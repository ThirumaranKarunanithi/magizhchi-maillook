import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { post } from '../api.js'
import { useMail } from '../state.jsx'
import { IconCheck, IconClock } from './Icons.jsx'

const pad = (n) => String(n).padStart(2, '0')

/** A datetime-local value for a time today/tomorrow (local time). */
function localInput(d) {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function nextHour() {
  const d = new Date()
  d.setMinutes(0, 0, 0)
  d.setHours(d.getHours() + 1)
  return d
}

const cleanSubject = (s) => (s || '').replace(/^((re|fwd?)\s*:\s*)+/i, '').trim()

/** "Add to PA": turn this email into a Magizhchi PA task or calendar event. */
export default function PaMenu({ message }) {
  const { pa, notify } = useMail()
  const [open, setOpen] = useState(false)
  const [kind, setKind] = useState('task')
  const [title, setTitle] = useState('')
  const [when, setWhen] = useState('')
  const [minutes, setMinutes] = useState(30)
  const [priority, setPriority] = useState('')
  const [busy, setBusy] = useState(false)
  const [done, setDone] = useState(null)
  const ref = useRef(null)
  const popRef = useRef(null)
  const [pos, setPos] = useState(null)

  // The reading pane clips overflow, so place the popover against the viewport, kept on screen.
  function toggle() {
    if (!open && ref.current) {
      const r = ref.current.getBoundingClientRect()
      const w = Math.min(320, window.innerWidth - 16)
      setPos({ top: r.bottom + 4, left: Math.max(8, Math.min(r.right - w, window.innerWidth - w - 8)), width: w })
    }
    setOpen((o) => !o)
  }

  useEffect(() => {
    if (!open) return
    setTitle(cleanSubject(message.subject) || `Email from ${message.fromName || message.fromEmail}`)
    setWhen(localInput(nextHour()))
    setDone(null)
    const onDown = (e) => { if (!ref.current?.contains(e.target) && !popRef.current?.contains(e.target)) setOpen(false) }
    const onKey = (e) => e.key === 'Escape' && setOpen(false)
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [open, message])

  if (!pa?.connected) return null

  async function submit(e) {
    e.preventDefault()
    setBusy(true)
    try {
      const at = when ? new Date(when).toISOString() : null
      const res = kind === 'task'
        ? await post(`/api/messages/${message.id}/pa/task`, { title, dueAt: at, priority: priority || null })
        : await post(`/api/messages/${message.id}/pa/event`, { title, startAt: at, durationMinutes: Number(minutes) })
      setDone(res)
      notify(res?.message || 'Added to Magizhchi PA', 'success')
    } catch (err) {
      notify(err.message, 'error')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="relative" ref={ref} onClick={(e) => e.stopPropagation()}>
      <button className="btn-ghost" onClick={toggle} aria-haspopup="dialog" aria-expanded={open} title="Add to Magizhchi PA">
        <IconClock size={17} /><span className="hidden sm:inline">Add to PA</span>
      </button>
      {open && createPortal(
        <div ref={popRef} className="menu !fixed max-h-[80vh] overflow-y-auto p-3" style={pos || undefined} onClick={(e) => e.stopPropagation()} role="dialog" aria-label="Add to Magizhchi PA">
          {done ? (
            <div className="space-y-2 text-sm">
              <p className="flex items-start gap-2 font-medium text-emerald-700"><IconCheck size={16} className="mt-0.5" /> {done.message}</p>
              {done.webUrl && <a className="text-xs font-semibold text-brand-primary hover:underline" href={done.webUrl} target="_blank" rel="noreferrer">Open Magizhchi PA ↗</a>}
            </div>
          ) : (
            <form onSubmit={submit} className="space-y-2.5">
              <div className="flex rounded-lg bg-slate-100 p-0.5 text-xs font-semibold">
                {[['task', 'Task'], ['event', 'Calendar event']].map(([k, l]) => (
                  <button key={k} type="button" onClick={() => setKind(k)}
                    className={`flex-1 rounded-md px-2 py-1 ${kind === k ? 'bg-white text-brand-ink shadow-sm' : 'text-slate-500'}`}>{l}</button>
                ))}
              </div>
              <div>
                <label className="label" htmlFor="pa-title">Title</label>
                <input id="pa-title" className="input" value={title} onChange={(e) => setTitle(e.target.value)} maxLength={300} required />
              </div>
              <div>
                <label className="label" htmlFor="pa-when">{kind === 'task' ? 'Due (optional)' : 'Starts'}</label>
                <input id="pa-when" className="input" type="datetime-local" value={when} onChange={(e) => setWhen(e.target.value)} required={kind === 'event'} />
              </div>
              {kind === 'task' ? (
                <div>
                  <label className="label" htmlFor="pa-priority">Priority</label>
                  <select id="pa-priority" className="input" value={priority} onChange={(e) => setPriority(e.target.value)}>
                    <option value="">Normal</option>
                    <option value="HIGH">High</option>
                    <option value="MEDIUM">Medium</option>
                    <option value="LOW">Low</option>
                  </select>
                </div>
              ) : (
                <div>
                  <label className="label" htmlFor="pa-len">Length</label>
                  <select id="pa-len" className="input" value={minutes} onChange={(e) => setMinutes(e.target.value)}>
                    {[15, 30, 45, 60, 90, 120].map((m) => <option key={m} value={m}>{m < 60 ? `${m} min` : `${m / 60} hr`}</option>)}
                  </select>
                </div>
              )}
              <p className="text-[11px] text-slate-500">The email's sender, subject and text go into the {kind === 'task' ? 'task' : 'event'} notes.</p>
              <button className="btn-primary w-full" disabled={busy || !title.trim() || (kind === 'event' && !when)}>
                {busy && <span className="spinner" />} Add to Magizhchi PA
              </button>
            </form>
          )}
        </div>,
        document.body
      )}
    </div>
  )
}
