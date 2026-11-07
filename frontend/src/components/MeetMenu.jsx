import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { post } from '../api.js'
import { useMeet } from '../meet.js'
import { useMail } from '../state.jsx'
import { IconVideo, IconX } from './Icons.jsx'

const pad = (n) => String(n).padStart(2, '0')
const localInput = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
const cleanSubject = (s) => (s || '').replace(/^((re|fwd?)\s*:\s*)+/i, '').trim()

function nextHalfHour() {
  const d = new Date()
  d.setSeconds(0, 0)
  d.setMinutes(d.getMinutes() < 30 ? 30 : 60)
  return d
}

/** Address part of "Name <a@b>" or a bare address, lower-cased. */
const addr = (s) => ((s || '').match(/<([^>]+)>/)?.[1] || s || '').trim().toLowerCase()

/**
 * "Meet": start a Magizhchi Meet room for this email's people, now or at a set time. Meet itself
 * emails everyone the link (with a calendar invite when it has a time); MailLook then opens an
 * in-thread reply with the link, for the person to read and send.
 */
export default function MeetMenu({ message, onStarted }) {
  const meet = useMeet()
  const { accounts, openComposer, notify } = useMail()
  const [open, setOpen] = useState(false)
  const [name, setName] = useState('')
  const [people, setPeople] = useState([])
  const [when, setWhen] = useState('now')
  const [at, setAt] = useState('')
  const [minutes, setMinutes] = useState(30)
  const [busy, setBusy] = useState(false)
  const ref = useRef(null)
  const popRef = useRef(null)
  const [pos, setPos] = useState(null)

  function toggle() {
    if (!open && ref.current) {
      const r = ref.current.getBoundingClientRect()
      const w = Math.min(340, window.innerWidth - 16)
      setPos({ top: r.bottom + 4, left: Math.max(8, Math.min(r.right - w, window.innerWidth - w - 8)), width: w })
    }
    setOpen((o) => !o)
  }

  useEffect(() => {
    if (!open) return
    const mine = new Set([message.accountEmail, ...(accounts || []).map((a) => a.email)].map(addr))
    const seen = new Set()
    const list = [message.fromEmail, ...(message.to || []), ...(message.cc || [])]
      .map(addr).filter((e) => e && e.includes('@') && !mine.has(e) && !seen.has(e) && seen.add(e))
    setPeople(list.map((email) => ({ email, on: true })))
    setName(cleanSubject(message.subject) || `Call with ${message.fromName || message.fromEmail}`)
    setWhen('now')
    setAt(localInput(nextHalfHour()))
    setMinutes(30)
    const onDown = (e) => { if (!ref.current?.contains(e.target) && !popRef.current?.contains(e.target)) setOpen(false) }
    const onKey = (e) => e.key === 'Escape' && setOpen(false)
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [open, message, accounts])

  if (!meet?.connected) return null

  const chosen = people.filter((p) => p.on).map((p) => p.email)
  const startAt = when === 'later' && at ? new Date(at) : null
  const past = startAt && startAt < new Date()

  async function submit(e) {
    e.preventDefault()
    // Open the tab now, while the click still counts as the person's, then point it at the room.
    const tab = when === 'now' ? window.open('', '_blank') : null
    setBusy(true)
    try {
      const res = await post(`/api/messages/${message.id}/meet`, {
        name: name.trim(), emails: chosen, startAt: startAt ? startAt.toISOString() : null, minutes: Number(minutes),
      })
      if (tab) {
        tab.opener = null
        tab.location.href = res.link
      }
      setOpen(false)
      notify(res.message || 'Room ready in Magizhchi Meet', res.failed?.length ? 'error' : 'success')
      onStarted?.()
      openComposer({ mode: 'replyAll', message, draftBody: res.replyBody })
    } catch (err) {
      tab?.close()
      notify(err.message, 'error')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="relative" ref={ref} onClick={(e) => e.stopPropagation()}>
      <button className="btn-ghost" onClick={toggle} aria-haspopup="dialog" aria-expanded={open} title="Meet them in Magizhchi Meet">
        <IconVideo size={17} /><span className="hidden sm:inline">Meet</span>
      </button>
      {open && createPortal(
        <div ref={popRef} className="menu !fixed max-h-[80vh] overflow-y-auto p-3" style={pos || undefined} onClick={(e) => e.stopPropagation()} role="dialog" aria-label="Meet in Magizhchi Meet">
          <form onSubmit={submit} className="space-y-2.5">
            <div className="flex rounded-lg bg-slate-100 p-0.5 text-xs font-semibold">
              {[['now', 'Now'], ['later', 'Pick a time']].map(([k, l]) => (
                <button key={k} type="button" onClick={() => setWhen(k)} aria-pressed={when === k}
                  className={`flex-1 rounded-md px-2 py-1 ${when === k ? 'bg-white text-brand-ink shadow-sm' : 'text-slate-500'}`}>{l}</button>
              ))}
            </div>
            <div>
              <label className="label" htmlFor="meet-name">Room name</label>
              <input id="meet-name" className="input" value={name} onChange={(e) => setName(e.target.value)} maxLength={120} required />
            </div>
            {when === 'later' && (
              <div className="grid grid-cols-[minmax(0,1fr)_6.5rem] gap-2">
                <div>
                  <label className="label" htmlFor="meet-at">Starts</label>
                  <input id="meet-at" className="input" type="datetime-local" value={at} onChange={(e) => setAt(e.target.value)} required />
                </div>
                <div>
                  <label className="label" htmlFor="meet-len">Length</label>
                  <select id="meet-len" className="input" value={minutes} onChange={(e) => setMinutes(e.target.value)}>
                    {[15, 30, 45, 60, 90, 120].map((m) => <option key={m} value={m}>{m < 60 ? `${m} min` : `${m / 60} hr`}</option>)}
                  </select>
                </div>
              </div>
            )}
            <div>
              <p className="label">Invite</p>
              {people.length === 0 ? (
                <p className="text-xs text-slate-500">No one else is on this email.</p>
              ) : (
                <ul className="flex flex-wrap gap-1.5">
                  {people.map((p, i) => (
                    <li key={p.email}>
                      <button type="button" aria-pressed={p.on}
                        onClick={() => setPeople((l) => l.map((x, j) => (j === i ? { ...x, on: !x.on } : x)))}
                        className={`inline-flex max-w-[260px] items-center gap-1 rounded-full border px-2 py-0.5 text-xs ${p.on ? 'border-peacock-300 bg-peacock-50 font-medium text-peacock-800' : 'border-slate-200 text-slate-400 line-through'}`}>
                        <span className="truncate">{p.email}</span>{p.on && <IconX size={11} />}
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </div>
            <p className="text-[11px] text-slate-500">
              Magizhchi Meet emails them the link{when === 'later' ? ' with a calendar invite' : ''}. Guests can join from a browser. You'll get a reply ready to send.
            </p>
            {past && <p className="text-xs text-red-700">That time has already passed.</p>}
            <button className="btn-primary w-full" disabled={busy || !name.trim() || chosen.length === 0 || past || (when === 'later' && !at)}>
              {busy && <span className="spinner" />} {when === 'now' ? 'Start room now' : 'Set up the meeting'}
            </button>
          </form>
        </div>,
        document.body
      )}
    </div>
  )
}
