import { useEffect, useState } from 'react'
import { get, post } from '../api.js'
import { minutesToStart, scheduleLine, useMeet } from '../meet.js'
import { useMail } from '../state.jsx'
import { safeHttpUrl } from '../util.js'
import { IconVideo } from './Icons.jsx'

function useTick(ms) {
  const [now, setNow] = useState(() => new Date())
  useEffect(() => {
    const t = setInterval(() => setNow(new Date()), ms)
    return () => clearInterval(t)
  }, [ms])
  return now
}

function soonText(m, live) {
  if (live > 0) return `Live now · ${live} in the room`
  if (m == null) return null
  if (m <= 0) return 'Started'
  if (m <= 10) return `Starts in ${m} min`
  return null
}

/**
 * Join card: the Magizhchi Meet rooms this thread links to (rooms started from it, or a Meet link
 * in the mail). Turns into a "starting soon" bar from ten minutes before the room opens.
 */
export function JoinCard({ message, refreshKey }) {
  const meet = useMeet()
  const [rooms, setRooms] = useState([])
  const [meetings, setMeetings] = useState([])
  const now = useTick(30000)
  const messageId = message.id

  useEffect(() => {
    if (!meet?.configured) return undefined
    let cancelled = false
    get(`/api/messages/${messageId}/meet`)
      .then((r) => {
        if (cancelled) return
        setRooms(r?.rooms || [])
        setMeetings(r?.meetings || [])
      })
      .catch(() => {
        if (cancelled) return
        setRooms([])
        setMeetings([])
      })
    return () => { cancelled = true }
  }, [messageId, refreshKey, meet?.configured])

  if (!meet?.configured || (rooms.length === 0 && meetings.length === 0)) return null

  return (
    <div className="space-y-2">
      {meetings.map((m, i) => <MeetingHeld key={m.id} meeting={m} message={message} defaultOpen={i === 0} />)}
      {rooms.map((r) => {
        const link = safeHttpUrl(r.link)
        const m = r.known ? minutesToStart(r, now) : null
        const soon = soonText(m, r.live)
        const line = r.known ? scheduleLine(r) : null
        return (
          <section key={r.id} aria-label={`Magizhchi Meet room ${r.name}`}
            className={`flex flex-wrap items-center gap-3 rounded-2xl border px-3.5 py-3 ${soon ? 'border-emerald-300 bg-emerald-50' : 'border-slate-200 bg-white/80'}`}>
            <span className={`grid h-10 w-10 shrink-0 place-items-center rounded-xl ${soon ? 'bg-emerald-500/15 text-emerald-700' : 'bg-peacock-50 text-peacock-700'}`}>
              <IconVideo size={20} />
            </span>
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-semibold text-brand-ink">{r.name || 'Magizhchi Meet room'}</p>
              <p className="truncate text-xs text-slate-500">
                {soon ? <span className="font-semibold text-emerald-800">{soon}</span> : line || 'Magizhchi Meet'}
                {soon && line ? <span> · {line}</span> : null}
                {r.locked && !soon ? ' · locked' : ''}
              </p>
            </div>
            {link && (
              <a className={soon ? 'btn-primary' : 'btn-secondary'} href={link} target="_blank" rel="noopener noreferrer">
                {soon ? 'Join now' : 'Join'}
              </a>
            )}
          </section>
        )
      })}
    </div>
  )
}

const firstNames = (people) => people.map((p) => (p.name || p.email || '').split(/\s+/)[0]).filter(Boolean)

function listNames(names) {
  if (names.length <= 2) return names.join(' and ')
  return `${names.slice(0, 2).join(', ')} and ${names.length - 2} more`
}

/**
 * A meeting held in this thread's room: when, how long, who came, the AI recap, and the two mails
 * that follow a meeting — the recap to who came and a catch-up to who missed it — one tap each.
 */
