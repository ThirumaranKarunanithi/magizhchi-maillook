import { useEffect, useState } from 'react'
import { createPortal } from 'react-dom'
import { useNavigate } from 'react-router-dom'
import { get, post } from '../api.js'
import { useMail } from '../state.jsx'
import { formsStatus, notLinked, shortWhen, STATUS_LABEL, STATUS_TONE, FORMS_WEB } from '../forms.js'
import { IconCheck, IconForm, IconPlus, IconSparkle, IconX } from './Icons.jsx'

/** A dialog over everything, closed with Escape or a tap outside. */
export function Modal({ title, onClose, children, wide = false }) {
  useEffect(() => {
    const onKey = (e) => e.key === 'Escape' && onClose?.()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  return createPortal(
    <div className="modal-back fixed inset-0 z-50 flex items-end justify-center p-0 sm:items-center sm:p-4" onClick={onClose}>
      <div className={`card flex max-h-[90vh] w-full flex-col max-sm:rounded-b-none ${wide ? 'max-w-xl' : 'max-w-md'}`} role="dialog" aria-modal="true" aria-label={title}
        onClick={(e) => e.stopPropagation()}>
        <div className="flex items-center gap-2 border-b border-slate-200/70 px-4 py-3">
          <h2 className="min-w-0 flex-1 truncate text-base font-bold text-brand-ink">{title}</h2>
          <button className="icon-btn" onClick={onClose} aria-label="Close"><IconX size={16} /></button>
        </div>
        <div className="scroll-thin min-h-0 flex-1 overflow-y-auto p-4">{children}</div>
      </div>
    </div>,
    document.body,
  )
}

/** Not linked yet: Forms has no account for this person until they open it once. */
export function OpenFormsOnce() {
  return (
    <p className="text-sm text-slate-600">
      Open <a className="font-semibold text-peacock-700 underline" href={FORMS_WEB} target="_blank" rel="noreferrer">Magizhchi Forms</a> once
      with the same Magizhchi ID, and your forms show up here.
    </p>
  )
}

/** The answers of one response, label and text. */
export function AnswerList({ answers, max = 6 }) {
  const [all, setAll] = useState(false)
  const list = answers || []
  const shown = all ? list : list.slice(0, max)
  return (
    <div>
      <dl className="grid grid-cols-[minmax(6rem,auto)_1fr] gap-x-3 gap-y-1 text-sm">
        {shown.map((a, i) => (
          <div key={i} className="contents">
            <dt className="text-slate-500">{a.label}</dt>
            <dd className="min-w-0 whitespace-pre-wrap break-words text-brand-ink">{a.text}</dd>
          </div>
        ))}
      </dl>
      {list.length > max && (
        <button className="mt-1 text-xs font-semibold text-peacock-700 hover:underline" onClick={() => setAll((v) => !v)}>
          {all ? 'Show fewer' : `Show all ${list.length} answers`}
        </button>
      )}
    </div>
  )
}

export function StatusChip({ status }) {
  if (!status) return null
  return <span className={`chip !ring-1 ${STATUS_TONE[status] || ''}`}>{STATUS_LABEL[status] || status}</span>
}

// ── Compose: put a form or a poll in the mail ────────────────────────────────────────

/** "Form" in the Composer: pick one of your forms, or make a quick poll; it goes in the mail as a card. */
export function FormButton({ onPick, disabled }) {
  const [ok, setOk] = useState(false)
  const [open, setOpen] = useState(false)
  useEffect(() => { formsStatus().then((s) => setOk(!!s?.configured)) }, [])
  if (!ok) return null
  return (
    <>
      <button className="btn-ghost" onClick={() => setOpen(true)} disabled={disabled} title="Put a Magizhchi Forms form or a poll in this mail">
        <IconForm size={16} /> <span className="max-sm:hidden">Form</span>
      </button>
      {open && <FormPicker onClose={() => setOpen(false)} onPick={(f) => { onPick(f); setOpen(false) }} />}
    </>
  )
}

function FormPicker({ onClose, onPick }) {
  const { notify } = useMail()
  const [tab, setTab] = useState('form')
  const [list, setList] = useState(null)
  const [error, setError] = useState(null)
  const [q, setQ] = useState('')
  const [question, setQuestion] = useState('')
  const [choices, setChoices] = useState(['Yes', 'No'])
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    get('/api/forms/list').then((r) => setList(r.forms || [])).catch((e) => setError(e))
  }, [])

  async function makePoll(e) {
    e.preventDefault()
    const c = choices.map((x) => x.trim()).filter(Boolean)
    if (!question.trim() || c.length < 2) return notify('Write the question and at least two answers.', 'error')
    setBusy(true)
    try {
      const p = await post('/api/forms/poll', { question: question.trim(), choices: c })
      onPick({ kind: 'poll', formId: p.formId, slug: p.slug, question: p.question, choices: p.choices, title: p.question })
    } catch (err) {
      notify(err.message, 'error')
    } finally {
      setBusy(false)
    }
  }

  const shown = (list || []).filter((f) => !q || f.title.toLowerCase().includes(q.toLowerCase()))
  return (
    <Modal title="Put a form in this mail" onClose={onClose}>
      <div className="mb-3 flex rounded-lg bg-slate-100 p-0.5 text-sm font-semibold" role="tablist">
        {[['form', 'One of my forms'], ['poll', 'A quick poll']].map(([k, label]) => (
          <button key={k} role="tab" aria-selected={tab === k} onClick={() => setTab(k)}
            className={`flex-1 rounded-md px-3 py-1.5 ${tab === k ? 'bg-white text-brand-ink shadow-sm' : 'text-slate-500 hover:text-brand-ink'}`}>{label}</button>
        ))}
      </div>
      <p className="mb-3 text-xs text-slate-500">Everyone gets their own copy with their own link, so you can see who has filled it and remind only the rest.</p>
      {tab === 'form' ? (
        error ? (notLinked(error) ? <OpenFormsOnce /> : <p className="text-sm text-red-700">{error.message}</p>)
          : !list ? <p className="py-6 text-center text-sm text-slate-500"><span className="spinner" /> Loading your forms…</p>
            : !list.length ? <p className="text-sm text-slate-600">No published forms yet. Publish one in <a className="font-semibold text-peacock-700 underline" href={FORMS_WEB} target="_blank" rel="noreferrer">Magizhchi Forms</a>, or make a quick poll.</p>
              : (
                <>
                  {list.length > 6 && <input className="input mb-2 w-full" placeholder="Find a form" value={q} onChange={(e) => setQ(e.target.value)} aria-label="Find a form" />}
                  <ul className="space-y-1.5">
                    {shown.map((f) => (
                      <li key={f.id}>
                        <button className="flex w-full items-center gap-3 rounded-xl border border-slate-200 px-3 py-2.5 text-left hover:border-peacock-300 hover:bg-peacock-50/50"
                          onClick={() => onPick({ kind: 'form', formId: f.id, title: f.title, url: f.url, emailKey: f.emailKey })}>
                          <IconForm size={18} className="shrink-0 text-peacock-600" />
                          <span className="min-w-0 flex-1">
                            <span className="block truncate text-sm font-semibold text-brand-ink">{f.title}</span>
                            <span className="block truncate text-xs text-slate-500">
                              {f.emailKey ? 'Their email fills itself in' : 'No email question: they fill it in themselves'}
                              {f.closesAt ? ` · closes ${shortWhen(f.closesAt)}` : ''}
                            </span>
                          </span>
                        </button>
                      </li>
                    ))}
                  </ul>
                </>
              )
      ) : (
        <form onSubmit={makePoll} className="space-y-3">
          <label className="block">
            <span className="text-xs font-semibold text-slate-600">Question</span>
            <input className="input mt-1 w-full" value={question} onChange={(e) => setQuestion(e.target.value)} maxLength={200} placeholder="Which day suits you for the meeting?" autoFocus />
          </label>
          <div>
            <span className="text-xs font-semibold text-slate-600">Answers (one tap each, right in the mail)</span>
            <div className="mt-1 space-y-1.5">
              {choices.map((c, i) => (
                <div key={i} className="flex gap-1.5">
                  <input className="input min-w-0 flex-1" value={c} maxLength={60} aria-label={`Answer ${i + 1}`}
                    onChange={(e) => setChoices(choices.map((x, j) => (j === i ? e.target.value : x)))} />
                  {choices.length > 2 && (
                    <button type="button" className="icon-btn" onClick={() => setChoices(choices.filter((_, j) => j !== i))} aria-label={`Remove answer ${i + 1}`}><IconX size={14} /></button>
                  )}
                </div>
              ))}
            </div>
            {choices.length < 6 && (
              <button type="button" className="btn-ghost mt-1 !px-1 text-xs" onClick={() => setChoices([...choices, ''])}><IconPlus size={13} /> Add an answer</button>
            )}
          </div>
          <button className="btn-primary w-full" disabled={busy}>{busy && <span className="spinner" />} Put the poll in the mail</button>
        </form>
      )}
    </Modal>
  )
}

