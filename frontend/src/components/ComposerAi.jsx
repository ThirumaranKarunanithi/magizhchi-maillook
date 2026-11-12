import { useEffect, useRef, useState } from 'react'
import { get, post } from '../api.js'
import { useMail } from '../state.jsx'
import { IconCalendar, IconChevron, IconSparkle, IconX } from './Icons.jsx'

const STYLES = [
  { key: 'shorter', label: 'Shorter' },
  { key: 'friendly', label: 'Friendlier' },
  { key: 'formal', label: 'More formal' },
  { key: 'confident', label: 'More confident' },
  { key: 'polish', label: 'Fix spelling & grammar' },
  { key: 'tamil', label: 'In Tamil' },
  { key: 'english', label: 'In English' },
]

/**
 * The parts of the body: what they wrote, the signature block and the quoted mail below it. Only the
 * first is ever written or rewritten.
 */
function parts(text, anchor, sig) {
  const at = anchor ? text.indexOf(anchor) : -1
  let head = at >= 0 ? text.slice(0, at) : text
  const tail = at >= 0 ? text.slice(at) : ''
  let block = ''
  if (sig && head.includes(sig)) {
    const i = head.lastIndexOf(sig)
    block = head.slice(i)
    head = head.slice(0, i)
  }
  return { head, block, tail }
}

function useOutside(ref, open, onClose) {
  useEffect(() => {
    if (!open) return undefined
    const down = (e) => !ref.current?.contains(e.target) && onClose()
    document.addEventListener('mousedown', down)
    return () => document.removeEventListener('mousedown', down)
  }, [open]) // eslint-disable-line react-hooks/exhaustive-deps
}

/**
 * Above the message: write the whole mail from a sentence, rewrite what's there (shorter, friendlier,
 * more formal, fixed, translated), and put free times from the PA calendar into the text.
 */
