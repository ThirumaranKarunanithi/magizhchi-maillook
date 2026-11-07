import { useEffect, useState } from 'react'
import { get, post } from '../api.js'
import { useMeet } from '../meet.js'
import { useMail } from '../state.jsx'
import { IconNews, IconUsers, IconVideo } from './Icons.jsx'

const cleanSubject = (s) => (s || '').replace(/^((re|fwd?)\s*:\s*)+/i, '').trim()
const pad = (n) => String(n).padStart(2, '0')
const localInput = (iso) => {
  const d = new Date(iso)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function listNames(names) {
  if (names.length <= 1) return names.join('')
  if (names.length === 2) return names.join(' and ')
  return `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`
}

/** Remembered per thread, so "Not now" stays put (or for this visit, in a private window). */
function useDismissed(key) {
  const storageKey = `mlook_hide_${key}`
  const [hidden, setHidden] = useState(() => {
    try {
      return localStorage.getItem(storageKey) === '1'
    } catch {
      return false
    }
  })
  function hide() {
    setHidden(true)
    try {
      localStorage.setItem(storageKey, '1')
    } catch {
      // nothing to remember it in
    }
  }
  return [hidden, hide]
}

/**
 * "This thread should be a call": six or more mails between three or more people and no room yet.
 * One tap opens a room for all of them and drafts the reply with the link.
 */
export function CallHint({ hint, message, onStarted }) {
  const { openComposer, notify } = useMail()
  const [hidden, hide] = useDismissed(`call_${message.threadKey || message.id}`)
  const [busy, setBusy] = useState(false)
  if (!hint || hidden) return null

  async function call() {
    const tab = window.open('', '_blank')   // opened inside the tap, so the browser allows it
    setBusy(true)
    try {
      const res = await post(`/api/messages/${message.id}/meet`, { name: cleanSubject(message.subject) || 'Quick call', emails: hint.emails })
      if (tab) {
        tab.opener = null
        tab.location.href = res.link
      }
      notify(res.message || 'Room is open', res.failed?.length ? 'error' : 'success')
      onStarted?.()
      openComposer({ mode: 'replyAll', message, to: res.invited, draftBody: res.replyBody })
    } catch (e) {
      tab?.close()
      notify(e.message, 'error')
    } finally {
      setBusy(false)
    }
  }

  return (
    <section aria-label="This thread could be a call" className="flex flex-wrap items-center gap-3 rounded-2xl border border-amber-300/70 bg-amber-50 px-3.5 py-3">
      <span className="grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-amber-500/15 text-amber-700"><IconUsers size={20} /></span>
      <div className="min-w-[12rem] flex-1">
        <p className="text-sm font-semibold text-brand-ink">Settle it on a call?</p>
        <p className="text-xs text-slate-600">
          {hint.replies} mails between {listNames(hint.names)}{hint.people > hint.names.length + 1 ? ' and others' : ''}. A short call usually ends a thread like this.
        </p>
      </div>
      <div className="flex gap-2">
        <button className="btn-ghost !py-1.5 text-sm" onClick={hide} disabled={busy}>Not now</button>
        <button className="btn-primary !py-1.5 text-sm" onClick={call} disabled={busy}>
          {busy ? <span className="spinner" /> : <IconVideo size={15} />} Call {hint.emails.length === 1 ? 'them' : `all ${hint.emails.length}`}
        </button>
      </div>
    </section>
  )
}

/**
 * The weekly Scrum digest: this mail is one of a run of reports (same subject, new date). One tap
 * turns the week's reports, their PDFs and the standups in Meet into one mail to the team.
 */
export function DigestCard({ message }) {
  const { aiEnabled, openComposer, notify } = useMail()
  const meet = useMeet()
  const [offer, setOffer] = useState(null)
  const [roomId, setRoomId] = useState('')
  const [busy, setBusy] = useState(false)
  const [hidden, hide] = useDismissed(`digest_${message.id}`)

  useEffect(() => {
    if (aiEnabled === false) return undefined
    let cancelled = false
    get(`/api/messages/${message.id}/digest`)
      .then((r) => {
        if (cancelled || !r?.available) return
        setOffer(r)
        setRoomId(r.roomId || '')
      })
      .catch(() => {})
    return () => { cancelled = true }
  }, [message.id, aiEnabled])

  if (!offer || hidden) return null

  async function make() {
    setBusy(true)
    try {
      const res = await post(`/api/messages/${message.id}/digest`, { roomId: roomId || null, days: 7 })
      openComposer({ mode: 'new', message, to: res.to, subject: res.subject, draftBody: res.body })
    } catch (e) {
      notify(e.message, 'error')
    } finally {
      setBusy(false)
    }
  }

  const rooms = meet?.connected ? offer.rooms || [] : []
  return (
    <section aria-label="Weekly digest" className="rounded-2xl border border-peacock-300/60 bg-white/80 p-3.5">
      <div className="flex items-start gap-3">
        <span className="grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-peacock-50 text-peacock-700"><IconNews size={20} /></span>
        <div className="min-w-0 flex-1">
          <p className="text-sm font-semibold text-brand-ink">Weekly digest of “{offer.title}”</p>
          <p className="text-xs text-slate-500">
            {offer.reports} reports from {offer.senders === 1 ? '1 person' : `${offer.senders} people`} lately.
            One mail to the team: what each person did, does next and is stuck on.
          </p>
        </div>
      </div>
      <div className="mt-3 flex flex-wrap items-end gap-2">
        {rooms.length > 0 && (
          <div className="min-w-[12rem] flex-1">
            <label className="label" htmlFor={`digest-room-${message.id}`}>Add the standups from</label>
            <select id={`digest-room-${message.id}`} className="input !py-1.5 text-sm" value={roomId} onChange={(e) => setRoomId(e.target.value)}>
              <option value="">No standups, just the reports</option>
              {rooms.map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}
            </select>
          </div>
        )}
        <div className="flex gap-2">
          <button className="btn-ghost !py-1.5 text-sm" onClick={hide} disabled={busy}>Not now</button>
          <button className="btn-primary !py-1.5 text-sm" onClick={make} disabled={busy}>
            {busy && <span className="spinner" />} {busy ? 'Reading the week…' : 'Write the digest'}
          </button>
        </div>
      </div>
    </section>
  )
}

/**
 * Placement drive (or webinar, orientation...): the AI read the date, length, seats and shortlist
 * from the thread. One tap makes the event room in Meet and invites every applicant.
 */
export function EventCard({ message, onBooked }) {
  const { aiEnabled, openComposer, notify } = useMail()
  const meet = useMeet()
  const [ev, setEv] = useState(null)
  const [name, setName] = useState('')
  const [at, setAt] = useState('')
  const [minutes, setMinutes] = useState(60)
  const [seats, setSeats] = useState('')
  const [showList, setShowList] = useState(false)
  const [busy, setBusy] = useState(false)
  const [done, setDone] = useState(null)
  const [hidden, hide] = useDismissed(`event_${message.threadKey || message.id}`)

  useEffect(() => {
    if (!meet?.connected || aiEnabled === false) return undefined
    let cancelled = false
    get(`/api/messages/${message.id}/meet/event`)
      .then((r) => {
        if (cancelled || !r?.isEvent) return
        setEv(r)
        setName(r.title || '')
        setAt(r.startAt ? localInput(r.startAt) : '')
        setMinutes(r.minutes || 60)
        setSeats(r.seats ? String(r.seats) : r.applicants?.length ? String(r.applicants.length) : '')
      })
      .catch(() => {})
    return () => { cancelled = true }
  }, [message.id, meet?.connected, aiEnabled])

  if (!ev || hidden) return null
  const people = ev.applicants || []
  const startAt = at ? new Date(at) : null
  const past = startAt && startAt < new Date()
  const seatCount = Number(seats)

  async function create(e) {
    e.preventDefault()
    setBusy(true)
    try {
      const res = await post(`/api/messages/${message.id}/meet/event`, {
        name: name.trim(), emails: people, startAt: startAt.toISOString(), minutes: Number(minutes), seats: seatCount || null,
      })
      setDone(res)
      notify(res.message || 'Event room ready', res.failed?.length ? 'error' : 'success')
      onBooked?.()
      openComposer({ mode: 'reply', message, draftBody: res.replyBody })
    } catch (err) {
      notify(err.message, 'error')
    } finally {
      setBusy(false)
    }
  }

  if (done) return null
  return (
    <section aria-label="Host this event in Magizhchi Meet" className="rounded-2xl border border-peacock-300/60 bg-white/80 p-3.5">
      <div className="flex items-start gap-3">
        <span className="grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-peacock-50 text-peacock-700"><IconUsers size={20} /></span>
        <div className="min-w-0 flex-1">
          <p className="text-sm font-semibold text-brand-ink">Host this event online</p>
          <p className="text-xs text-slate-500">
            An event room: you and the panel on stage, {people.length ? `${people.length} applicant${people.length === 1 ? '' : 's'}` : 'everyone'} watching.
            Magizhchi Meet emails each one the link and a calendar invite.
          </p>
        </div>
      </div>
      <form onSubmit={create} className="mt-3 space-y-2.5">
        <div>
          <label className="label" htmlFor={`ev-name-${message.id}`}>Event</label>
          <input id={`ev-name-${message.id}`} className="input" value={name} onChange={(e) => setName(e.target.value)} maxLength={120} required />
        </div>
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-[minmax(0,1fr)_7rem_6rem]">
          <div className="col-span-2 sm:col-span-1">
            <label className="label" htmlFor={`ev-at-${message.id}`}>Starts</label>
            <input id={`ev-at-${message.id}`} className="input" type="datetime-local" value={at} onChange={(e) => setAt(e.target.value)} required />
          </div>
          <div>
            <label className="label" htmlFor={`ev-len-${message.id}`}>Length</label>
            <select id={`ev-len-${message.id}`} className="input" value={minutes} onChange={(e) => setMinutes(e.target.value)}>
              {[...new Set([30, 60, 90, 120, 180, 240, Number(minutes)])].sort((a, b) => a - b).map((m) => (
                <option key={m} value={m}>{m < 60 ? `${m} min` : `${+(m / 60).toFixed(1)} hr`}</option>
              ))}
            </select>
          </div>
          <div>
            <label className="label" htmlFor={`ev-seats-${message.id}`}>Seats</label>
            <input id={`ev-seats-${message.id}`} className="input" type="number" min="1" max="10000" inputMode="numeric"
              value={seats} onChange={(e) => setSeats(e.target.value)} />
          </div>
        </div>
        <div>
          <button type="button" className="text-xs font-semibold text-peacock-700 hover:underline" aria-expanded={showList}
            onClick={() => setShowList((s) => !s)}>
            {people.length ? `${people.length} applicant${people.length === 1 ? '' : 's'} found in the thread` : 'No applicant addresses found in the thread'}
            {people.length > 0 && (showList ? ' · hide' : ' · show')}
          </button>
          {showList && people.length > 0 && (
            <ul className="scroll-thin mt-1.5 flex max-h-28 flex-wrap gap-1.5 overflow-y-auto">
              {people.map((p) => <li key={p} className="chip !bg-slate-100 !text-slate-700">{p}</li>)}
            </ul>
          )}
          {seatCount > 0 && people.length > seatCount && (
            <p className="mt-1 text-xs text-amber-800">More applicants ({people.length}) than seats ({seatCount}). Meet doesn't turn people away yet, so plan for everyone.</p>
          )}
        </div>
        {past && <p className="text-xs text-red-700">That time has already passed.</p>}
        <div className="flex flex-wrap items-center gap-2">
          <button className="btn-primary !py-1.5 text-sm" disabled={busy || !name.trim() || !people.length || !at || past}>
            {busy && <span className="spinner" />} Create the event room
          </button>
          <button type="button" className="btn-ghost !py-1.5 text-sm" onClick={hide} disabled={busy}>Not now</button>
        </div>
      </form>
    </section>
  )
}
