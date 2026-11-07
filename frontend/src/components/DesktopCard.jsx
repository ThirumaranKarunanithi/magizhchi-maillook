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
    </section>
  )
}
