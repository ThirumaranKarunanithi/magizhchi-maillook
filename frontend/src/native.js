// When MailLook runs inside the Android app (mobile/), Capacitor puts its plugins on window.Capacitor. This
// file is the web side of that app: phone alerts (on by default), attachments saved and opened with the
// phone's own apps, outside links in the browser, and mail links from other apps opening a new mail.
// In a browser or the desktop app, `native` is null and nothing here does anything.
import { useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { getToken, post } from './api.js'
import { useMail } from './state.jsx'

const Cap = typeof window !== 'undefined' ? window.Capacitor : null
export const native = Cap && typeof Cap.isNativePlatform === 'function' && Cap.isNativePlatform() ? Cap : null
const plugin = (name) => (native && native.Plugins ? native.Plugins[name] : null)

const PHONE_TOKEN_KEY = 'mlook_phone_token'

// ── links ────────────────────────────────────────────────────────────────────

const APP_HOSTS = [window.location.host]

/** Opens a link outside MailLook: the phone's browser (Meet rooms, Box files, links in mail). */
export function openOutside(url) {
  if (!url) return
  const Browser = plugin('Browser')
  if (/^mailto:/i.test(url)) {
    window.dispatchEvent(new CustomEvent('mlook:mailto', { detail: url }))
    return
  }
  try {
    const u = new URL(url, window.location.href)
    if (APP_HOSTS.includes(u.host)) {
      window.location.assign(u.href)
      return
    }
  } catch {
    /* odd link: let the browser try */
  }
  if (Browser) Browser.open({ url, toolbarColor: '#0D6A75' }).catch(() => window.open(url, '_system'))
}

/**
 * In the app there is no second tab. MailLook opens a blank one inside the tap and points it somewhere a
 * moment later (a Meet room, a Box file); here that "tab" hands the address to the phone's browser instead.
 */
function patchWindowOpen() {
  const original = window.open.bind(window)
  window.open = (url, target, features) => {
    if (target === '_system') return original(url, '_blank', features)
    if (!url || url === 'about:blank') {
      const tab = { closed: false, close() { tab.closed = true }, focus() {} }
      tab.location = {
        set href(u) { if (!tab.closed) openOutside(u) },
        get href() { return 'about:blank' },
        assign(u) { if (!tab.closed) openOutside(u) },
        replace(u) { if (!tab.closed) openOutside(u) },
      }
      return tab
    }
    openOutside(String(url))
    return null
  }
}

// ── files ────────────────────────────────────────────────────────────────────

function base64(blob) {
  return new Promise((resolve, reject) => {
    const r = new FileReader()
    r.onload = () => resolve(String(r.result).split(',')[1] || '')
    r.onerror = () => reject(r.error)
    r.readAsDataURL(blob)
  })
}

/** Phones can't take a browser download: the file goes to MailLook's cache, then to the phone's apps. */
export async function nativeFile(blob, name, { open = false, contentType } = {}) {
  const Filesystem = plugin('Filesystem')
  if (!Filesystem) throw new Error('This phone can’t save files from MailLook.')
  const safe = String(name || 'attachment').replace(/[\\/:*?"<>|]+/g, '_').slice(0, 120) || 'attachment'
  const { uri } = await Filesystem.writeFile({ path: `files/${Date.now()}/${safe}`, data: await base64(blob), directory: 'CACHE', recursive: true })
  const type = contentType || blob.type || 'application/octet-stream'
  const FileOpener = plugin('FileOpener')
  const Share = plugin('Share')
  if (open && FileOpener) {
    try {
      await FileOpener.open({ filePath: uri, contentType: type, openWithDefault: true })
      return
    } catch {
      /* no app opens this type: offer to share or save it instead */
    }
  }
  if (Share) await Share.share({ title: safe, files: [uri], dialogTitle: `Save or share ${safe}` })
  else if (FileOpener) await FileOpener.open({ filePath: uri, contentType: type })
}

// ── alerts ───────────────────────────────────────────────────────────────────

/** What the phone says about alerts: { pushReady, notificationsOn, version } or null outside the app. */
export async function phoneInfo() {
  const N = plugin('MailLookNative')
  if (!N) return null
  try {
    return await N.info()
  } catch {
    return null
  }
}

export function openPhoneAlertSettings() {
  plugin('MailLookNative')?.openAlertSettings().catch(() => {})
}

let pushListening = false

/**
 * Alerts are on by default: asks the phone once (Android 13 and later show a question), then signs this
 * phone up with the server. Runs at every start, so a new Firebase token reaches the server.
 */
export async function startPhoneAlerts({ ask = true } = {}) {
  const Push = plugin('PushNotifications')
  const info = await phoneInfo()
  if (!Push || !info?.pushReady) return 'unavailable'
  let perm = await Push.checkPermissions()
  if (perm.receive === 'prompt' || perm.receive === 'prompt-with-rationale') {
    if (!ask) return 'ask'
    perm = await Push.requestPermissions()
  }
  if (perm.receive !== 'granted') return 'denied'
  if (!pushListening) {
    pushListening = true
    await Push.addListener('registration', (t) => { // a handle or a promise of one: await takes either
      if (!t?.value || !getToken()) return
      try {
        localStorage.setItem(PHONE_TOKEN_KEY, t.value)
      } catch {
        /* sent again next start */
      }
      post('/api/push/phone', { token: t.value, platform: native.getPlatform?.() || 'android' }).catch(() => {})
    })
    await Push.addListener('registrationError', () => {})
  }
  await Push.register()
  return 'on'
}

/** Signing out: this phone stops getting that person's alerts. */
export async function forgetPhone() {
  if (!native) return
  let token = null
  try {
    token = localStorage.getItem(PHONE_TOKEN_KEY)
  } catch {
    /* nothing stored */
  }
  if (token && getToken()) await post('/api/push/phone/remove', { token }).catch(() => {})
}

// ── mail links from other apps ───────────────────────────────────────────────

function parseMailto(url) {
  const out = { to: [], cc: [], subject: '', body: '' }
  try {
    const [addr, query = ''] = String(url).replace(/^mailto:/i, '').split('?')
    out.to = decodeURIComponent(addr || '').split(/[,;]/).map((s) => s.trim()).filter(Boolean)
    const q = new URLSearchParams(query)
    for (const [k, v] of q) {
      const key = k.toLowerCase()
      if (key === 'cc' || key === 'to') out[key].push(...v.split(/[,;]/).map((s) => s.trim()).filter(Boolean))
      else if (key === 'subject') out.subject = v
      else if (key === 'body') out.body = v
    }
  } catch {
    /* an empty new mail */
  }
  return out
}

// ── the bridge, inside the signed-in app ─────────────────────────────────────

let installed = false

/** Called once at start-up. */
export function installNative() {
  if (!native || installed) return
  installed = true
  document.documentElement.classList.add('in-app')
  patchWindowOpen()
}

/** Inside the mail shell: alerts, taps on alerts, mail links. */
export function NativeBridge() {
  const navigate = useNavigate()
  const { openComposer, notify } = useMail()

  useEffect(() => {
    if (!native) return undefined
    const offs = []
    const Push = plugin('PushNotifications')
    const App = plugin('App')
    // Plugins injected into a page the app loads from the web hand back the listener handle itself, not a
    // promise of it; accept both. Nothing here may stop MailLook from showing.
    const listen = (p, event, fn) => {
      try {
        Promise.resolve(p.addListener(event, fn)).then((h) => offs.push(h)).catch(() => {})
      } catch {
        /* this plugin isn't there */
      }
    }

    startPhoneAlerts().catch(() => {})

    if (Push) {
      listen(Push, 'pushNotificationActionPerformed', (a) => {
        const url = a?.notification?.data?.url
        if (url && url.startsWith('/')) navigate(url)
      })
      // While MailLook is open Android doesn't show the alert, so it shows here.
      listen(Push, 'pushNotificationReceived', (n) => {
        const kind = n?.data?.kind
        if (n?.title) notify(n.title + (n.body ? ` · ${n.body.split('\n')[0]}` : ''), kind === 'problem' ? 'error' : 'info')
      })
    }

    const compose = (url) => {
      const f = parseMailto(url)
      openComposer({ mode: 'new', to: f.to, cc: f.cc, subject: f.subject, draftBody: f.body })
    }
    const onMailto = (e) => compose(e.detail)
    window.addEventListener('mlook:mailto', onMailto)
    if (App) {
      listen(App, 'appUrlOpen', ({ url }) => {
        if (/^mailto:/i.test(url || '')) compose(url)
      })
      Promise.resolve().then(() => App.getLaunchUrl()).then((r) => {
        if (r?.url && /^mailto:/i.test(r.url) && sessionStorage.getItem('mlook_launch') !== r.url) {
          sessionStorage.setItem('mlook_launch', r.url) // once, not again after a reload
          compose(r.url)
        }
      }).catch(() => {})
    }

    return () => {
      window.removeEventListener('mlook:mailto', onMailto)
      offs.forEach((h) => h?.remove?.())
    }
  }, [navigate, openComposer, notify])

  return null
}
