import { useState } from 'react'
import { put } from '../api.js'
import { connectBox, planNotice, refreshBox, useBox } from '../box.js'
import { formatSize } from '../util.js'
import { IconCloudCheck, IconCloudUp } from './Icons.jsx'

const STORES = [
  { key: 'box', label: 'Magizhchi Box', hint: 'Every attachment is saved to your Box by itself. Files searches the words inside them.' },
  { key: 'local', label: 'This computer', hint: 'Attachments stay in your mail. Files lists them, and they download to this device.' },
]

const TROUBLE = {
  full: 'Your Box is full or its plan has ended, so saving has paused. Recharge in Magizhchi Box and it carries on.',
  no_id: 'Saving needs you signed in with Magizhchi ID.',
  unreachable: 'Couldn’t reach Magizhchi Box or your mailbox just now. It tries again in a few minutes.',
}

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

  async function setStore(store) {
    if (store === box.filesStore) return
    setBusy(true)
    try {
      await put('/api/box/files-store', { store })
      await refreshBox()
      notify(store === 'box' ? 'Attachments now go to your Magizhchi Box by themselves.' : 'Attachments stay in your mail and download to this computer.', 'success')
    } catch (e) {
      notify(e.message, 'error')
    } finally {
      setBusy(false)
    }
  }

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
              ? <>Attachments go to <b className="font-semibold text-brand-ink">{box.folder}</b> in your own Box, one folder per month.</>
              : box.filesStore === 'box'
                ? 'Your attachments go to your own Magizhchi Box. It connects by itself in a few minutes, or now with the button.'
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

      <fieldset className="mt-4" disabled={busy}>
        <legend className="mb-1.5 text-sm font-semibold text-brand-ink">Where attachments live</legend>
        <div className="grid gap-2 sm:grid-cols-2">
          {STORES.map((o) => {
            const on = (box.filesStore || 'box') === o.key
            return (
              <label key={o.key} className={`flex cursor-pointer gap-2.5 rounded-xl border px-3 py-2.5 transition-colors ${on
                ? 'border-peacock-400 bg-peacock-50/70' : 'border-slate-200 hover:border-slate-300'}`}>
                <input type="radio" name="files-store" className="mt-1 accent-[#0e7c86]" checked={on} onChange={() => setStore(o.key)} />
                <span className="min-w-0">
                  <span className="block text-sm font-semibold text-brand-ink">{o.label}</span>
                  <span className="block text-xs text-slate-600">{o.hint}</span>
                </span>
              </label>
            )
          })}
        </div>
        {box.filesStore === 'box' && box.autoSave && (
          box.autoSave.state ? (
            <p role="status" className={`mt-2 rounded-xl border px-3 py-2 text-sm ${box.autoSave.state === 'full' ? TONE.warn : TONE.info}`}>
              {TROUBLE[box.autoSave.state] || box.autoSave.note}
            </p>
          ) : box.autoSave.backlog > 0 ? (
            <p role="status" className="mt-2 flex items-center gap-2 text-xs text-slate-600">
              <span className="spinner !h-3 !w-3" /> Saving attachments from {box.autoSave.backlog.toLocaleString()} mail{box.autoSave.backlog === 1 ? '' : 's'} to your Box, newest first…
            </p>
          ) : (
            <p className="mt-2 text-xs text-slate-600">Every attachment is in your Box.</p>
          )
        )}
      </fieldset>

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
