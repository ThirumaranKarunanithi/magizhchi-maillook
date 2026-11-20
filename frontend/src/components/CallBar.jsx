import { useEffect } from 'react'
import { get, qs } from '../api.js'
import { native } from '../native.js'
import { desktop } from '../desktop.js'

/** Where Messenger serves the Magizhchi bar (chat + calls) and Meet serves its call bar. */
const MESSENGER_WEB = (import.meta.env.VITE_MESSENGER_WEB_URL || 'https://messenger.magizhchi.software').replace(/\/+$/, '')
const MEET_WEB = (import.meta.env.VITE_MEET_WEB_URL || 'https://meet.magizhchi.software').replace(/\/+$/, '')
const BAR_URL = `${MESSENGER_WEB}/bar/magizhchi-bar.js`
const SDK_URL = `${MEET_WEB}/sdk/magizhchi-call.js`

const loads = {}
function loadScript(src, global) {
  if (window[global]) return Promise.resolve(window[global])
  if (!loads[src]) {
    loads[src] = new Promise((resolve, reject) => {
      const s = document.createElement('script')
      s.src = src
      s.async = true
      s.onload = () => (window[global] ? resolve(window[global]) : reject(new Error(`${global} missing`)))
      s.onerror = () => { delete loads[src]; reject(new Error(`${src} did not load`)) }
      document.head.appendChild(s)
    })
  }
  return loads[src]
}

const fetchBarPasses = (args) => get(`/api/calls/bar-pass${qs(args)}`)
const fetchRingPass = (args) => get(`/api/calls/ring-pass${qs(args)}`)

/**
 * The Magizhchi bar inside MailLook: one chat bubble for Magizhchi Messenger (chat, stored in Box chat) and Meet's
 * call bar (calls ring here; Answer opens the call in Meet). The person is already signed in with Magizhchi ID,
 * so MailLook's server hands the bar their own passes; there is no second sign-in. Renders nothing itself.
 *
 * Only for people signed in with Magizhchi ID: for anyone else the pass request answers 409 and nothing shows.
 * If chat can't start (not set up), calls still ring through Meet's call bar on its own, as before.
 */
export default function CallBar({ enabled = true }) {
  // Off inside the Android app for now (mobile/): the phone shell isn't ready for chat or calls yet.
  const on = enabled && !native
  useEffect(() => {
    if (!on) return undefined
    let stopped = false
    let mode = null   // 'bar' | 'calls'

    loadScript(BAR_URL, 'MagizhchiBar')
      .then((bar) => (stopped ? false : bar.start({ getPasses: fetchBarPasses, calls: true })))
      .catch(() => false)
      .then((on) => {
        if (stopped) return null
        if (on) { mode = 'bar'; return null }
        return startCallsOnly(() => stopped).then((ok) => { if (ok) mode = 'calls' })
      })
      .catch(() => { /* chat and calls stay off in MailLook for this person; nothing to show */ })

    return () => {
      stopped = true
      try { window.MagizhchiBar?.stop() } catch { /* not loaded */ }
      if (mode === 'calls') { try { window.MagizhchiCall?.stop() } catch { /* not loaded */ } }
    }
  }, [on])
  return null
}

/** Meet's call bar alone (the old call bar): one pass first; a 409 or 503 means stay off, no retrying. */
function startCallsOnly(isStopped) {
  let first = null
  return loadScript(SDK_URL, 'MagizhchiCall')
    .then((sdk) => fetchRingPass({ device: deviceId(), client: clientKind() }).then((p) => { first = p; return sdk }))
    .then((sdk) => {
      if (isStopped()) return false
      sdk.start({
        getPass: (args) => {
          if (first) { const p = first; first = null; return Promise.resolve(p) }
          return fetchRingPass(args)
        },
      })
      return true
    })
}

// Same device id and client kind the bars themselves use, so the first pass and later ones match.
function deviceId() {
  try {
    let d = localStorage.getItem('magizhchi.call.device')
    if (!d) { d = `d-${Math.random().toString(36).slice(2)}${Date.now().toString(36)}`; localStorage.setItem('magizhchi.call.device', d) }
    return d
  } catch { return 'unknown' }
}

function clientKind() {
  const ua = navigator.userAgent || ''
  if (desktop || /Electron/i.test(ua)) return 'desktop'
  if (/Android|iPhone|iPad/i.test(ua)) return 'phone'
  return 'web'
}
