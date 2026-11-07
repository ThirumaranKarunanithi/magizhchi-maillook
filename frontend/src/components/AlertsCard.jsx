import { useEffect, useState } from 'react'
import { get, post, put } from '../api.js'
import { useMail } from '../state.jsx'
import { currentSubscription, disablePush, enablePush, needsHomeScreen, pushSupported } from '../push.js'
import { desktop, desktopFeed, setDesktopAlertMode } from '../desktop.js'
import { native, openPhoneAlertSettings, phoneInfo, startPhoneAlerts } from '../native.js'
import { IconBell } from './Icons.jsx'

const TZ = (() => {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || ''
  } catch {
    return ''
  }
})()

const HOURS = [5, 6, 7, 8, 9, 10]

const MODES = [
  { key: 'needs_me', label: 'Needs me', hint: 'People waiting on you, and your reminders' },
  { key: 'all', label: 'All new mail', hint: 'Everything except newsletters you set to Can wait' },
  { key: 'off', label: 'Off', hint: 'No alerts, not even reminders' },
]

/** Alerts on this browser or phone: turn them on, choose which mail rings, send a test. */
export default function AlertsCard({ compact = false }) {
  const { notify } = useMail()
  const [s, setS] = useState(null)
  const [here, setHere] = useState(null) // this browser subscribed?
  const [busy, setBusy] = useState(false)
  const [phone, setPhone] = useState(null) // the Android app: { pushReady, notificationsOn }

  useEffect(() => {
    get('/api/push').then(setS).catch(() => setS({ configured: false }))
    // The morning briefing comes at the person's own hour, so the server needs their time zone.
    if (TZ) put('/api/push', { tz: TZ }).catch(() => {})
    if (native) phoneInfo().then(setPhone)
    else currentSubscription().then((sub) => setHere(!!sub)).catch(() => setHere(false))
  }, [])

  if (!s) return null
  const supported = pushSupported()
  const blocked = supported && Notification.permission === 'denied'
  // The settings below apply wherever alerts can ring: browsers, the desktop app and the phone app.
  const canSet = s.configured || !!desktop || (!!native && s.phonesConfigured)
  const phoneOn = !!(native && phone?.pushReady && phone?.notificationsOn && s.phonesConfigured)

  async function phoneTurnOn() {
    setBusy(true)
    try {
      const r = await startPhoneAlerts()
      if (r === 'denied') openPhoneAlertSettings()
      setPhone(await phoneInfo())
      setS(await get('/api/push'))
    } finally {
      setBusy(false)
    }
  }

  async function turnOn() {
    setBusy(true)
    try {
      setS(await enablePush(s.publicKey))
      setHere(true)
      notify('Alerts are on for this device ✓', 'success')
    } catch (e) {
      notify(e.message, 'error')
    } finally {
      setBusy(false)
    }
  }

  async function turnOff() {
    setBusy(true)
    try {
      setS(await disablePush())
      setHere(false)
      notify('Alerts are off for this device', 'info')
    } catch (e) {
      notify(e.message, 'error')
    } finally {
      setBusy(false)
    }
  }

  async function setMode(mode) {
    try {
      setS(await put('/api/push', { mode }))
      setDesktopAlertMode(mode)
    } catch (e) {
      notify(e.message, 'error')
    }
  }

  async function setBriefing(value) {
    try {
      setS(await put('/api/push', value === 'off' ? { briefing: false, tz: TZ || null } : { briefingHour: Number(value), tz: TZ || null }))
    } catch (e) {
      notify(e.message, 'error')
    }
  }

  async function test() {
    try {
      await post('/api/push/test')
      notify('Test alert sent. It shows in a few seconds.', 'info')
    } catch (e) {
      notify(e.message, 'error')
    }
  }

  return (
    <section className={compact ? '' : 'card p-4'} aria-label="Alerts">
      <div className="flex items-start gap-3">
        <span className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-xl ${here || phoneOn ? 'bg-amber-50 text-amber-700' : 'bg-slate-100 text-slate-500'}`}>
          <IconBell size={18} />
        </span>
        <div className="min-w-0 flex-1">
          <h2 className="text-sm font-bold">Alerts on your phone and computer</h2>
          <p className="mt-0.5 text-xs text-slate-500">
            {native ? (!phone ? 'Checking this phone…'
              : !phone.pushReady ? 'This copy of the app can’t get alerts yet. The next version can.'
                : !s.phonesConfigured ? 'Phone alerts aren’t set up on this server yet.'
                  : !phone.notificationsOn ? 'Alerts are off for MailLook on this phone. Turn them on to hear about mail that needs you.'
                    : s.mode === 'off' ? 'Alerts are off. Choose which mail rings on this phone and your computer.'
                      : 'On for this phone, with MailLook’s chime (reminders get a double chime). They ring even when MailLook is closed.')
            : desktop ? (s.mode === 'off' ? 'Alerts are off. Choose which mail rings on this computer and your phone.'
              : desktopFeed ? 'This app rings for new mail and for your reminders (snoozes, no reply, the morning briefing) while it runs in the tray. Sounds are in the MailLook for Windows card.'
                : 'This app alerts you while it runs in the tray, and shows the Needs me count on the taskbar.')
              : !s.configured ? 'Alerts aren’t set up on this server yet.'
              : !supported ? 'This browser can’t show alerts. Chrome, Edge, Firefox and Safari can.'
                : needsHomeScreen() ? 'On iPhone and iPad: tap Share, then “Add to Home Screen”, open MailLook from there, and turn alerts on.'
                  : blocked ? 'Alerts are blocked for MailLook in this browser. Allow them in the site settings, then come back.'
                    : here ? `On for this device${s.devices > 1 ? ` and ${s.devices - 1} more` : ''}. They ring even when MailLook is closed.`
                      : 'Get a ping when someone needs you, a snooze ends, or nobody replied.'}
          </p>
        </div>
        {native && phone?.pushReady && s.phonesConfigured && (
          phoneOn ? <button className="btn-secondary !py-1 text-xs" onClick={openPhoneAlertSettings}>Sounds</button>
            : <button className="btn-primary !py-1 text-xs" onClick={phoneTurnOn} disabled={busy}>{busy && <span className="spinner" />} Turn on</button>
        )}
        {s.configured && supported && !needsHomeScreen() && !blocked && (
          here ? <button className="btn-secondary !py-1 text-xs" onClick={turnOff} disabled={busy}>Turn off</button>
            : <button className="btn-primary !py-1 text-xs" onClick={turnOn} disabled={busy}>{busy && <span className="spinner" />} Turn on</button>
        )}
      </div>
      {canSet && (
        <div className="mt-3 flex flex-wrap items-center gap-1.5" role="radiogroup" aria-label="Which mail rings">
          {MODES.map((m) => (
            <button key={m.key} role="radio" aria-checked={s.mode === m.key} onClick={() => setMode(m.key)} title={m.hint}
              className={`rounded-full border px-2.5 py-1 text-xs font-semibold ${s.mode === m.key ? 'border-peacock-300 bg-peacock-50 text-peacock-800' : 'border-slate-200 text-slate-600 hover:text-brand-ink'}`}>
              {m.label}
            </button>
          ))}
          {(here || phoneOn) && <button className="ml-auto text-xs font-semibold text-peacock-700 hover:underline" onClick={test}>Send a test</button>}
        </div>
      )}
      {canSet && s.mode !== 'off' && (
        <label className="mt-2.5 flex flex-wrap items-center gap-2 text-xs text-slate-600">
          <span>Morning briefing</span>
          <select className="rounded-lg border border-slate-200 bg-transparent px-1.5 py-1 text-xs outline-none" value={s.briefingHour ?? 'off'}
            onChange={(e) => setBriefing(e.target.value)} aria-label="Morning briefing alert">
            {HOURS.map((h) => <option key={h} value={h}>{h}:00 AM</option>)}
            <option value="off">Off</option>
          </select>
          <span className="text-slate-500">who's waiting on you, and today's meetings</span>
        </label>
      )}
    </section>
  )
}
