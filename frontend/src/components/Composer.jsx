import { useEffect, useMemo, useRef, useState } from 'react'
import { postForm } from '../api.js'
import { useMail } from '../state.jsx'
import { bodyAsText, EMAIL_RE, formatSize, fullDate, splitAddresses } from '../util.js'
import { IconPaperclip, IconSend, IconX } from './Icons.jsx'

function uniq(list, exclude = new Set()) {
  const seen = new Set()
  const out = []
  for (const a of list) {
    const k = String(a || '').trim().toLowerCase()
    if (!k || seen.has(k) || exclude.has(k)) continue
    seen.add(k)
    out.push(String(a).trim())
  }
  return out
}

function prefixed(subject, prefix, re) {
  const s = subject || ''
  return re.test(s) ? s : `${prefix} ${s}`.trim()
}

function quote(m) {
  const who = m.fromName ? `${m.fromName} <${m.fromEmail}>` : m.fromEmail
  const text = bodyAsText(m)
  return `On ${fullDate(m.receivedAt)}, ${who} wrote:\n${text.split('\n').map((l) => `> ${l}`).join('\n')}`
}

/** Works out From/To/Cc/Subject/body for new, reply, reply all and forward. */
function initialDraft({ mode, message: m, draftBody }, accounts, selectedAccountId) {
  const own = new Set(accounts.map((a) => a.email.toLowerCase()))
  const fallbackFrom = selectedAccountId ?? accounts[0]?.id ?? ''
  if (!m || mode === 'new') return { accountId: fallbackFrom, to: [], cc: [], subject: '', text: '', replyToMessageId: null }

  const accountId = accounts.some((a) => a.id === m.accountId) ? m.accountId : fallbackFrom
  const fromMe = own.has((m.fromEmail || '').toLowerCase())
  if (mode === 'forward') {
    const header = [
      '---------- Forwarded message ----------',
      `From: ${m.fromName ? `${m.fromName} <${m.fromEmail}>` : m.fromEmail}`,
      `Date: ${fullDate(m.receivedAt)}`,
      `Subject: ${m.subject || ''}`,
      `To: ${(m.to || []).join(', ')}`,
      ...(m.cc?.length ? [`Cc: ${m.cc.join(', ')}`] : []),
    ].join('\n')
    return {
      accountId, to: [], cc: [],
      subject: prefixed(m.subject, 'Fwd:', /^\s*(fwd?|fw)\s*:/i),
      text: `\n\n${header}\n\n${bodyAsText(m)}`,
      replyToMessageId: null,
    }
  }
  let to = fromMe ? m.to || [] : [m.fromEmail]
  let cc = []
  if (mode === 'replyAll') {
    to = fromMe ? m.to || [] : [m.fromEmail, ...(m.to || [])]
    cc = m.cc || []
  }
  to = uniq(to, fromMe ? new Set() : own)
  if (!to.length && m.fromEmail) to = [m.fromEmail]
  cc = uniq(cc, new Set([...own, ...to.map((x) => x.toLowerCase())]))
  return {
    accountId, to, cc,
    subject: prefixed(m.subject, 'Re:', /^\s*re\s*:/i),
    text: `${draftBody ? `${draftBody}\n` : ''}\n\n${quote(m)}`,
    replyToMessageId: m.id,
  }
}

/** To/Cc/Bcc: chips plus the half-typed text, both owned by the parent so Send never loses a typed address. */
function RecipientField({ label, chips, setChips, text, setText, autoFocus, children }) {
  function commit() {
    const parts = splitAddresses(text)
    if (parts.length) setChips(uniq([...chips, ...parts]))
    setText('')
  }
  return (
    <div className="flex items-start gap-2 border-b border-slate-200/80 px-4 py-1.5">
      <span className="w-10 shrink-0 pt-1.5 text-xs font-semibold text-slate-500">{label}</span>
      <div className="flex min-w-0 flex-1 flex-wrap items-center gap-1">
        {chips.map((c) => {
          const ok = EMAIL_RE.test(c)
          return (
            <span key={c} className={`chip ${ok ? '' : '!bg-red-50 !text-red-700'}`} title={ok ? c : 'This does not look like an email address'}>
              {c}
              <button type="button" onClick={() => setChips(chips.filter((x) => x !== c))} aria-label={`Remove ${c}`} className="opacity-60 hover:opacity-100">
                <IconX size={11} />
              </button>
            </span>
          )
        })}
        <input
          className="min-w-[160px] flex-1 bg-transparent py-1 text-sm outline-none"
          value={text}
          autoFocus={autoFocus}
          onChange={(e) => {
            const v = e.target.value
            if (/[,;\n]/.test(v)) {
              const parts = splitAddresses(v)
              const endsWithSep = /[,;\n]\s*$/.test(v)
              const done = endsWithSep ? parts : parts.slice(0, -1)
              setChips(uniq([...chips, ...done]))
              setText(endsWithSep ? '' : parts[parts.length - 1] || '')
            } else setText(v)
          }}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && text.trim()) { e.preventDefault(); commit() }
            if (e.key === 'Backspace' && !text && chips.length) setChips(chips.slice(0, -1))
          }}
          onBlur={commit}
          aria-label={label}
          type="text"
          autoComplete="email"
        />
      </div>
      {children}
    </div>
  )
}