function MeetingHeld({ meeting: m, message, defaultOpen }) {
  const { openComposer } = useMail()
  const [open, setOpen] = useState(defaultOpen)
  const day = new Date(m.startedAt).toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short' })
  const time = new Date(m.startedAt).toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })
  const missed = m.missed || []
  const hasNotes = m.summary || m.decisions?.length || m.actions?.length

  return (
    <section aria-label={`Meeting held: ${m.name || 'Magizhchi Meet'}`} className="rounded-2xl border border-peacock-300/60 bg-white/80 p-3.5">
      <div className="flex items-start gap-3">
        <span className="grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-peacock-50 text-peacock-700"><IconVideo size={20} /></span>
        <div className="min-w-0 flex-1">
          <p className="text-sm font-semibold text-brand-ink">Meeting held · {day}, {time} · {m.minutes} min</p>
          <p className="text-xs text-slate-500">
            {m.attended?.length || 0} came{missed.length ? ` · ${listNames(firstNames(missed))} missed it` : ''}
            {m.tasksAdded ? ` · ${m.tasksAdded} task${m.tasksAdded === 1 ? '' : 's'} on your PA board` : ''}
          </p>
        </div>
        {hasNotes && (
          <button className="btn-ghost !px-2 !py-1 text-xs" onClick={() => setOpen((o) => !o)} aria-expanded={open}>
            {open ? 'Hide notes' : 'Notes'}
          </button>
        )}
      </div>

      {open && hasNotes && (
        <div className="mt-3 space-y-2.5 border-t border-slate-100 pt-3 text-sm">
          {m.summary && <p className="text-slate-700">{m.summary}</p>}
          {m.decisions?.length > 0 && (
            <div>
              <p className="label !mb-1">Decided</p>
              <ul className="list-disc space-y-0.5 pl-5 text-slate-700">{m.decisions.map((d, i) => <li key={i}>{d}</li>)}</ul>
            </div>
          )}
          {m.actions?.length > 0 && (
            <div>
              <p className="label !mb-1">Next steps</p>
              <ul className="space-y-0.5 text-slate-700">
                {m.actions.map((a, i) => (
                  <li key={i}><span className="font-semibold text-brand-ink">{a.owner}</span>: {a.task}
                    {a.due && <span className="text-slate-500"> · by {new Date(`${a.due}T12:00:00`).toLocaleDateString(undefined, { day: 'numeric', month: 'short' })}</span>}
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      )}
      {!hasNotes && (
        <p className="mt-2 text-xs text-slate-500">
          {m.hasTranscript ? 'The AI notes were not ready. The recap lists who came.' : 'Captions were off, so there are no notes. Turn on captions in Meet to get a summary next time.'}
        </p>
      )}

      <div className="mt-3 flex flex-wrap gap-2">
        {m.attendeeEmails?.length > 0 && (
          <button className="btn-primary !py-1.5 text-sm"
            onClick={() => openComposer({ mode: 'reply', message, to: m.attendeeEmails, draftBody: m.recapBody })}>
            Send recap
          </button>
        )}
        {m.missedEmails?.length > 0 && (
          <button className="btn-secondary !py-1.5 text-sm"
            onClick={() => openComposer({ mode: 'reply', message, to: m.missedEmails, draftBody: m.catchUpBody })}>
            Catch up {m.missedEmails.length === 1 ? firstNames(missed)[0] || '1 person' : `${m.missedEmails.length} who missed it`}
          </button>
        )}
      </div>
    </section>
  )
}

const t12 = (hhmm) => {
  const [h, m] = (hhmm || '').split(':').map(Number)
  if (!Number.isFinite(h)) return hhmm
  return `${((h + 11) % 12) + 1}:${String(m || 0).padStart(2, '0')} ${h < 12 ? 'AM' : 'PM'}`
}

/**
 * Classroom actions the AI spotted in this mail ("move Saturday batch to 4 PM", "can I attend
 * online today?"). Nothing happens until the person taps; then a reply opens, ready to send.
 */
export function ClassroomCard({ message, onDone }) {
  const meet = useMeet()
  const { aiEnabled, openComposer, notify } = useMail()
  const [s, setS] = useState(null)
  const [roomId, setRoomId] = useState('')
  const [time, setTime] = useState('')
  const [busy, setBusy] = useState(false)
  const [gone, setGone] = useState(false)

  useEffect(() => {
    if (!meet?.connected || aiEnabled === false || message.lane !== 'needs_me') return undefined
    let cancelled = false
    get(`/api/messages/${message.id}/meet/suggestion`)
      .then((r) => {
        if (cancelled || !r || r.kind === 'none') return
        setS(r)
        setRoomId(r.room?.id || '')
        setTime(r.newTime || '')
      })
      .catch(() => {})
    return () => { cancelled = true }
  }, [message.id, message.lane, meet?.connected, aiEnabled])

  if (!s || gone) return null

  const move = s.kind === 'reschedule'
  const choices = (s.rooms || []).filter((r) => !move || r.repeat)
  if (choices.length === 0) return null
  const room = choices.find((r) => r.id === roomId)
  const first = (s.personName || '').split(/\s+/)[0] || s.personEmail

  async function accept() {
    setBusy(true)
    try {
      const res = move
        ? await post(`/api/messages/${message.id}/meet/reschedule`, { roomId, newTime: time })
        : await post(`/api/messages/${message.id}/meet/add`, { roomId })
      notify(res.message, 'success')
      setGone(true)
      onDone?.()
      openComposer({ mode: 'reply', message, draftBody: res.replyBody })
    } catch (e) {
      notify(e.message, 'error')
    } finally {
      setBusy(false)
    }
  }

  return (
    <section aria-label="Classroom action" className="rounded-2xl border border-amber-200 bg-amber-50/80 p-3.5">
      <p className="flex items-center gap-2 text-sm font-semibold text-amber-900">
        <IconVideo size={16} />
        {move ? `${first} asks to move a batch${s.newTime ? ` to ${t12(s.newTime)}` : ''}` : `${first} wants to attend online`}
      </p>
      {s.why && <p className="mt-1 text-xs text-amber-800">{s.why}</p>}
      <div className="mt-2.5 flex flex-wrap items-end gap-2">
        <div className="min-w-[180px] flex-1">
          <label className="label" htmlFor={`cls-room-${message.id}`}>{move ? 'Batch' : 'Room'}</label>
          <select id={`cls-room-${message.id}`} className="input" value={roomId} onChange={(e) => setRoomId(e.target.value)}>
            <option value="" disabled>Pick a room</option>
            {choices.map((r) => <option key={r.id} value={r.id}>{r.name}{scheduleLine(r) ? ` · ${scheduleLine(r)}` : ''}</option>)}
          </select>
        </div>
        {move && (
          <div>
            <label className="label" htmlFor={`cls-time-${message.id}`}>New start</label>
            <input id={`cls-time-${message.id}`} className="input" type="time" value={time} onChange={(e) => setTime(e.target.value)} required />
          </div>
        )}
      </div>
      {room && (
        <p className="mt-2 text-xs text-amber-900">
          {move
            ? `${room.name} will start at ${t12(time)} instead of ${t12(room.openTime)}, same days and length. Meet tells everyone in the room.`
            : `${s.personEmail} gets the link to ${room.name} from Magizhchi Meet.`}
        </p>
      )}
      <div className="mt-2.5 flex flex-wrap gap-2">
        <button className="btn-primary !py-1.5 text-sm" disabled={busy || !roomId || (move && !time)} onClick={accept}>
          {busy && <span className="spinner" />} {move ? 'Move batch' : `Add ${first}`}
        </button>
        <button className="btn-ghost !py-1.5 text-sm" onClick={() => setGone(true)} disabled={busy}>Not now</button>
      </div>
    </section>
  )
}
