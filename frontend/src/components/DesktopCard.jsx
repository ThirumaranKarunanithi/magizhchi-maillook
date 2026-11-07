import { useEffect, useState } from 'react'
import { desktop } from '../desktop.js'
import { relativeTime } from '../util.js'
import { IconDownload, IconInstall, IconRefresh } from './Icons.jsx'

/** Only in the desktop app: its version, and how it updates (automatically, or when you say so). */
export default function DesktopCard() {
  const updates = desktop?.updates
  const [u, setU] = useState(null)

  useEffect(() => {
    if (!updates) return undefined
    updates.get().then(setU).catch(() => {})
    return updates.onChange(setU)
  }, [updates])

  if (!updates || !u) return null
  const run = (fn) => fn().then(setU).catch(() => {})
  const busy = u.state === 'checking' || u.state === 'downloading'

  const line = !u.packaged ? 'This copy runs from source, so it doesn’t update itself.'
    : u.state === 'checking' ? 'Checking for a new version…'
      : u.state === 'available' ? `Version ${u.version} is out.${u.auto ? '' : ' Download it when it suits you.'}`
        : u.state === 'downloading' ? `Downloading version ${u.version}… ${u.percent}%`
          : u.state === 'ready' ? `Version ${u.version} is ready. ${u.auto ? 'It installs the next time MailLook restarts.' : 'Restart MailLook to install it.'}`
            : u.checkedAt ? `Up to date. Checked ${relativeTime(u.checkedAt)}.` : 'MailLook checks for a new version when it starts and every six hours.'

  return (
    <section className="card p-4" aria-label="Desktop app">
      <div className="flex items-start gap-3">
        <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-peacock-50 text-peacock-700">
          <IconInstall size={18} />
        </span>
        <div className="min-w-0 flex-1">
          <h2 className="text-sm font-bold">MailLook for Windows <span className="num font-normal text-slate-500">{u.current}</span></h2>
          <p className="mt-0.5 text-xs text-slate-500" aria-live="polite">{line}</p>
          {u.error && <p className="mt-1 text-xs text-red-600">{u.error}</p>}
          {u.state === 'downloading' && (
            <div className="mt-2 h-1.5 overflow-hidden rounded-full bg-slate-100" role="progressbar" aria-valuenow={u.percent} aria-valuemin={0} aria-valuemax={100}>
              <div className="h-full rounded-full bg-peacock-500 transition-[width]" style={{ width: `${u.percent}%` }} />
            </div>
          )}
        </div>
        {u.packaged && (
          u.state === 'ready' ? (
            <button className="btn-primary !py-1 text-xs" onClick={() => run(updates.install)}>Restart and update</button>
          ) : u.state === 'available' ? (
            <button className="btn-primary !py-1 text-xs" onClick={() => run(updates.download)}><IconDownload size={14} /> Download {u.version}</button>
          ) : (
            <button className="btn-secondary !py-1 text-xs" onClick={() => run(updates.check)} disabled={busy}>
              {busy ? <span className="spinner !h-3 !w-3" /> : <IconRefresh size={14} />} Check now
            </button>
          )
        )}
      </div>
      {u.packaged && (
        <label className="mt-3 flex cursor-pointer items-start gap-2.5 text-sm">
          <input type="checkbox" className="mt-0.5 h-4 w-4 accent-peacock-600" checked={u.auto} onChange={(e) => run(() => updates.setAuto(e.target.checked))} />
          <span>
            <span className="font-semibold">Update automatically</span>
            <span className="block text-xs text-slate-500">
              {u.auto ? 'New versions download in the background and install when MailLook restarts.'
                : 'MailLook tells you when a new version is out, and downloads it only when you say so.'}
            </span>
          </span>
        </label>
      )}
      <AlertSounds />
    </section>
  )
}

const SOUND_LABELS = { chime: 'Chime', soft: 'Soft bell', bright: 'Bright' }

/** MailLook's own sounds: on or off, how loud, which one for new mail; and a test alert. */
function AlertSounds() {
  const alerts = desktop?.alerts
  const [a, setA] = useState(null)
  const [tested, setTested] = useState('')

  useEffect(() => {
    if (!alerts) return
    alerts.get().then(setA).catch(() => {})
  }, [alerts])

  if (!alerts || !a) return null
  const save = (patch) => {
    setA((x) => ({ ...x, ...patch }))
    alerts.set(patch).then(setA).catch(() => {})
  }
  const test = (kind) => {
    alerts.test(kind).catch(() => {})
    setTested(kind === 'reminder' ? 'A test reminder is on screen. It stays until you close it.' : 'A test alert is on screen. Didn’t see it? Check Windows Settings → System → Notifications → Magizhchi MailLook.')
  }

  return (
    <div className="mt-4 border-t border-slate-100 pt-3">
      <h3 className="text-sm font-semibold">Alert sounds</h3>
      <p className="mt-0.5 text-xs text-slate-500">
        A chime for new mail and a double chime for reminders. Reminders and mails that didn’t send stay on screen until you close them.
      </p>
      <label className="mt-3 flex cursor-pointer items-center gap-2.5 text-sm">
        <input type="checkbox" className="h-4 w-4 accent-peacock-600" checked={a.sound} onChange={(e) => save({ sound: e.target.checked })} />
        <span className="font-semibold">Play sounds</span>
      </label>
      {a.sound && (
        <div className="mt-3 grid gap-3 sm:grid-cols-2">
          <div className="text-xs text-slate-600">
            <label htmlFor="ml-mail-sound">New mail sound</label>
            <div className="mt-1 flex gap-2">
              <select id="ml-mail-sound" className="input !py-1 text-sm" value={a.mailSound} onChange={(e) => { save({ mailSound: e.target.value }); alerts.play(e.target.value) }}>
                {(a.sounds || Object.keys(SOUND_LABELS)).map((s) => <option key={s} value={s}>{SOUND_LABELS[s] || s}</option>)}
              </select>
              <button type="button" className="btn-secondary !py-1 text-xs" onClick={() => alerts.play(a.mailSound)}>Play</button>
              <button type="button" className="btn-secondary !py-1 text-xs whitespace-nowrap" onClick={() => alerts.play('reminder')}>Reminder sound</button>
            </div>
          </div>
          <label className="text-xs text-slate-600">
            Volume <span className="num">{Math.round(a.volume * 100)}%</span>
            <input type="range" min="5" max="100" step="5" className="mt-2 block w-full accent-peacock-600" value={Math.round(a.volume * 100)}
              onChange={(e) => setA((x) => ({ ...x, volume: Number(e.target.value) / 100 }))}
              onPointerUp={(e) => { save({ volume: Number(e.currentTarget.value) / 100 }); alerts.play(a.mailSound) }}
              onKeyUp={(e) => save({ volume: Number(e.currentTarget.value) / 100 })} />
          </label>
        </div>
      )}
      <div className="mt-3 flex flex-wrap gap-2">
        <button type="button" className="btn-secondary !py-1 text-xs" onClick={() => test('mail')}>Send a test alert</button>
        <button type="button" className="btn-secondary !py-1 text-xs" onClick={() => test('reminder')}>Test a reminder</button>
      </div>
      {tested && <p className="mt-2 text-xs text-slate-500" aria-live="polite">{tested}</p>}
    </div>
  )
}
