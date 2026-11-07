// When MailLook runs inside the desktop app (desktop/), the app offers a few extras through
// window.maillookDesktop: a taskbar count, alerts of its own, and mail links that open a new mail here.
// In a browser this is all null and nothing changes.
import { useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { get } from './api.js'
import { useMail } from './state.jsx'

export const desktop = typeof window !== 'undefined' ? window.maillookDesktop || null : null

let lastSeen = null
let alertMode = 'needs_me'

const lanes = (c) => c?.lanes || {}
const unread = (c, lane) => lanes(c)[lane]?.unread ?? 0

/** Called with each /api/counts: the taskbar count, and an alert when more mail arrives. */
export function desktopCounts(c) {
  if (!desktop || !c) return
  const needsMe = unread(c, 'needs_me')
  desktop.setBadge(needsMe)
  const watched = alertMode === 'all' ? Object.keys(lanes(c)).reduce((n, k) => n + unread(c, k), 0) : needsMe
  if (lastSeen != null && alertMode !== 'off' && watched > lastSeen) {
    const more = watched - lastSeen
    if (alertMode === 'all') desktop.notify(more === 1 ? 'New mail' : `${more} new mails`, 'Open MailLook to read it.', '/')
    else desktop.notify(more === 1 ? 'A new mail needs you' : `${more} new mails need you`, `${needsMe} waiting in Needs me.`, '/?view=needs_me')
  }
  lastSeen = watched
}

/** The alert setting (Needs me / All new mail / Off) is the same one phones use. */
export function setDesktopAlertMode(mode) {
  if (mode) alertMode = mode
}

/** Inside the mail shell: listens for "open this page" and "write a new mail" from the desktop app. */
export function DesktopBridge() {
  const navigate = useNavigate()
  const { openComposer } = useMail()
  useEffect(() => {
    if (!desktop) return undefined
    get('/api/push').then((s) => setDesktopAlertMode(s?.mode)).catch(() => {})
    const offOpen = desktop.onOpen((path) => navigate(path))
    const offCompose = desktop.onCompose((json) => {
      let f = {}
      try {
        f = JSON.parse(json) || {}
      } catch {
        /* an empty new mail */
      }
      openComposer({ mode: 'new', to: f.to || [], cc: f.cc || [], subject: f.subject || '', draftBody: f.body || '' })
    })
    return () => {
      offOpen()
      offCompose()
    }
  }, [navigate, openComposer])
  return null
}
