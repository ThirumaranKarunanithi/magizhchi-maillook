import { useEffect, useRef, useState } from 'react'
import { post, put } from '../api.js'
import { useMail } from '../state.jsx'
import { relativeTime } from '../util.js'
import { IconNote, IconX } from './Icons.jsx'

/**
 * A private note on this conversation: who to call, what you promised, the parking tip. Only you see
 * it, search finds it, and it can be copied into Magizhchi PA Notes.
 */
export default function NoteCard({ message, open, onClose, onChange }) {
  const { pa, notify } = useMail()
  const [body, setBody] = useState(message.note?.body || '')
  const [saved, setSaved] = useState(message.note || null)
  const [state, setState] = useState(null) // 'saving' | 'error'
  const [toPa, setToPa] = useState(false)
  const last = useRef(message.note?.body || '')
  const area = useRef(null)

  useEffect(() => {
    if (open && !message.note?.body) area.current?.focus()
  }, [open]) // eslint-disable-line react-hooks/exhaustive-deps

  async function save(text = body) {
    const t = text.trim()
    if (t === last.current.trim()) return true
    setState('saving')
    try {
      const res = await put(`/api/messages/${message.id}/note`, { body: t })
      last.current = t
      setSaved(res?.body ? res : null)
      setState(null)
      onChange?.(!!res?.body)
      return true
    } catch (e) {
      setState('error')
      notify(e.message, 'error')
      return false
    }
  }

  // Saves a moment after they stop typing.
  useEffect(() => {
    if (body.trim() === last.current.trim()) return undefined
    const t = setTimeout(() => save(body), 1200)
    return () => clearTimeout(t)
  }, [body]) // eslint-disable-line react-hooks/exhaustive-deps

  async function sendToPa() {
    if (!(await save())) return
    setToPa(true)
    try {
      const res = await post(`/api/messages/${message.id}/pa/note`)
      notify(res?.message || 'Your note is in Magizhchi PA Notes.', 'success')
      setSaved((s) => (s ? { ...s, paNoteId: res?.id || s.paNoteId } : s))
    } catch (e) {
      notify(e.message, 'error')
    } finally {
      setToPa(false)
    }
  }

  if (!open && !saved?.body) return null

  return (
    <section className="rounded-xl border border-amber-200 bg-amber-50/70 p-3" aria-label="Your note on this conversation">
      <div className="mb-1.5 flex items-center gap-2 text-xs">
        <IconNote size={15} className="text-amber-700" />
        <span className="font-semibold text-amber-900">Your note</span>
        <span className="hidden text-amber-800/70 sm:inline">Only you can see this</span>
        <span className="ml-auto text-amber-800/70" role="status" aria-live="polite">
          {state === 'saving' ? 'Saving…' : state === 'error' ? 'Not saved' : saved?.updatedAt ? `Saved ${relativeTime(saved.updatedAt)}` : ''}
        </span>
        <button className="icon-btn !h-6 !w-6" onClick={async () => { await save(); onClose?.() }} aria-label="Close the note"><IconX size={13} /></button>
      </div>
      <textarea ref={area} rows={Math.min(8, Math.max(2, body.split('\n').length + 1))}
        className="w-full resize-y rounded-lg border border-amber-200 bg-white/90 px-2.5 py-2 text-sm leading-relaxed text-brand-ink outline-none focus:border-amber-400"
        placeholder="What to remember about this: what you promised, who to call, the next step…"
        value={body} maxLength={10000} onChange={(e) => setBody(e.target.value)} onBlur={() => save()} aria-label="Note" />
      <div className="mt-1.5 flex flex-wrap items-center gap-2 text-xs">
        {pa?.connected && (
          <button className="btn-secondary !px-2 !py-1 text-xs" onClick={sendToPa} disabled={toPa || !body.trim()}
            title={saved?.paNoteId ? 'Update the copy in Magizhchi PA Notes' : 'Copy this note into Magizhchi PA Notes'}>
            {toPa && <span className="spinner" />}{saved?.paNoteId ? 'Update in PA Notes' : 'Send to PA Notes'}
          </button>
        )}
        {saved?.paNoteId && pa?.webUrl && (
          <a className="font-semibold text-peacock-700 hover:underline" href={pa.webUrl} target="_blank" rel="noreferrer">Open PA ↗</a>
        )}
        {body.trim() && (
          <button className="ml-auto text-slate-500 hover:text-red-700" onClick={() => { setBody(''); save('') }}>Delete note</button>
        )}
      </div>
    </section>
  )
}