/** The form chip in the Composer. */
export function FormChip({ form, onRemove }) {
  return (
    <div className="flex flex-wrap items-center gap-2 border-t border-slate-100 px-4 py-2">
      <span className="chip !bg-peacock-50 !text-peacock-800">
        <IconForm size={12} /> {form.kind === 'poll' ? `Poll: ${form.question}` : `Form: ${form.title}`}
        <button type="button" onClick={onRemove} aria-label="Take the form out of this mail"><IconX size={11} /></button>
      </span>
      <span className="text-[11px] text-slate-500">Each person gets their own copy and link. See who has {form.kind === 'poll' ? 'answered' : 'filled it'} in Forms.</span>
    </div>
  )
}

// ── Reading a mail ──────────────────────────────────────────────────────────────────

/** "Filled: Admission form (2 Nov, approved)" for the sender, from your own forms. */
export function FormsFilled({ email }) {
  const navigate = useNavigate()
  const [rows, setRows] = useState(null)
  useEffect(() => {
    let live = true
    setRows(null)
    formsStatus().then((s) => {
      if (!s?.configured || s.linked === false || !email) return
      get(`/api/forms/person?who=${encodeURIComponent(email)}`).then((r) => live && setRows(r.filled || [])).catch(() => {})
    })
    return () => { live = false }
  }, [email])
  if (!rows?.length) return null
  const seen = new Set()
  const firsts = rows.filter((r) => (seen.has(r.formId) ? false : seen.add(r.formId)))
  return (
    <div className="flex flex-wrap items-center gap-x-2 gap-y-1 rounded-xl border border-slate-200 bg-white/70 px-3 py-2 text-xs text-slate-600">
      <IconForm size={14} className="text-peacock-600" />
      <span className="font-semibold text-brand-ink">Filled:</span>
      {firsts.slice(0, 3).map((r, i) => (
        <a key={r.responseId} className="hover:underline" href={`${FORMS_WEB}/forms/${r.formId}/responses`} target="_blank" rel="noreferrer"
          onClick={(e) => { if (r.status === 'pending_approval') { e.preventDefault(); navigate('/forms') } }}>
          {r.formTitle} <span className="text-slate-500">({shortWhen(r.at)}{r.status && r.status !== 'submitted' ? `, ${(STATUS_LABEL[r.status] || r.status).toLowerCase()}` : ''})</span>{i < Math.min(3, firsts.length) - 1 ? ',' : ''}
        </a>
      ))}
      {firsts.length > 3 && <span className="text-slate-500">and {firsts.length - 3} more</span>}
    </div>
  )
}

