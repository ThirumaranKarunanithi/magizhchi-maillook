import { useState } from 'react'
import { connectBox, planNotice, refreshBox, useBox } from '../box.js'
import { formatSize } from '../util.js'
import { IconCloudCheck, IconCloudUp } from './Icons.jsx'

const TONE = {
  info: 'border-peacock-200 bg-peacock-50 text-peacock-800',
  warn: 'border-amber-300 bg-amber-50 text-amber-900',
  error: 'border-red-300 bg-red-50 text-red-800',
}

/** Magizhchi Box on the Accounts page: where saved attachments go, how much space is left, and the plan. */
export default function BoxCard({ notify }) {
  const box = useBox()
  const [busy, setBusy] = useState(false)
  if (!box?.configured) return null
  const u = box.usage
  const pct = u?.limitBytes ? Math.min(100, (u.usedBytes / u.limitBytes) * 100) : 0
  const notice = planNotice(u)

  async function connect() {
    setBusy(true)
    try {
      await connectBox()
      notify('Magizhchi Box is connected. Saved attachments go to your own drive.', 'success')
    } catch (e) {
      notify(e.message, 'error')
      refreshBox()
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="card p-5" aria-label="Magizhchi Box">
      <div className="flex flex-wrap items-start gap-3">
        <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-peacock-50 text-peacock-700 ring-1 ring-peacock-200/70">
          {box.connected ? <IconCloudCheck size={22} /> : <IconCloudUp size={22} />}
        </span>
        <div className="min-w-0 flex-1">
          <h2 className="text-base font-bold">
            Magizhchi Box
            {u?.planLabel && <span className="chip ml-2 align-middle">{u.planLabel} plan</span>}
          </h2>
          <p className="mt-0.5 text-sm text-slate-600">
            {box.connected
              ? <>Attachments you save go to <b className="font-semibold text-brand-ink">{box.folder}</b> in your own Box, one folder per month.</>
              : 'Tap the cloud on any attachment to keep it in your own Magizhchi Box. It connects by itself the first time.'}
          </p>
        </div>
        {box.connected ? (
          box.webUrl && <a className="btn-secondary" href={box.webUrl} target="_blank" rel="noopener noreferrer">Open Box</a>
        ) : (
          <button className="btn-secondary" onClick={connect} disabled={busy}>
            {busy && <span className="spinner" />} Connect now
          </button>
        )}
      </div>

      {u && (
        <div className="mt-4">
          <div className="mb-1 flex justify-between text-xs text-slate-600">
            <span><b className="font-semibold text-brand-ink">{formatSize(u.usedBytes)}</b> used</span>
            <span>{formatSize(u.limitBytes)}</span>
          </div>
          <div className="h-2 overflow-hidden rounded-full bg-slate-100 ring-1 ring-slate-200/70" role="progressbar"
            aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(pct)} aria-label="Box space used">
            <div className={`h-full rounded-full ${pct >= 90 ? 'bg-red-500' : pct >= 75 ? 'bg-amber-500' : 'bg-peacock-500'}`}
              style={{ width: `${Math.max(pct, u.usedBytes > 0 ? 1.5 : 0)}%` }} />
          </div>
        </div>
      )}

      {notice && (
        <div role="status" className={`mt-3 flex flex-wrap items-center gap-x-3 gap-y-1 rounded-xl border px-3 py-2 text-sm ${TONE[notice.tone]}`}>
          <span className="min-w-0 flex-1">{notice.text}</span>
          {notice.tone !== 'info' && box.webUrl && (
            <a className="font-bold underline" href={box.webUrl} target="_blank" rel="noopener noreferrer">Recharge</a>
          )}
        </div>
      )}
      {box.message && <p className="mt-3 text-sm text-red-700">{box.message}</p>}
    </section>
  )
}
