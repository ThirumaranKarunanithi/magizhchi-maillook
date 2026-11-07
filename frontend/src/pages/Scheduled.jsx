import { useCallback, useEffect, useState } from 'react'
import { get, post } from '../api.js'
import { useMail } from '../state.jsx'
import { localInput, whenLabel } from '../when.js'
import { IconAlert, IconClock, IconMenu, IconPaperclip, IconRefresh, IconSend } from '../components/Icons.jsx'

/** Mail waiting to go at a set time, and mail that couldn't be sent: send now, change the time, edit or cancel. */
export default function Scheduled() {
  const { setMenuOpen, accounts, notify, undoSend, refreshCounts, bumpMail } = useMail()
  const [items, setItems] = useState(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(null)
  const [moving, setMoving] = useState(null) // id whose time is being changed
  const [newAt, setNewAt] = useState('')

  const load = useCallback(async () => {
    setError('')
    try {
      setItems(await get('/api/outbox'))
    } catch (e) {
      setError(e.message)
    }
  }, [])

  useEffect(() => {
    load()
    const t = setInterval(load, 20000)
    return () => clearInterval(t)
  }, [load])

  async function run(id, fn, done) {
    setBusy(id)
    try {
      await fn()
      if (done) notify(done, 'success')
      await load()
      refreshCounts()
    } catch (e) {
      notify(e.message, 'error')
      load()
    } finally {
      setBusy(null)
    }
  }

  const sendNow = (i) => run(i.id, () => post(`/api/outbox/${i.id}/now`), 'Sending now ✓').then(() => setTimeout(bumpMail, 6000))
  const edit = (i) => run(i.id, () => undoSend(i.id))
  const cancel = (i) => run(i.id, () => undoSend(i.id, { reopen: false }), 'Cancelled. It’s back in Drafts.')
  const reschedule = (i) => run(i.id, async () => {
    await post(`/api/outbox/${i.id}/reschedule`, { sendAt: new Date(newAt).toISOString() })
    setMoving(null)
  }, 'New time saved ✓')

  const visible = (items || []).filter((i) => i.scheduled || i.status === 'failed')
  const account = (id) => accounts.find((a) => a.id === id)

  return (
    <section className="slab rise-3d d1 flex min-h-0 flex-1 flex-col overflow-hidden max-lg:rounded-none">
      <header className="border-b border-slate-200/70 px-3 py-2.5 sm:px-4">
        <div className="flex items-center gap-2">
          <button className="icon-btn lg:hidden" onClick={() => setMenuOpen(true)} aria-label="Open menu"><IconMenu /></button>
          <IconClock size={20} className="hidden text-peacock-600 sm:block" />
          <div className="min-w-0 flex-1">
            <h1 className="truncate text-base font-bold leading-tight">Scheduled</h1>
            <p className="truncate text-[11px] text-slate-500">Mail that goes out later, sent by MailLook even when it's closed</p>
          </div>
          <button className="icon-btn" onClick={load} title="Refresh"><IconRefresh size={17} /></button>
        </div>
      </header>

      <div className="scroll-thin min-h-0 flex-1 overflow-y-auto px-3 py-4 max-md:pb-24 sm:px-6">
        <div className="mx-auto max-w-3xl space-y-2.5">
          {error && <p className="rounded-xl border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700" role="alert">{error}</p>}
          {items == null && !error && <div className="h-24 animate-pulse rounded-2xl bg-slate-100" aria-busy="true" />}
          {items != null && visible.length === 0 && (
            <div className="py-16 text-center">
              <p className="text-4xl" aria-hidden="true">🕰️</p>
              <p className="mt-3 font-semibold text-brand-ink">Nothing scheduled</p>
              <p className="mx-auto mt-1 max-w-sm text-sm text-slate-600">In a new mail, open the arrow next to Send and pick a time. It waits here until then.</p>
            </div>
          )}
          {visible.map((i) => {
            const a = account(i.accountId)
            const failed = i.status === 'failed'
            return (
              <article key={i.id} className={`rounded-2xl border p-3 sm:p-4 ${failed ? 'border-red-200 bg-red-50/60' : 'border-slate-200/80 bg-white/70'}`}>
                <div className="flex items-start gap-3">
                  <span className="mt-1.5 h-2 w-2 shrink-0 rounded-full" style={{ background: a?.color || '#0e7c86' }} />
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-sm font-semibold text-brand-ink">To: {[...(i.to || []), ...(i.cc || [])].join(', ') || '(nobody)'}</p>
                    <p className="truncate text-[13px] text-slate-800">{i.subject || '(no subject)'}</p>
                    {i.snippet && <p className="truncate text-xs text-slate-500">{i.snippet}</p>}
                    {i.files?.length > 0 && (
                      <p className="mt-1 flex items-center gap-1 text-[11px] text-slate-500"><IconPaperclip size={11} /> {i.files.map((f) => f.name).join(', ')}</p>
                    )}
                  </div>
                  <span className={`shrink-0 rounded-md px-1.5 py-0.5 text-[11px] font-semibold ring-1 ${failed ? 'bg-red-50 text-red-800 ring-red-200' : 'bg-peacock-50 text-peacock-800 ring-peacock-200'}`}>
                    {failed ? 'Not sent' : i.status === 'sending' ? 'Sending…' : whenLabel(i.sendAt)}
                  </span>
                </div>
                {failed && i.error && <p className="mt-2 flex items-start gap-1.5 text-xs text-red-800"><IconAlert size={13} className="mt-px shrink-0" />{i.error}</p>}
                {moving === i.id ? (
                  <div className="mt-3 flex flex-wrap items-center gap-2">
                    <input type="datetime-local" className="rounded-lg border border-slate-200 bg-transparent px-2 py-1 text-sm" aria-label="New send time"
                      min={localInput(new Date(Date.now() + 5 * 60000))} value={newAt} onChange={(e) => setNewAt(e.target.value)} />
                    <button className="btn-primary !py-1 text-xs" disabled={!newAt || busy === i.id} onClick={() => reschedule(i)}>Save time</button>
                    <button className="btn-ghost !py-1 text-xs" onClick={() => setMoving(null)}>Never mind</button>
                  </div>
                ) : i.status !== 'sending' && (
                  <div className="mt-3 flex flex-wrap gap-1.5">
                    <button className="btn-secondary !py-1 text-xs" disabled={busy === i.id} onClick={() => sendNow(i)}>
                      <IconSend size={13} /> {failed ? 'Try again' : 'Send now'}
                    </button>
                    <button className="btn-secondary !py-1 text-xs" disabled={busy === i.id}
                      onClick={() => { setMoving(i.id); setNewAt(localInput(new Date(Math.max(Date.now() + 3600000, new Date(i.sendAt).getTime())))) }}>
                      <IconClock size={13} /> Change time
                    </button>
                    <button className="btn-ghost !py-1 text-xs" disabled={busy === i.id} onClick={() => edit(i)}>Edit</button>
                    <button className="btn-ghost !py-1 text-xs hover:!text-red-700" disabled={busy === i.id} onClick={() => cancel(i)}>Don't send</button>
                  </div>
                )}
              </article>
            )
          })}
        </div>
      </div>
    </section>
  )
}