/** On a sent mail that carried a form or poll: who has filled it, the tally, and Remind. */
export function SentFormStrip({ message }) {
  const { notify } = useMail()
  const [d, setD] = useState(null)
  const [busy, setBusy] = useState(false)
  const [open, setOpen] = useState(false)
  useEffect(() => {
    let live = true
    setD(null)
    if (message.folder !== 'SENT') return undefined
    get(`/api/forms/for-message/${message.id}`).then((r) => live && r?.found && setD(r)).catch(() => {})
    return () => { live = false }
  }, [message.id, message.folder])
  if (!d) return null

  async function remind() {
    setBusy(true)
    try {
      const r = await post(`/api/forms/sent/${d.id}/remind`)
      notify(r.reminded ? `Reminded ${r.reminded} ${r.reminded === 1 ? 'person' : 'people'}` : 'Everyone has filled it', 'success')
      setD({ ...d, remindedAt: new Date().toISOString() })
    } catch (e) {
      notify(e.message, 'error')
    } finally {
      setBusy(false)
    }
  }
  return <SentDetail d={d} open={open} setOpen={setOpen} busy={busy} onRemind={remind} />
}

export function SentDetail({ d, open, setOpen, busy, onRemind }) {
  const left = d.asked - d.filled
  const pct = d.asked ? Math.round((d.filled / d.asked) * 100) : 0
  const poll = d.kind === 'poll'
  return (
    <div className="rounded-xl border border-peacock-200 bg-peacock-50/50 px-3 py-2.5 text-sm">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5">
        <IconForm size={16} className="text-peacock-700" />
        <span className="min-w-0 flex-1">
          <span className="font-semibold text-brand-ink num">{d.filled} of {d.asked}</span> {poll ? 'answered' : 'filled'} · <span className="text-slate-600">{d.title}</span>
        </span>
        {left > 0 && (
          <button className="btn-secondary !px-2 !py-1 text-xs" onClick={onRemind} disabled={busy} title={`A short reminder, each with their own link, to the ${left} who haven't yet`}>
            {busy && <span className="spinner" />} Remind the {left}
          </button>
        )}
        <button className="text-xs font-semibold text-peacock-700 hover:underline" onClick={() => setOpen(!open)} aria-expanded={open}>{open ? 'Hide' : 'Who'}</button>
      </div>
      <div className="mt-2 h-1.5 rounded-full bg-white" aria-hidden="true"><div className="h-1.5 rounded-full bg-peacock-600" style={{ width: `${pct}%` }} /></div>
      {poll && d.tally?.choices && (
        <ul className="mt-2.5 space-y-1">
          {d.tally.choices.map((c) => {
            const p = d.tally.total ? Math.round((c.count / d.tally.total) * 100) : 0
            return (
              <li key={c.choice} className="relative overflow-hidden rounded-lg bg-white px-2.5 py-1.5" title={c.who?.join(', ')}>
                <span className="absolute inset-y-0 left-0 bg-peacock-100" style={{ width: `${p}%` }} aria-hidden="true" />
                <span className="relative flex justify-between gap-2"><span>{c.choice}</span><span className="font-semibold num">{c.count}</span></span>
              </li>
            )
          })}
        </ul>
      )}
      {open && d.people && (
        <ul className="mt-2.5 grid gap-x-4 gap-y-1 text-xs sm:grid-cols-2">
          {d.people.map((p) => (
            <li key={p.email} className="flex items-center gap-1.5">
              {p.filled ? <IconCheck size={13} className="text-emerald-700" /> : <span className="inline-block h-2 w-2 rounded-full border border-slate-400" aria-hidden="true" />}
              <span className={`min-w-0 truncate ${p.filled ? 'text-brand-ink' : 'text-slate-500'}`}>{p.email}</span>
              {p.filled && <span className="text-slate-500">{shortWhen(p.at)}</span>}
              <span className="sr-only">{p.filled ? 'filled' : 'not yet'}</span>
            </li>
          ))}
        </ul>
      )}
      {d.remindedAt && <p className="mt-1.5 text-[11px] text-slate-500">Reminded {shortWhen(d.remindedAt)}</p>}
    </div>
  )
}