export default function ComposerAi({ aiEnabled, text, setText, anchor, sig, accountId, to, subject, setSubject, replyToId, bodyRef, notify }) {
  const { refreshAi } = useMail()
  const [checking, setChecking] = useState(false)
  // Opening a mail to write is a good moment to look again whether PA's AI has come on.
  useEffect(() => { if (aiEnabled === false) refreshAi() }, []) // eslint-disable-line react-hooks/exhaustive-deps
  const [mode, setMode] = useState(null) // 'write' | 'rewrite' | 'times'
  const [prompt, setPrompt] = useState('')
  const [custom, setCustom] = useState('')
  const [busy, setBusy] = useState(null)
  const [undo, setUndo] = useState(null) // { text, subject, label }
  const [times, setTimes] = useState(null)
  const [minutes, setMinutes] = useState(30)
  const [picked, setPicked] = useState([])
  const box = useRef(null)
  useOutside(box, mode === 'rewrite' || mode === 'times', () => setMode(null))

  const { head, block, tail } = parts(text, anchor, sig)
  const hasText = head.trim().length > 0

  // A change of their own makes the undo stale.
  useEffect(() => {
    if (undo && text !== undo.after) setUndo(null)
  }, [text]) // eslint-disable-line react-hooks/exhaustive-deps

  function apply(newHead, label, newSubject) {
    const before = { text, subject }
    const next = `${newHead.trimEnd()}${block || ''}${tail}`
    setText(next)
    if (newSubject && !subject.trim()) setSubject(newSubject)
    setUndo({ ...before, after: next, label })
    setMode(null)
    requestAnimationFrame(() => bodyRef.current?.focus())
  }

  async function write(e) {
    e?.preventDefault()
    if (prompt.trim().length < 3 || busy) return
    setBusy('write')
    try {
      const res = await post('/api/ai/write', { accountId: Number(accountId) || null, prompt: prompt.trim(), to, subject, replyToId: replyToId || null })
      apply(res.body, 'Written by AI', res.subject)
      setPrompt('')
    } catch (err) {
      notify(err.message, 'error')
    } finally {
      setBusy(null)
    }
  }

  async function rewrite(style, instruction) {
    if (!hasText || busy) return
    setBusy(style || 'custom')
    try {
      const res = await post('/api/ai/rewrite', { text: head.trim(), style: style || '', instruction: instruction || null })
      apply(res.body, STYLES.find((s) => s.key === style)?.label || 'Rewritten')
      setCustom('')
    } catch (err) {
      notify(err.message, 'error')
    } finally {
      setBusy(null)
    }
  }

  async function loadTimes(m = minutes) {
    setMode('times')
    setTimes(null)
    setPicked([])
    try {
      const res = await get(`/api/meet/free?minutes=${m}&count=5`)
      setTimes(res)
      setPicked(res.slots.slice(0, 3).map((s) => s.start))
    } catch (err) {
      setMode(null)
      notify(err.message, 'error')
    }
  }

  function insertTimes() {
    const chosen = (times?.slots || []).filter((s) => picked.includes(s.start))
    if (!chosen.length) return
    const len = minutes >= 60 ? `${minutes / 60} hour${minutes > 60 ? 's' : ''}` : `${minutes} minutes`
    const lines = `Would any of these times work for you (${len}, India time)?\n${chosen.map((s) => `- ${s.label}`).join('\n')}\n`
    // At the cursor when it's in their part of the mail, else at the end of it.
    const el = bodyRef.current
    const pos = el && el.selectionStart <= head.length && document.activeElement === el ? el.selectionStart : head.trimEnd().length
    const before = head.slice(0, pos)
    const after = head.slice(pos)
    const sep = before && !before.endsWith('\n\n') ? (before.endsWith('\n') ? '\n' : '\n\n') : ''
    apply(`${before}${sep}${lines}${after}`, 'Times added')
  }

  return (
    <div ref={box} className="relative border-b border-slate-200/80 bg-slate-50/50">
      <div className="flex flex-wrap items-center gap-1 px-3 py-1">
        {aiEnabled && (
          <>
            <button type="button" className={`btn-ghost !px-2 !py-1 text-xs ${mode === 'write' ? '!bg-peacock-50 !text-peacock-800' : ''}`}
              onClick={() => setMode(mode === 'write' ? null : 'write')} aria-expanded={mode === 'write'}>
              <IconSparkle size={14} className="text-peacock-600" /> Write with AI
            </button>
            <button type="button" className={`btn-ghost !px-2 !py-1 text-xs ${mode === 'rewrite' ? '!bg-peacock-50 !text-peacock-800' : ''}`}
              onClick={() => setMode(mode === 'rewrite' ? null : 'rewrite')} disabled={!hasText} aria-expanded={mode === 'rewrite'}
              title={hasText ? 'Change how it sounds' : 'Write something first'}>
              Rewrite <IconChevron size={12} className="rotate-90" />
            </button>
          </>
        )}
        <button type="button" className={`btn-ghost !px-2 !py-1 text-xs ${mode === 'times' ? '!bg-peacock-50 !text-peacock-800' : ''}`}
          onClick={() => (mode === 'times' ? setMode(null) : loadTimes())} aria-expanded={mode === 'times'}>
          <IconCalendar size={14} /> Suggest times
        </button>
        {aiEnabled === false && (
          <span className="flex items-center gap-1 px-1 text-xs text-slate-500" title="Write with AI and Rewrite come from Magizhchi PA's AI">
            <IconSparkle size={13} /> AI is off ·
            <button type="button" className="font-semibold text-peacock-700 hover:underline disabled:opacity-60" disabled={checking}
              onClick={async () => { setChecking(true); const on = await refreshAi(); setChecking(false); if (!on) notify('PA’s AI is still off.', 'info') }}>
              {checking ? 'Checking…' : 'Check again'}
            </button>
          </span>
        )}
        {busy && <span className="flex items-center gap-1.5 px-1 text-xs text-slate-500"><span className="spinner" /> {busy === 'write' ? 'Writing…' : 'Rewriting…'}</span>}
        {undo && !busy && (
          <span className="ml-auto flex items-center gap-1.5 text-xs text-slate-500">
            {undo.label}
            <button type="button" className="font-semibold text-peacock-700 hover:underline"
              onClick={() => { setText(undo.text); setSubject(undo.subject); setUndo(null) }}>Undo</button>
          </span>
        )}
      </div>

      {mode === 'write' && (
        <form onSubmit={write} className="flex items-center gap-2 px-3 pb-2">
          <input autoFocus className="input !py-1.5 text-sm" value={prompt} onChange={(e) => setPrompt(e.target.value)} maxLength={2000}
            placeholder={replyToId ? 'What should the reply say? e.g. yes to Saturday, ask about parking' : 'What should it say? e.g. ask Ravi for the October fee receipts by Friday'}
            onKeyDown={(e) => { if (e.key === 'Escape') { e.stopPropagation(); setMode(null) } }} aria-label="What the mail should say" />
          <button className="btn-primary !px-3 !py-1.5 text-sm" disabled={prompt.trim().length < 3 || !!busy}>
            {busy === 'write' ? <span className="spinner" /> : <IconSparkle size={14} />} Write
          </button>
          <button type="button" className="icon-btn" onClick={() => setMode(null)} aria-label="Close"><IconX size={14} /></button>
        </form>
      )}
      {mode === 'write' && hasText && <p className="-mt-1 px-3 pb-2 text-[11px] text-slate-500">This replaces what you've written above your signature. You can undo it.</p>}

      {mode === 'rewrite' && (
        <div className="absolute left-3 top-full z-20 mt-1 w-64 rounded-xl border border-slate-200 bg-white p-1 shadow-lg" role="menu">
          {STYLES.map((s) => (
            <button key={s.key} type="button" role="menuitem" className="flex w-full items-center rounded-lg px-3 py-1.5 text-left text-sm hover:bg-slate-100"
              onClick={() => rewrite(s.key)} disabled={!!busy}>
              {s.label}
            </button>
          ))}
          <form className="mt-1 flex gap-1 border-t border-slate-100 p-1 pt-2" onSubmit={(e) => { e.preventDefault(); if (custom.trim()) rewrite('', custom.trim()) }}>
            <input className="input !py-1 text-xs" value={custom} onChange={(e) => setCustom(e.target.value)} placeholder="Or say how: e.g. sound less urgent" maxLength={500} aria-label="How to rewrite it" />
            <button className="btn-secondary !px-2 !py-1 text-xs" disabled={!custom.trim() || !!busy}>Go</button>
          </form>
        </div>
      )}

      {mode === 'times' && (
        <div className="absolute left-3 right-3 top-full z-20 mt-1 rounded-xl border border-slate-200 bg-white p-3 shadow-lg sm:left-auto sm:w-80">
          <div className="mb-2 flex items-center gap-2 text-xs">
            <span className="font-semibold">Free times</span>
            <select className="ml-auto rounded-md border border-slate-200 bg-white px-1.5 py-0.5 text-xs" value={minutes}
              onChange={(e) => { const m = Number(e.target.value); setMinutes(m); loadTimes(m) }} aria-label="How long">
              {[15, 30, 45, 60, 90].map((m) => <option key={m} value={m}>{m < 60 ? `${m} min` : m === 60 ? '1 hour' : '1½ hours'}</option>)}
            </select>
          </div>
          {!times && <p className="flex items-center gap-2 py-2 text-xs text-slate-500"><span className="spinner" /> Looking at your calendar…</p>}
          {times && !times.slots.length && <p className="py-2 text-xs text-slate-500">No free time in the next week and a half.</p>}
          {times?.slots.map((s) => (
            <label key={s.start} className="flex cursor-pointer items-center gap-2 rounded-lg px-1.5 py-1 text-sm hover:bg-slate-50">
              <input type="checkbox" checked={picked.includes(s.start)}
                onChange={(e) => setPicked((p) => (e.target.checked ? [...p, s.start] : p.filter((x) => x !== s.start)))} />
              {s.label}
            </label>
          ))}
          {times && (
            <p className="mt-1 text-[11px] text-slate-500">
              {times.calendarChecked ? 'Checked against your Magizhchi PA calendar.' : 'Working hours only: your PA calendar couldn’t be read.'} India time.
            </p>
          )}
          <div className="mt-2 flex justify-end gap-2">
            <button type="button" className="btn-ghost !px-2 !py-1 text-xs" onClick={() => setMode(null)}>Cancel</button>
            <button type="button" className="btn-primary !px-3 !py-1 text-xs" onClick={insertTimes} disabled={!picked.length}>Add to mail</button>
          </div>
        </div>
      )}
    </div>
  )
}
