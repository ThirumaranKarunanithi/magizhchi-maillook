import { useEffect, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { del, postForm, put } from '../api.js'
import { useMail } from '../state.jsx'
import { bodyAsText, EMAIL_RE, formatSize, fullDate, signatureBlock, splitAddresses } from '../util.js'
import { IconAlert, IconBell, IconCalendar, IconPaperclip, IconPlus, IconSend, IconTrash, IconX } from './Icons.jsx'
import ConfirmDialog from './ConfirmDialog.jsx'
import { SuggestList, useAddressCheck, useOftenWith, useSuggestions } from './AddressSuggest.jsx'
import { FOLLOW_UP, followUpAt } from '../when.js'

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

/** The chips plus whatever is still half-typed in the field. */
function allOf(chips, typed) {
  return uniq([...chips, ...splitAddresses(typed)])
}

/**
 * Swaps the signature when From changes: the old mailbox's block becomes the new one's. With no old
 * block, the new one goes above the quote (anchor) or at the end; a signature they edited is left alone.
 */
function swapSignature(text, oldBlock, newBlock, anchor) {
  if (oldBlock && text.includes(oldBlock)) return text.replace(oldBlock, () => newBlock)
  if (!newBlock || oldBlock) return text
  const at = anchor ? text.indexOf(anchor) : -1
  return at >= 0 ? text.slice(0, at) + newBlock + text.slice(at) : text + newBlock
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

/** Works out From/To/Cc/Subject/body for new, reply, reply all and forward, or a saved draft. */
function initialDraft({ mode, message: m, draftBody, to: toOverride, subject: subjectOverride, invite, draft }, accounts, selectedAccountId) {
  const own = new Set(accounts.map((a) => a.email.toLowerCase()))
  const fallbackFrom = selectedAccountId ?? accounts[0]?.id ?? ''
  const sig = (id) => signatureBlock(accounts.find((a) => a.id === id)?.signature)
  if (draft) {
    // Back from Drafts exactly as it was left (its signature is already in the text).
    return {
      accountId: accounts.some((a) => a.id === draft.accountId) ? draft.accountId : fallbackFrom,
      to: draft.to || [], cc: draft.cc || [], bcc: draft.bcc || [], subject: draft.subject || '', text: draft.body || '',
      replyToMessageId: draft.replyToMessageId || null,
      invite: draft.inviteCardId ? { cardId: draft.inviteCardId, name: 'your Meet room' } : null,
      draftId: draft.id, anchor: null,
    }
  }
  if (!m || mode === 'new') {
    // A new mail can still start from one (the weekly digest): same mailbox, chosen people, ready text.
    const from = m && accounts.some((a) => a.id === m.accountId) ? m.accountId : fallbackFrom
    return {
      accountId: from, to: uniq(toOverride || [], own), cc: [], bcc: [], subject: subjectOverride || '',
      text: `${draftBody || ''}${sig(from)}`,
      replyToMessageId: null, invite: null, draftId: null, anchor: null,
    }
  }

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
      accountId, to: [], cc: [], bcc: [],
      subject: prefixed(m.subject, 'Fwd:', /^\s*(fwd?|fw)\s*:/i),
      text: `${sig(accountId)}\n\n${header}\n\n${bodyAsText(m)}`,
      replyToMessageId: null,
      invite: null, draftId: null, anchor: `\n\n${header}`,
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
  // A reply to chosen people (the meeting recap goes to who came; the catch-up to who missed it).
  if (toOverride?.length) {
    to = uniq(toOverride, own)
    cc = []
  }
  return {
    accountId, to, cc, bcc: [],
    subject: subjectOverride || prefixed(m.subject, 'Re:', /^\s*re\s*:/i),
    // Signature above the quote, where it belongs in a reply.
    text: `${draftBody ? `${draftBody}\n` : ''}${sig(accountId)}\n\n${quote(m)}`,
    replyToMessageId: m.id,
    draftId: null, anchor: `\n\n${quote(m)}`,
    // A Meet room booked from this thread: the reply is its invite and carries the calendar file.
    invite: invite || null,
  }
}

/** To/Cc/Bcc: chips plus the half-typed text, both owned by the parent so Send never loses a typed address. */
function RecipientField({ label, chips, setChips, text, setText, autoFocus, accountId, others = [], names, onName, children }) {
  const suggestions = useSuggestions(text, accountId, [...others, ...chips])
  const [active, setActive] = useState(0)
  const [hidden, setHidden] = useState(false)
  useEffect(() => { setActive(0); setHidden(false) }, [suggestions])
  const showing = !hidden && suggestions.length > 0 && text.trim().length > 0

  function commit() {
    const parts = splitAddresses(text)
    if (parts.length) setChips(uniq([...chips, ...parts]))
    setText('')
  }
  function pick(s) {
    setChips(uniq([...chips, s.email]))
    if (s.name) onName?.(s.email, s.name)
    setText('')
  }
  return (
    <div className="relative flex items-start gap-2 border-b border-slate-200/80 px-4 py-1.5">
      <span className="w-10 shrink-0 pt-1.5 text-xs font-semibold text-slate-500">{label}</span>
      <div className="flex min-w-0 flex-1 flex-wrap items-center gap-1">
        {chips.map((c) => {
          const ok = EMAIL_RE.test(c)
          const name = names?.[c.toLowerCase()]
          return (
            <span key={c} className={`chip ${ok ? '' : '!bg-red-50 !text-red-700'}`} title={ok ? (name ? `${name} <${c}>` : c) : 'This does not look like an email address'}>
              {name || c}
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
            if (showing && e.key === 'ArrowDown') { e.preventDefault(); setActive((a) => (a + 1) % suggestions.length); return }
            if (showing && e.key === 'ArrowUp') { e.preventDefault(); setActive((a) => (a - 1 + suggestions.length) % suggestions.length); return }
            if (showing && (e.key === 'Enter' || e.key === 'Tab')) { e.preventDefault(); pick(suggestions[active]); return }
            if (showing && e.key === 'Escape') { e.stopPropagation(); setHidden(true); return }
            if (e.key === 'Enter' && text.trim()) { e.preventDefault(); commit() }
            if (e.key === 'Backspace' && !text && chips.length) setChips(chips.slice(0, -1))
          }}
          onBlur={commit}
          aria-label={label}
          aria-autocomplete="list"
          aria-expanded={showing}
          type="text"
          autoComplete="off"
        />
      </div>
      {children}
      {showing && <SuggestList items={suggestions} active={active} onPick={pick} onHover={setActive} />}
    </div>
  )
}

export default function Composer() {
  const { composer, closeComposer, accounts, accountId: selectedAccountId, notify, bumpMail, refreshCounts, refreshDrafts } = useMail()
  const init = useMemo(() => initialDraft(composer, accounts, selectedAccountId), []) // eslint-disable-line react-hooks/exhaustive-deps

  const [fromId, setFromId] = useState(init.accountId)
  const [to, setTo] = useState(init.to)
  const [cc, setCc] = useState(init.cc)
  const [bcc, setBcc] = useState(init.bcc)
  const [toText, setToText] = useState('')
  const [ccText, setCcText] = useState('')
  const [bccText, setBccText] = useState('')
  const [ask, setAsk] = useState(null) // 'discard' | 'unsaved' | 'nosubject' while a question is on screen
  const [showCc, setShowCc] = useState(init.cc.length > 0 || init.bcc.length > 0)
  const [subject, setSubject] = useState(init.subject)
  const [invite, setInvite] = useState(init.invite)
  const [text, setText] = useState(init.text)
  const [files, setFiles] = useState([])
  const [sending, setSending] = useState(false)
  const [error, setError] = useState('')
  const [remind, setRemind] = useState('')
  const [names, setNames] = useState({}) // email → name, from suggestions
  const [typoOk, setTypoOk] = useState(false) // they chose to send to an address that looked mistyped
  const fileInput = useRef(null)
  const bodyRef = useRef(null)
  // Drafts: saved a moment after each change and on close. One save at a time, in order.
  const draftId = useRef(init.draftId)
  const lastSaved = useRef(null)
  const saveChain = useRef(Promise.resolve(true))
  const stopSaving = useRef(false)
  const [saveState, setSaveState] = useState(init.draftId ? { at: composer.draft?.updatedAt } : null) // { busy } | { at } | { error }

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

  // only what the user typed or changed counts: an untouched reply (prefilled To, subject and quote) closes without a question
  const dirty = to.join() !== init.to.join() || cc.join() !== init.cc.join() || bcc.join() !== init.bcc.join() || toText || ccText || bccText
    || subject !== init.subject || text !== init.text || files.length

  const everyone = uniq([...to, ...cc, ...bcc])
  const oftenWith = useOftenWith(everyone, fromId).filter((s) => !everyone.some((x) => x.toLowerCase() === s.email))
  const checks = useAddressCheck(everyone)
  const typos = checks.filter((c) => c.problem === 'typo' || c.problem === 'invalid')
  const rememberName = (email, name) => setNames((n) => ({ ...n, [email.toLowerCase()]: name }))

  /** Swaps a mistyped address for the suggested one, wherever it is. */
  function fix(bad, good) {
    const swap = (list) => uniq(list.map((x) => (x.toLowerCase() === bad.toLowerCase() ? good : x)))
    setTo(swap)
    setCc(swap)
    setBcc(swap)
  }

  function changeFrom(id) {
    const block = (x) => signatureBlock(accounts.find((a) => a.id === Number(x))?.signature)
    setText((t) => swapSignature(t, block(fromId), block(id), init.anchor))
    setFromId(id)
  }

  /** Saves to Drafts; resolves true when what's on screen is saved. */
  function saveDraft() {
    const body = {
      accountId: fromId ? Number(fromId) : null,
      mode: composer.mode || 'new',
      replyToMessageId: init.replyToMessageId || null,
      to: allOf(to, toText), cc: allOf(cc, ccText), bcc: allOf(bcc, bccText),
      subject, body: text, inviteCardId: invite?.cardId || null,
    }
    const key = JSON.stringify(body)
    saveChain.current = saveChain.current.then(async () => {
      if (stopSaving.current) return true
      if (key === lastSaved.current && draftId.current) return true
      setSaveState({ busy: true })
      try {
        const res = await put('/api/drafts', { ...body, id: draftId.current })
        draftId.current = res.id
        lastSaved.current = key
        setSaveState({ at: res.savedAt })
        return true
      } catch (e) {
        setSaveState({ error: e.message })
        return false
      }
    })
    return saveChain.current
  }

  useEffect(() => {
    if (!dirty || sending) return undefined
    const t = setTimeout(saveDraft, 1500)
    return () => clearTimeout(t)
  }, [fromId, to, cc, bcc, toText, ccText, bccText, subject, text, invite]) // eslint-disable-line react-hooks/exhaustive-deps

  // Closing keeps the mail in Drafts instead of asking to throw it away.
  async function close() {
    if (sending || ask) return
    if (!dirty) return closeComposer()
    const ok = await saveDraft()
    if (!ok) return setAsk('unsaved')
    notify(files.length ? 'Saved to Drafts. Attachments aren\u2019t kept in drafts; add them again before sending.' : 'Saved to Drafts', 'info')
    refreshDrafts()
    closeComposer()
  }

  async function discard() {
    stopSaving.current = true
    await saveChain.current
    if (draftId.current) {
      try {
        await del(`/api/drafts/${draftId.current}`)
      } catch {
        /* it may already be gone */
      }
    }
    refreshDrafts()
    closeComposer()
  }

  useEffect(() => {
    const onKey = (e) => e.key === 'Escape' && !ask && close()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  })

  async function send(sendWithoutSubject = false) {
    setError('')
    const toList = allOf(to, toText)
    const ccList = allOf(cc, ccText)
    const bccList = allOf(bcc, bccText)
    const bad = [...toList, ...ccList, ...bccList].filter((a) => !EMAIL_RE.test(a))
    if (!fromId) return setError('Add a mailbox under Manage accounts before sending.')
    if (!toList.length && !ccList.length && !bccList.length) return setError('Who is this going to? Add at least one recipient.')
    if (bad.length) return setError(`These don't look like email addresses: ${bad.join(', ')}`)
    if (typos.length && !typoOk) {
      setTypoOk(true)
      return setError('One address looks mistyped (see above). Fix it, or press Send again to send as it is.')
    }
    if (!subject.trim() && !sendWithoutSubject) return setAsk('nosubject')

    const payload = {
      accountId: Number(fromId),
      to: toList, cc: ccList, bcc: bccList,
      subject, text,
      ...(init.replyToMessageId ? { replyToMessageId: init.replyToMessageId } : {}),
      ...(invite ? { inviteCardId: invite.cardId } : {}),
      ...(remind ? { remindAt: followUpAt(remind).toISOString() } : {}),
    }
    const form = new FormData()
    form.append('payload', new Blob([JSON.stringify(payload)], { type: 'application/json' }))
    for (const f of files) form.append('files', f, f.name)

    setSending(true)
    // No autosave may land after the send (it would bring the draft back).
    stopSaving.current = true
    await saveChain.current
    if (draftId.current) payload.draftId = draftId.current
    form.set('payload', new Blob([JSON.stringify(payload)], { type: 'application/json' }))
    try {
      await postForm('/api/send', form)
      notify(remind ? 'Sent ✓ If nobody replies, it comes back to Needs me.' : 'Sent ✓', 'success')
      closeComposer()
      bumpMail()
      refreshCounts()
      refreshDrafts()
    } catch (e) {
      stopSaving.current = false
      setError(e.message)
      setSending(false)
    }
  }

  const askNode = ask && (
    <ConfirmDialog
      title={{ discard: 'Discard this draft?', unsaved: 'Close without saving?' }[ask] || 'Send without a subject?'}
      confirmLabel={{ discard: 'Discard', unsaved: 'Close and lose it' }[ask] || 'Send anyway'}
      danger={ask !== 'nosubject'}
      onCancel={() => setAsk(null)}
      onConfirm={() => { const a = ask; setAsk(null); if (a === 'nosubject') send(true); else discard() }}>
      {{
        discard: draftId.current ? 'It will be deleted from Drafts.' : 'What you wrote will be lost.',
        unsaved: `We couldn't save it to Drafts (${saveState?.error || 'no connection'}). Keep the window open and try again in a moment, or close and lose what you wrote.`,
      }[ask] || 'People are more likely to open mail that says what it is about.'}
    </ConfirmDialog>
  )

  const title = { new: 'New message', reply: 'Reply', replyAll: 'Reply all', forward: 'Forward' }[composer.mode] || 'New message'
  const totalSize = files.reduce((n, f) => n + f.size, 0)

  return createPortal(
    <div className="modal-back fixed inset-0 z-50 flex items-stretch justify-end sm:items-center sm:justify-center sm:p-4" onMouseDown={(e) => e.target === e.currentTarget && close()}>
      <div className="card flex h-full w-full flex-col overflow-hidden !rounded-none sm:h-[min(760px,92vh)] sm:max-w-2xl sm:!rounded-2xl"
        role="dialog" aria-modal="true" aria-label={title}
        onKeyDown={(e) => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); send() } }}>
        <div className="flex items-center gap-2 border-b border-slate-200 bg-slate-50/80 px-4 py-2.5">
          <h2 className="flex-1 text-sm font-bold">{title}</h2>
          <button className="icon-btn" onClick={close} aria-label="Close"><IconX size={17} /></button>
        </div>

        <div className="flex items-center gap-2 border-b border-slate-200/80 px-4 py-1.5">
          <span className="w-10 shrink-0 text-xs font-semibold text-slate-500">From</span>
          <select className="min-w-0 flex-1 bg-transparent py-1 text-sm outline-none" value={fromId ?? ''} onChange={(e) => changeFrom(Number(e.target.value))} aria-label="From">
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
        <RecipientField label="To" chips={to} setChips={setTo} text={toText} setText={setToText} autoFocus={composer.mode === 'new' || composer.mode === 'forward'}
          accountId={fromId} others={[...cc, ...bcc]} names={names} onName={rememberName}>
          {!showCc && <button type="button" className="pt-1 text-xs font-semibold text-peacock-600 hover:underline" onClick={() => setShowCc(true)}>Cc/Bcc</button>}
        </RecipientField>
        {showCc && (
          <>
            <RecipientField label="Cc" chips={cc} setChips={setCc} text={ccText} setText={setCcText}
              accountId={fromId} others={[...to, ...bcc]} names={names} onName={rememberName} />
            <RecipientField label="Bcc" chips={bcc} setChips={setBcc} text={bccText} setText={setBccText}
              accountId={fromId} others={[...to, ...cc]} names={names} onName={rememberName} />
          </>
        )}
        {(oftenWith.length > 0 || checks.length > 0) && (
          <div className="space-y-1 border-b border-slate-200/80 bg-slate-50/60 px-4 py-1.5 text-xs">
            {checks.map((c) => (
              <p key={c.email} className={`flex flex-wrap items-center gap-1.5 ${c.problem === 'typo' || c.problem === 'invalid' ? 'text-amber-800' : 'text-slate-600'}`}>
                {c.problem === 'typo' || c.problem === 'invalid' ? <IconAlert size={13} /> : <span aria-hidden="true">👋</span>}
                <span>{c.message}</span>
                {c.didYouMean && (
                  <button type="button" className="font-semibold text-peacock-700 hover:underline" onClick={() => fix(c.email, c.didYouMean)}>
                    Use {c.didYouMean}
                  </button>
                )}
              </p>
            ))}
            {oftenWith.length > 0 && (
              <p className="flex flex-wrap items-center gap-1.5 text-slate-600">
                <span className="font-semibold">Often on these mails:</span>
                {oftenWith.map((s) => (
                  <button key={s.email} type="button" className="chip !bg-white hover:!bg-peacock-50" title={`${s.why || ''} · add to ${showCc ? 'Cc' : 'To'}`}
                    onClick={() => { if (s.name) rememberName(s.email, s.name); (showCc ? setCc : setTo)((l) => uniq([...l, s.email])) }}>
                    <IconPlus size={11} />{s.name || s.email}
                  </button>
                ))}
              </p>
            )}
          </div>
        )}
        <div className="flex items-center gap-2 border-b border-slate-200/80 px-4 py-1.5">
          <span className="w-10 shrink-0 text-xs font-semibold text-slate-500">Subject</span>
          <input className="min-w-0 flex-1 bg-transparent py-1 text-sm font-medium outline-none" value={subject} onChange={(e) => setSubject(e.target.value)} aria-label="Subject" />
        </div>

        <textarea ref={bodyRef} className="scroll-thin min-h-0 flex-1 resize-none bg-white px-4 py-3 text-sm leading-relaxed outline-none"
          value={text} onChange={(e) => setText(e.target.value)} aria-label="Message" placeholder="Write something kind…" />

        {invite && (
          <div className="flex items-center gap-2 border-t border-slate-100 px-4 py-2">
            <span className="chip !bg-peacock-50 !text-peacock-800" title="Sent from your mailbox, so answers come back to this thread">
              <IconCalendar size={12} /> Calendar invite: {invite.name}{invite.when ? ` · ${invite.when}` : ''}
              <button type="button" onClick={() => setInvite(null)} aria-label="Don't attach the calendar invite"><IconX size={11} /></button>
            </span>
            <span className="hidden text-[11px] text-slate-500 sm:inline">Yes / No answers come back to this thread.</span>
          </div>
        )}

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
          <button className="btn-primary" onClick={() => send()} disabled={sending}>
            {sending ? <span className="spinner" /> : <IconSend size={15} />} {sending ? 'Sending…' : 'Send'}
          </button>
          <button className="btn-ghost" onClick={() => fileInput.current?.click()} disabled={sending} title="Attach files">
            <IconPaperclip size={16} /> Attach
          </button>
          <label className={`btn-ghost !px-1.5 ${remind ? '!text-brand-primary' : ''}`} title="If nobody replies by then, the mail comes back to Needs me">
            <IconBell size={15} />
            <select className="max-w-[9.5rem] bg-transparent text-sm outline-none" value={remind} onChange={(e) => setRemind(e.target.value)} aria-label="Remind me if nobody replies">
              {FOLLOW_UP.map((f) => <option key={f.key} value={f.key}>{f.key ? `No reply? ${f.label.toLowerCase()}` : 'No reminder'}</option>)}
            </select>
          </label>
          <input ref={fileInput} type="file" multiple className="hidden"
            onChange={(e) => { setFiles([...files, ...Array.from(e.target.files || [])]); e.target.value = '' }} />
          <span className="ml-auto truncate text-[11px] text-slate-500" role="status" aria-live="polite">
            {saveState?.busy && 'Saving…'}
            {saveState?.at && `Saved to Drafts ${new Date(saveState.at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`}
            {saveState?.error && <span className="text-red-700" title={saveState.error}>Couldn't save the draft</span>}
            {!saveState && <span className="hidden sm:inline">Ctrl + Enter to send</span>}
          </span>
          {(dirty || draftId.current) && (
            <button className="icon-btn hover:!text-red-700" onClick={() => setAsk('discard')} disabled={sending} title="Discard draft" aria-label="Discard draft">
              <IconTrash size={16} />
            </button>
          )}
        </div>
      </div>
      {askNode}
    </div>,
    document.body,
  )
}