/** "Forms" on a mail: make a form from it and send it to everyone on the thread, or copy it into a form as a response. */
export function FormsMenu({ message }) {
  const { openComposer, notify } = useMail()
  const [ok, setOk] = useState(false)
  const [open, setOpen] = useState(null) // 'menu' | 'build' | 'add'
  useEffect(() => { formsStatus().then((s) => setOk(!!s?.configured && s.linked !== false)) }, [])
  if (!ok) return null
  return (
    <>
      <button className="btn-ghost" onClick={() => setOpen('menu')} title="Magizhchi Forms: make a form from this mail, or add it to a form">
        <IconForm size={17} /><span className="hidden xl:inline">Forms</span>
      </button>
      {open === 'menu' && (
        <Modal title="Magizhchi Forms" onClose={() => setOpen(null)}>
          <div className="space-y-2">
            <button className="flex w-full items-start gap-3 rounded-xl border border-slate-200 px-3 py-3 text-left hover:border-peacock-300 hover:bg-peacock-50/50" onClick={() => setOpen('build')}>
              <IconSparkle size={18} className="mt-0.5 shrink-0 text-peacock-600" />
              <span><span className="block text-sm font-semibold text-brand-ink">Collect info from everyone</span>
                <span className="block text-xs text-slate-500">Forms’ AI makes a form from what this mail asks for, then it goes to everyone on the thread, with tracking.</span></span>
            </button>
            <button className="flex w-full items-start gap-3 rounded-xl border border-slate-200 px-3 py-3 text-left hover:border-peacock-300 hover:bg-peacock-50/50" onClick={() => setOpen('add')}>
              <IconPlus size={18} className="mt-0.5 shrink-0 text-peacock-600" />
              <span><span className="block text-sm font-semibold text-brand-ink">Add to a form</span>
                <span className="block text-xs text-slate-500">This mail (and its PDF or photo, read by Magizhchi Data) becomes a response. The form’s connectors take it on to CRM or Books.</span></span>
            </button>
          </div>
        </Modal>
      )}
      {open === 'build' && <BuildForm message={message} onClose={() => setOpen(null)} onBuilt={(r) => {
        setOpen(null)
        const f = r.form
        openComposer({
          mode: 'new', message, to: r.people,
          subject: r.subject ? `Please fill in: ${f.title}` : f.title,
          draftBody: `Hi all,\n\nCould you fill in this short form? It only takes a minute.\n`,
          form: { kind: 'form', formId: f.id, title: f.title, url: f.url, emailKey: f.emailKey },
        })
        notify(`Made “${f.title}”. Check it, then send.`, 'success')
      }} />}
      {open === 'add' && <AddToForm message={message} onClose={() => setOpen(null)} />}
    </>
  )
}

