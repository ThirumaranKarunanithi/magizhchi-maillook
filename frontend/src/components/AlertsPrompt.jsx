import { useEffect, useState } from 'react'
import { get, post, put } from '../api.js'
import { useMail } from '../state.jsx'
import { currentSubscription, enablePush, needsHomeScreen, pushSupported } from '../push.js'
import { IconBell, IconX } from './Icons.jsx'

// Alerts are on by default as far as a browser lets them be:
//   • the browser already allows MailLook's alerts → this device is signed up without asking
//   • it hasn't been asked yet → a small card asks once (browsers only show their question after a click)
//   • "Not now" hides the card for a week; blocked, iPhone Safari outside the home screen, or alerts set
//     to Off → nothing here (Manage accounts explains those)
const LATER_KEY = 'mlook_alerts_later'
const LATER_MS = 7 * 24 * 3600 * 1000

function askedRecently() {
  try {
    return Date.now() - Number(localStorage.getItem(LATER_KEY) || 0) < LATER_MS
  } catch {
    return false
  }
}

export default function AlertsPrompt() {
  const { notify } = useMail()
  const [settings, setSettings] = useState(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    // The morning briefing (on at 8 by default) comes at the person's own hour, so the server needs their zone.
    try {
      const tz = Intl.DateTimeFormat().resolvedOptions().timeZone
      if (tz) put('/api/push', { tz }).catch(() => {})
    } catch {
      /* no zone */
    }
    if (!pushSupported() || needsHomeScreen()) return undefined
    let alive = true
    const t = setTimeout(async () => {
      try {
        const s = await get('/api/push')
        if (!alive || !s?.configured || !s.publicKey || s.mode === 'off') return
        const sub = await currentSubscription()
        if (Notification.permission === 'granted') {
          if (sub) {
            // Already signed up here; tell the server again in case it forgot this browser.
            const j = sub.toJSON()
            await post('/api/push/subscribe', { endpoint: j.endpoint, keys: j.keys })
          } else {
            await enablePush(s.publicKey) // allowed before: no question shows
          }
          return
        }
        if (Notification.permission === 'default' && !sub && !askedRecently() && alive) setSettings(s)
      } catch {
        /* alerts stay as they are; Manage accounts can turn them on */
      }
    }, 2500)
    return () => {
      alive = false
      clearTimeout(t)
    }
  }, [])

  if (!settings) return null

  async function turnOn() {
    setBusy(true)
    try {
      await enablePush(settings.publicKey)
      notify('Alerts are on for this device ✓', 'success')
      setSettings(null)
    } catch (e) {
      notify(e.message, 'error')
      later()
    } finally {
      setBusy(false)
    }
  }

  function later() {
    try {
      localStorage.setItem(LATER_KEY, String(Date.now()))
    } catch {
      /* asks again next time */
    }
    setSettings(null)
  }

  return (
    <div role="dialog" aria-label="Turn on alerts"
      className="card fixed bottom-24 right-4 z-40 lg:bottom-4 w-[22rem] max-w-[calc(100vw-2rem)] p-4 shadow-xl">
      <div className="flex items-start gap-3">
        <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-amber-50 text-amber-700">
          <IconBell size={18} />
        </span>
        <div className="min-w-0 flex-1">
          <h2 className="text-sm font-bold">Get a ping when someone needs you</h2>
          <p className="mt-0.5 text-xs text-slate-500">
            New mail that needs you, snoozed mail coming back, nobody replied, and your morning briefing, even when MailLook is closed.
          </p>
          <div className="mt-3 flex gap-2">
            <button className="btn-primary !py-1 text-xs" onClick={turnOn} disabled={busy}>
              {busy && <span className="spinner" />} Turn on alerts
            </button>
            <button className="btn-secondary !py-1 text-xs" onClick={later} disabled={busy}>Not now</button>
          </div>
        </div>
        <button className="text-slate-400 hover:text-slate-600" onClick={later} aria-label="Close"><IconX size={16} /></button>
      </div>
    </div>
  )
}