export default function Composer() {
  const { composer, closeComposer, accounts, accountId: selectedAccountId, notify, bumpMail, refreshCounts } = useMail()
  const init = useMemo(() => initialDraft(composer, accounts, selectedAccountId), []) // eslint-disable-line react-hooks/exhaustive-deps

  const [fromId, setFromId] = useState(init.accountId)
  const [to, setTo] = useState(init.to)
  const [cc, setCc] = useState(init.cc)
  const [bcc, setBcc] = useState([])
  const [toText, setToText] = useState('')
  const [ccText, setCcText] = useState('')
  const [bccText, setBccText] = useState('')
  const [showCc, setShowCc] = useState(init.cc.length > 0)
  const [subject, setSubject] = useState(init.subject)
  const [text, setText] = useState(init.text)
  const [files, setFiles] = useState([])
  const [sending, setSending] = useState(false)
  const [error, setError] = useState('')
  const fileInput = useRef(null)
  const bodyRef = useRef(null)

  // accounts may arrive after the composer opened
  useEffect(() => {
    if ((fromId === '' || fromId == null) && accounts.length) setFromId(selectedAccountId ?? accounts[0].id)
  }, [accounts, fromId, selectedAccountId])

  // replies start with the cursor above the quote
  useEffect(() => {
    if (composer.mode !== 'new' && bodyRef.current) {
      bodyRef.current.focus()
      bodyRef.current.setSelectionRange(0, 0)
      bodyRef.current.scrollTop = 0
    }
  }, [composer.mode])

  const dirty = to.length || cc.length || bcc.length || toText || subject !== init.subject || text !== init.text || files.length

  function close() {
    if (sending) return
    if (dirty && !window.confirm('Discard this draft?')) return
    closeComposer()
  }

  useEffect(() => {
    const onKey = (e) => e.key === 'Escape' && close()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  })

  async function send() {
    setError('')
    const all = (chips, typed) => uniq([...chips, ...splitAddresses(typed)])
    const toList = all(to, toText)
    const ccList = all(cc, ccText)
    const bccList = all(bcc, bccText)
    const bad = [...toList, ...ccList, ...bccList].filter((a) => !EMAIL_RE.test(a))
    if (!fromId) return setError('Add a mailbox under Manage accounts before sending.')
    if (!toList.length && !ccList.length && !bccList.length) return setError('Who is this going to? Add at least one recipient.')
    if (bad.length) return setError(`These don't look like email addresses: ${bad.join(', ')}`)
    if (!subject.trim() && !window.confirm('Send without a subject?')) return

    const payload = {
      accountId: Number(fromId),
      to: toList, cc: ccList, bcc: bccList,
      subject, text,
      ...(init.replyToMessageId ? { replyToMessageId: init.replyToMessageId } : {}),
    }
    const form = new FormData()
    form.append('payload', new Blob([JSON.stringify(payload)], { type: 'application/json' }))
    for (const f of files) form.append('files', f, f.name)

    setSending(true)
    try {
      await postForm('/api/send', form)
      notify('Sent ✓', 'success')
      closeComposer()
      bumpMail()
      refreshCounts()
    } catch (e) {
      setError(e.message)
      setSending(false)
    }
  }

  const title = { new: 'New message', reply: 'Reply', replyAll: 'Reply all', forward: 'Forward' }[composer.mode] || 'New message'
  const totalSize = files.reduce((n, f) => n + f.size, 0)

  return (
    <div className="modal-back fixed inset-0 z-50 flex items-stretch justify-end sm:items-center sm:justify-center sm:p-4" onMouseDown={(e) => e.target === e.currentTarget && close()}>
      <div className="card flex h-full w-full flex-col overflow-hidden !rounded-none sm:h-[min(760px,92vh)] sm:max-w-2xl sm:!rounded-2xl"
        role="dialog" aria-modal="true" aria-label={title}
        onKeyDown={(e) => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); send() } }}>
        <div className="flex items-center gap-2 border-b border-slate-200 bg-slate-50 px-4 py-2.5">
          <h2 className="flex-1 text-sm font-bold">{title}</h2>
          <button className="icon-btn" onClick={close} aria-label="Close"><IconX size={17} /></button>
        </div>

        <div className="flex items-center gap-2 border-b border-slate-200/80 px-4 py-1.5">
          <span className="w-10 shrink-0 text-xs font-semibold text-slate-500">From</span>
          <select className="min-w-0 flex-1 bg-transparent py-1 text-sm outline-none" value={fromId ?? ''} onChange={(e) => setFromId(Number(e.target.value))} aria-label="From">
            {!accounts.length && <option value="">No accounts yet</option>}
            {accounts.map((a) => (
              <option key={a.id} value={a.id}>{a.displayName && a.displayName !== a.email ? `${a.displayName} <${a.email}>` : a.email}</option>
            ))}
          </select>
          {(() => {
            const a = accounts.find((x) => x.id === Number(fromId))
            return a ? <span className="h-2.5 w-2.5 rounded-full" style={{ background: a.color }} /> : null
          })()}
        </div>
        <RecipientField label="To" chips={to} setChips={setTo} text={toText} setText={setToText} autoFocus={composer.mode === 'new' || composer.mode === 'forward'}>
          {!showCc && <button type="button" className="pt-1 text-xs font-semibold text-peacock-600 hover:underline" onClick={() => setShowCc(true)}>Cc/Bcc</button>}
        </RecipientField>
        {showCc && (
          <>
            <RecipientField label="Cc" chips={cc} setChips={setCc} text={ccText} setText={setCcText} />
            <RecipientField label="Bcc" chips={bcc} setChips={setBcc} text={bccText} setText={setBccText} />
          </>
        )}
        <div className="flex items-center gap-2 border-b border-slate-200/80 px-4 py-1.5">
          <span className="w-10 shrink-0 text-xs font-semibold text-slate-500">Subject</span>
          <input className="min-w-0 flex-1 bg-transparent py-1 text-sm font-medium outline-none" value={subject} onChange={(e) => setSubject(e.target.value)} aria-label="Subject" />
        </div>

        <textarea ref={bodyRef} className="scroll-thin min-h-0 flex-1 resize-none bg-white px-4 py-3 text-sm leading-relaxed outline-none"
          value={text} onChange={(e) => setText(e.target.value)} aria-label="Message" placeholder="Write something kind…" />

        {files.length > 0 && (
          <div className="flex flex-wrap gap-1.5 border-t border-slate-100 px-4 py-2">
            {files.map((f, i) => (
              <span key={`${f.name}-${i}`} className="chip !bg-slate-100 !text-slate-700">
                <IconPaperclip size={11} /> {f.name} <span className="text-slate-500">{formatSize(f.size)}</span>
                <button type="button" onClick={() => setFiles(files.filter((_, j) => j !== i))} aria-label={`Remove ${f.name}`}><IconX size={11} /></button>
              </span>
            ))}
            <span className="self-center text-[11px] text-slate-500">{formatSize(totalSize)} total</span>
          </div>
        )}

        {error && <p className="border-t border-red-100 bg-red-50 px-4 py-2 text-sm text-red-700" role="alert">{error}</p>}

        <div className="flex items-center gap-2 border-t border-slate-200 bg-slate-50 px-4 py-2.5">
          <button className="btn-primary" onClick={send} disabled={sending}>
            {sending ? <span className="spinner" /> : <IconSend size={15} />} {sending ? 'Sending…' : 'Send'}
          </button>
          <button className="btn-ghost" onClick={() => fileInput.current?.click()} disabled={sending} title="Attach files">
            <IconPaperclip size={16} /> Attach
          </button>
          <input ref={fileInput} type="file" multiple className="hidden"
            onChange={(e) => { setFiles([...files, ...Array.from(e.target.files || [])]); e.target.value = '' }} />
          <span className="ml-auto hidden text-[11px] text-slate-400 sm:inline">Ctrl + Enter to send</span>
        </div>
      </div>
    </div>
  )
}