function BuildForm({ message, onClose, onBuilt }) {
  const { notify } = useMail()
  const [prompt, setPrompt] = useState('')
  const [busy, setBusy] = useState(false)
  async function go(e) {
    e.preventDefault()
    setBusy(true)
    try {
      onBuilt(await post('/api/forms/build', { messageId: message.id, prompt: prompt.trim() || null }))
    } catch (err) {
      notify(err.message, 'error')
      setBusy(false)
    }
  }
  return (
    <Modal title="Collect info from everyone" onClose={onClose}>
      <form onSubmit={go} className="space-y-3">
        <p className="text-sm text-slate-600">Forms’ AI reads this mail and makes a form for what it asks. You can say more:</p>
        <textarea className="input min-h-[5rem] w-full" value={prompt} onChange={(e) => setPrompt(e.target.value)} maxLength={1500}
          placeholder="e.g. Name, which session they’ll attend, food preference, and a phone number" />
        <p className="text-xs text-slate-500">It’s published in your Magizhchi Forms, where you can change it any time.</p>
        <button className="btn-primary w-full" disabled={busy}>{busy && <span className="spinner" />} {busy ? 'Making the form…' : 'Make the form'}</button>
      </form>
    </Modal>
  )
}

function AddToForm({ message, onClose }) {
  const { notify } = useMail()
  const [list, setList] = useState(null)
  const [error, setError] = useState(null)
  const [form, setForm] = useState(null)
  const [read, setRead] = useState(null)
  const [values, setValues] = useState({})
  const [busy, setBusy] = useState(false)
  const [saved, setSaved] = useState(null)

  useEffect(() => { get('/api/forms/list').then((r) => setList(r.forms || [])).catch(setError) }, [])

  async function pick(f) {
    setForm(f)
    setBusy(true)
    try {
      const r = await post('/api/forms/read', { messageId: message.id, formId: f.id })
      setRead(r)
      setValues(Object.fromEntries(r.fields.map((x) => [x.key, x.value || ''])))
    } catch (err) {
      notify(err.message, 'error')
      setForm(null)
    } finally {
      setBusy(false)
    }
  }

  async function save(e) {
    e.preventDefault()
    setBusy(true)
    try {
      const answers = Object.fromEntries(Object.entries(values).filter(([, v]) => String(v).trim() !== ''))
      const r = await post('/api/forms/response', { messageId: message.id, formId: form.id, answers })
      setSaved(r)
    } catch (err) {
      notify(err.message, 'error')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal title={saved ? 'Saved to the form' : form ? `Add to “${form.title}”` : 'Add this mail to a form'} onClose={onClose} wide>
      {saved ? (
        <div className="space-y-3 text-sm">
          <p className="flex items-center gap-2 font-semibold text-emerald-800"><IconCheck size={16} /> Saved as a response{saved.autoId ? ` (${saved.autoId})` : ''}.</p>
          <p className="text-slate-600">The form’s own steps take it from here: approvals, mails, and any CRM or Books connector it has.</p>
          <button className="btn-secondary" onClick={onClose}>Done</button>
        </div>
      ) : !form ? (
        error ? (notLinked(error) ? <OpenFormsOnce /> : <p className="text-sm text-red-700">{error.message}</p>)
          : !list ? <p className="py-6 text-center text-sm text-slate-500"><span className="spinner" /> Loading your forms…</p>
            : (
              <ul className="space-y-1.5">
                {list.map((f) => (
                  <li key={f.id}>
                    <button className="flex w-full items-center gap-3 rounded-xl border border-slate-200 px-3 py-2.5 text-left hover:border-peacock-300 hover:bg-peacock-50/50" onClick={() => pick(f)}>
                      <IconForm size={18} className="shrink-0 text-peacock-600" />
                      <span className="truncate text-sm font-semibold text-brand-ink">{f.title}</span>
                    </button>
                  </li>
                ))}
              </ul>
            )
      ) : !read ? (
        <p className="py-8 text-center text-sm text-slate-500"><span className="spinner" /> Reading the mail{message.attachments?.length ? ' and its attachment' : ''}…</p>
      ) : (
        <form onSubmit={save} className="space-y-3">
          <p className="text-xs text-slate-500">Read by {read.readBy}. Check each answer before saving; leave blank what the mail doesn’t say.</p>
          {read.fields.map((x) => (
            <label key={x.key} className="block">
              <span className="text-xs font-semibold text-slate-600">{x.label}</span>
              {x.type === 'textarea'
                ? <textarea className="input mt-1 min-h-[4rem] w-full" value={values[x.key]} onChange={(e) => setValues({ ...values, [x.key]: e.target.value })} />
                : <input className="input mt-1 w-full" value={values[x.key]} onChange={(e) => setValues({ ...values, [x.key]: e.target.value })}
                  type={x.type === 'date' ? 'date' : x.type === 'number' ? 'number' : 'text'} />}
            </label>
          ))}
          <div className="flex gap-2">
            <button type="button" className="btn-secondary" onClick={() => { setForm(null); setRead(null) }}>Another form</button>
            <button className="btn-primary flex-1" disabled={busy}>{busy && <span className="spinner" />} Save the response</button>
          </div>
        </form>
      )}
    </Modal>
  )
}
