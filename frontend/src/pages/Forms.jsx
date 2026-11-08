import { useCallback, useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { get, post, put } from '../api.js'
import { useMail } from '../state.jsx'
import { formsStatus, notLinked, shortWhen, FORMS_WEB } from '../forms.js'
import { AnswerList, OpenFormsOnce, SentDetail, StatusChip } from '../components/FormsBits.jsx'
import { IconCheck, IconForm, IconMenu, IconReply, IconX } from '../components/Icons.jsx'

const TABS = [
  ['waiting', 'Approvals'],
  ['responses', 'New responses'],
  ['sent', 'Sent forms'],
  ['settings', 'Settings'],
]

/** Magizhchi Forms in the inbox: approvals waiting on you, new responses, the forms you mailed, and settings. */
export default function Forms() {
  const { setMenuOpen } = useMail()
  const [params, setParams] = useSearchParams()
  const [status, setStatus] = useState(null)
  const [over, setOver] = useState(null)
  const [error, setError] = useState(null)
  const tab = TABS.some(([k]) => k === params.get('tab')) ? params.get('tab') : params.get('approval') ? 'waiting' : null

  const load = useCallback(() => {
    setError(null)
    return get('/api/forms/overview').then(setOver).catch(setError)
  }, [])

  useEffect(() => {
    formsStatus(true).then((s) => {
      setStatus(s)
      if (s?.configured) load()
    })
  }, [load])

  const waiting = over?.approvals || []
  const fresh = over?.responses || []
  const current = tab || (waiting.length ? 'waiting' : 'responses')
  const setTab = (k) => setParams({ tab: k }, { replace: true })

  return (
    <section className="slab rise-3d d1 flex min-h-0 flex-1 flex-col overflow-hidden max-lg:rounded-none">
      <header className="flex items-center gap-2 border-b border-slate-200/70 px-3 py-2.5 sm:px-4">
        <button className="icon-btn lg:hidden" onClick={() => setMenuOpen(true)} aria-label="Open menu"><IconMenu /></button>
        <IconForm size={20} className="hidden text-peacock-600 sm:block" />
        <div className="min-w-0 flex-1">
          <h1 className="truncate text-base font-bold leading-tight">Forms</h1>
          <p className="truncate text-xs text-slate-500">From your Magizhchi Forms</p>
        </div>
        <a className="btn-ghost text-sm" href={FORMS_WEB} target="_blank" rel="noreferrer">Open Forms</a>
      </header>

      {status?.configured && !notLinked(error) && (
        <div className="scroll-thin flex shrink-0 gap-1 overflow-x-auto border-b border-slate-200/70 px-3 py-1.5 text-sm font-semibold sm:px-4" role="tablist" aria-label="Forms view">
          {TABS.map(([k, label]) => {
            const n = k === 'waiting' ? waiting.length : k === 'responses' ? fresh.length : 0
            return (
              <button key={k} role="tab" aria-selected={current === k} onClick={() => setTab(k)}
                className={`flex shrink-0 items-center gap-1.5 rounded-lg px-3 py-1.5 ${current === k ? 'bg-peacock-50 text-peacock-800' : 'text-slate-500 hover:text-brand-ink'}`}>
                {label}{n > 0 && <span className={`rounded-full px-1.5 text-xs num ${k === 'waiting' ? 'bg-amber-100 text-amber-800' : 'bg-slate-100 text-slate-700'}`}>{n}</span>}
              </button>
            )
          })}
        </div>
      )}

      <div className="scroll-thin min-h-0 flex-1 overflow-y-auto px-3 py-4 max-md:pb-24 sm:px-5">
        <div className="mx-auto max-w-3xl space-y-3">
          {!status ? <p className="py-10 text-center text-sm text-slate-500"><span className="spinner" /> Loading…</p>
            : !status.configured ? <p className="card p-4 text-sm text-slate-600">Magizhchi Forms isn’t connected on this server yet.</p>
              : notLinked(error) ? <div className="card p-4"><OpenFormsOnce /></div>
                : current === 'settings' ? <Settings status={status} onSaved={setStatus} />
                  : current === 'sent' ? <SentForms />
                    : error ? <p className="rounded-xl bg-red-50 px-3 py-2 text-sm text-red-800">{error.message} <button className="font-semibold underline" onClick={load}>Try again</button></p>
                      : !over ? <p className="py-10 text-center text-sm text-slate-500"><span className="spinner" /> Loading your forms…</p>
                        : current === 'waiting' ? <Approvals list={waiting} focus={params.get('approval')} onDone={(id) => setOver({ ...over, approvals: waiting.filter((a) => a.responseId !== id) })} />
                          : <Responses list={fresh} mode={status.responses} />}
        </div>
      </div>
    </section>
  )
}

// ── approvals ─────────────────────────────────────────────────────

function Approvals({ list, focus, onDone }) {
  if (!list.length) {
    return (
      <div className="card flex items-center gap-3 p-5 text-sm text-slate-600">
        <IconCheck size={20} className="text-emerald-700" /> Nothing is waiting for your approval.
      </div>
    )
  }
  return list.map((a) => <ApprovalCard key={a.responseId} a={a} focus={focus === a.responseId} onDone={onDone} />)
}

function ApprovalCard({ a, focus, onDone }) {
  const { notify, openComposer } = useMail()
  const [busy, setBusy] = useState(null)
  const [commenting, setCommenting] = useState(false)
  const [comment, setComment] = useState('')

  async function decide(decision) {
    if (decision === 'comment' && !comment.trim()) return
    setBusy(decision)
    try {
      await post('/api/forms/decision', { responseId: a.responseId, decision, comment: comment.trim() || null })
      if (decision === 'comment') {
        notify('Comment saved', 'success')
        setCommenting(false)
      } else {
        notify(decision === 'approve' ? `Approved ${a.who || 'the response'}` : `Rejected ${a.who || 'the response'}`, 'success')
        onDone(a.responseId)
      }
    } catch (e) {
      notify(e.message, 'error')
      if (e.status === 409) onDone(a.responseId)
    } finally {
      setBusy(null)
    }
  }

  return (
    <article className={`card p-4 ${focus ? 'ring-2 ring-amber-300' : ''}`} aria-label={`${a.formTitle}: ${a.who || 'response'}`}>
      <div className="flex flex-wrap items-baseline gap-x-2 gap-y-1">
        <h2 className="min-w-0 flex-1 text-sm font-bold text-brand-ink">{a.who || a.whoEmail || 'Someone'} <span className="font-normal text-slate-500">· {a.formTitle}{a.autoId ? ` · ${a.autoId}` : ''}</span></h2>
        <span className="text-xs text-slate-500">{shortWhen(a.submittedAt)}</span>
      </div>
      {a.comment && <p className="mt-1 text-xs text-slate-500">Comment: {a.comment}</p>}
      <div className="mt-3"><AnswerList answers={a.answers} /></div>
      {commenting && (
        <textarea className="input mt-3 min-h-[4rem] w-full" value={comment} onChange={(e) => setComment(e.target.value)} placeholder="A note for the others who approve this form" autoFocus maxLength={2000} />
      )}
      <div className="mt-3 flex flex-wrap items-center gap-2">
        <button className="btn-primary !px-3 !py-1.5 text-sm" onClick={() => decide('approve')} disabled={!!busy}>
          {busy === 'approve' ? <span className="spinner" /> : <IconCheck size={15} />} Approve
        </button>
        <button className="btn-secondary !px-3 !py-1.5 text-sm" onClick={() => decide('reject')} disabled={!!busy}>
          {busy === 'reject' ? <span className="spinner" /> : <IconX size={15} />} Reject
        </button>
        {commenting
          ? <button className="btn-secondary !px-3 !py-1.5 text-sm" onClick={() => decide('comment')} disabled={!!busy || !comment.trim()}>{busy === 'comment' && <span className="spinner" />} Save comment</button>
          : <button className="btn-ghost text-sm" onClick={() => setCommenting(true)}>Comment</button>}
        {a.whoEmail && (
          <button className="btn-ghost text-sm" onClick={() => openComposer({ mode: 'new', to: [a.whoEmail], subject: `Your ${a.formTitle}` })} title={`Write to ${a.whoEmail}`}>
            <IconReply size={15} /> Reply
          </button>
        )}
        <a className="ml-auto text-xs font-semibold text-peacock-700 hover:underline" href={a.url} target="_blank" rel="noreferrer">Open in Forms</a>
      </div>
    </article>
  )
}

// ── new responses ─────────────────────────────────────────────────

function Responses({ list, mode }) {
  const { openComposer } = useMail()
  const [open, setOpen] = useState(null)
  if (mode === 'off') return <p className="card p-4 text-sm text-slate-600">New responses are turned off. Turn them back on in Settings.</p>
  if (!list.length) return <p className="card p-4 text-sm text-slate-600">No new responses in the last week.</p>
  return (
    <ul className="card divide-y divide-slate-100 overflow-hidden">
      {list.map((r) => {
        const isOpen = open === r.responseId
        const first = (r.answers || []).find((x) => x.text !== r.who && x.text !== r.whoEmail)
        return (
          <li key={r.responseId}>
            <button className="flex w-full items-start gap-3 px-4 py-3 text-left hover:bg-slate-50" onClick={() => setOpen(isOpen ? null : r.responseId)} aria-expanded={isOpen}>
              <span className="min-w-0 flex-1">
                <span className="flex items-baseline gap-2">
                  <span className="truncate text-sm font-semibold text-brand-ink">{r.who || r.whoEmail || 'Someone'}</span>
                  <span className="truncate text-xs text-slate-500">{r.formTitle}</span>
                </span>
                {!isOpen && first && <span className="block truncate text-xs text-slate-500">{first.label}: {first.text}</span>}
              </span>
              <span className="flex shrink-0 flex-col items-end gap-1">
                <span className="text-xs text-slate-500">{shortWhen(r.submittedAt)}</span>
                {r.status !== 'submitted' && <StatusChip status={r.status} />}
              </span>
            </button>
            {isOpen && (
              <div className="px-4 pb-4">
                <AnswerList answers={r.answers} max={14} />
                <div className="mt-3 flex items-center gap-2">
                  {r.whoEmail && (
                    <button className="btn-secondary !px-3 !py-1.5 text-sm" onClick={() => openComposer({ mode: 'new', to: [r.whoEmail], subject: `Re: ${r.formTitle}` })}>
                      <IconReply size={15} /> Reply to {r.who || r.whoEmail}
                    </button>
                  )}
                  <a className="ml-auto text-xs font-semibold text-peacock-700 hover:underline" href={r.url} target="_blank" rel="noreferrer">Open in Forms</a>
                </div>
              </div>
            )}
          </li>
        )
      })}
    </ul>
  )
}

// ── sent forms ────────────────────────────────────────────────────

function SentForms() {
  const [list, setList] = useState(null)
  const [error, setError] = useState('')
  useEffect(() => { get('/api/forms/sent').then(setList).catch((e) => setError(e.message)) }, [])
  if (error) return <p className="rounded-xl bg-red-50 px-3 py-2 text-sm text-red-800">{error}</p>
  if (!list) return <p className="py-10 text-center text-sm text-slate-500"><span className="spinner" /> Loading…</p>
  if (!list.length) {
    return <p className="card p-4 text-sm text-slate-600">Forms and polls you send from Compose show here, with who has filled them. Press <b>Form</b> in a new mail to start.</p>
  }
  return list.map((s) => <SentRow key={s.id} s={s} />)
}

function SentRow({ s }) {
  const { notify } = useMail()
  const [d, setD] = useState(s)
  const [open, setOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  const [live, setLive] = useState(false)

  useEffect(() => {
    if (!open || live) return
    get(`/api/forms/sent/${s.id}`).then((x) => { setD(x); setLive(true) }).catch((e) => notify(e.message, 'error'))
  }, [open, live, s.id, notify])

  async function remind() {
    setBusy(true)
    try {
      const r = await post(`/api/forms/sent/${s.id}/remind`)
      notify(r.reminded ? `Reminded ${r.reminded} ${r.reminded === 1 ? 'person' : 'people'}` : 'Everyone has filled it', 'success')
      setD({ ...d, remindedAt: new Date().toISOString() })
    } catch (e) {
      notify(e.message, 'error')
    } finally {
      setBusy(false)
    }
  }
  return (
    <div>
      <p className="mb-1 px-1 text-xs text-slate-500">{d.subject || d.title} · sent {shortWhen(d.sentAt)}</p>
      <SentDetail d={d} open={open} setOpen={setOpen} busy={busy} onRemind={remind} />
    </div>
  )
}

// ── settings ──────────────────────────────────────────────────────

function Settings({ status, onSaved }) {
  const { notify } = useMail()
  const [busy, setBusy] = useState(false)
  const [mailbox, setMailbox] = useState(status.sendAccountId || status.accounts?.[0]?.id || '')

  async function save(body) {
    setBusy(true)
    try {
      const s = await put('/api/forms/settings', body)
      onSaved(s)
      formsStatus(true)
      notify('Saved', 'success')
    } catch (e) {
      notify(e.message, 'error')
    } finally {
      setBusy(false)
    }
  }

  const on = status.sendAccountId != null
  return (
    <>
      <section className="card p-4">
        <h2 className="text-sm font-bold text-brand-ink">New responses</h2>
        <div className="mt-3 space-y-2" role="radiogroup" aria-label="New responses">
          {[
            ['lane', 'Wait here quietly', 'They gather on this page; no alerts.'],
            ['daily', 'One alert in the evening', 'A single phone alert around 6 pm with the day’s count.'],
            ['off', 'Off', 'Only approvals show here.'],
          ].map(([k, label, note]) => (
            <label key={k} className={`flex cursor-pointer items-start gap-3 rounded-xl border px-3 py-2.5 ${status.responses === k ? 'border-peacock-400 bg-peacock-50/60' : 'border-slate-200'}`}>
              <input type="radio" name="responses" className="mt-1" checked={status.responses === k} disabled={busy} onChange={() => save({ responses: k })} />
              <span><span className="block text-sm font-semibold text-brand-ink">{label}</span><span className="block text-xs text-slate-500">{note}</span></span>
            </label>
          ))}
        </div>
        <p className="mt-3 text-xs text-slate-500">Approvals always ring your phone, once each, when alerts are on.</p>
      </section>

      <section className="card p-4">
        <h2 className="text-sm font-bold text-brand-ink">Send Forms’ mail from my mailbox</h2>
        <p className="mt-1 text-sm text-slate-600">
          Confirmation and follow-up mails your forms send go from your own address instead of forms@magizhchi.software, so replies come back to you and a copy sits in your Sent folder.
        </p>
        <ul className="mt-2 list-disc space-y-0.5 pl-5 text-xs text-slate-500">
          <li>Only mail your own forms send, one person at a time, at most 300 a day.</li>
          <li>Each one says at the bottom that it was sent through Magizhchi Forms.</li>
          <li>Turn it off here any time; Forms then sends from its own address again.</li>
        </ul>
        {status.accounts?.length ? (
          <div className="mt-3 flex flex-wrap items-center gap-2">
            <select className="input !w-auto" value={mailbox} onChange={(e) => setMailbox(Number(e.target.value))} disabled={busy} aria-label="Mailbox to send from">
              {status.accounts.map((a) => <option key={a.id} value={a.id}>{a.email}</option>)}
            </select>
            {on && Number(mailbox) === Number(status.sendAccountId)
              ? <button className="btn-secondary !px-3 !py-1.5 text-sm" onClick={() => save({ sendFromMailbox: false })} disabled={busy}>Turn off</button>
              : <button className="btn-primary !px-3 !py-1.5 text-sm" onClick={() => save({ sendAccountId: Number(mailbox) })} disabled={busy}>{on ? 'Use this mailbox' : 'Yes, send from this mailbox'}</button>}
            {on && <span className="inline-flex items-center gap-1 text-xs font-semibold text-emerald-700"><IconCheck size={13} /> On</span>}
          </div>
        ) : <p className="mt-2 text-sm text-slate-500">Add a mailbox first.</p>}
      </section>
    </>
  )
}
