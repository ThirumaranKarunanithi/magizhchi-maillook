import { useEffect, useState } from 'react'
import { get, post, qs } from '../api.js'
import { useMail } from '../state.jsx'
import { safeHttpUrl } from '../util.js'
import { IconAlert, IconCalendar, IconCheck, IconVideo } from './Icons.jsx'

const TZ = (() => {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || ''
  } catch {
    return ''
  }
})()

const ANSWERS = [
  { key: 'accepted', label: 'Yes', done: 'You said yes' },
  { key: 'tentative', label: 'Maybe', done: 'You said maybe' },
  { key: 'declined', label: 'No', done: 'You said no' },
]

function when(inv) {
  const s = new Date(inv.start)
  const e = new Date(inv.end)
  const day = (d) => d.toLocaleDateString([], { weekday: 'short', day: 'numeric', month: 'short' })
  const time = (d) => d.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })
  if (inv.allDay) {
    const last = new Date(e.getTime() - 1)
    return s.toDateString() === last.toDateString() ? `${day(s)} · all day` : `${day(s)} – ${day(last)} · all day`
  }
  return s.toDateString() === e.toDateString() ? `${day(s)} · ${time(s)} – ${time(e)}` : `${day(s)} ${time(s)} – ${day(e)} ${time(e)}`
}

/** A meeting invite in the mail: when, where, what clashes in your PA calendar, and Yes / Maybe / No. */
export default function InviteCard({ message }) {
  const { notify } = useMail()
  const [inv, setInv] = useState(null)
  const [busy, setBusy] = useState(null)
  const [comment, setComment] = useState('')
  const [note, setNote] = useState(false)

  useEffect(() => {
    if (!message.invite) return undefined
    let live = true
    get(`/api/messages/${message.id}/invite${qs({ tz: TZ })}`).then((r) => live && setInv(r)).catch(() => live && setInv({ found: false }))
    return () => { live = false }
  }, [message.id, message.invite])

  if (!message.invite || !inv?.found) return null

  async function answer(key) {
    setBusy(key)
    try {
      const res = await post(`/api/messages/${message.id}/invite/respond${qs({ tz: TZ })}`, { answer: key, comment: comment.trim() || null })
      setInv((v) => ({ ...v, replied: { answer: res.answer, at: res.at, paEventId: res.paEventId } }))
      setNote(false)
      setComment('')
      notify(res.message, 'success')
    } catch (e) {
      notify(e.message, 'error')
    } finally {
      setBusy(null)
    }
  }

  async function remove() {
    setBusy('remove')
    try {
      const res = await post(`/api/messages/${message.id}/invite/remove`)
      notify(res.message, 'success')
      setInv((v) => ({ ...v, replied: v.replied ? { ...v.replied, paEventId: null } : v.replied, removed: true }))
    } catch (e) {
      notify(e.message, 'error')
    } finally {
      setBusy(null)
    }
  }

  const replied = inv.replied?.answer
  const done = ANSWERS.find((a) => a.key === replied)
  const join = safeHttpUrl(inv.joinUrl)
  const who = inv.organizer?.name || inv.organizer?.email

  return (
    <section className={`rounded-xl border p-3 text-sm ${inv.cancelled ? 'border-red-200 bg-red-50/60' : 'border-peacock-200 bg-white/80'}`} aria-label="Meeting invite">
      <div className="flex items-start gap-3">
        <div className="flex h-11 w-11 shrink-0 flex-col items-center justify-center rounded-lg bg-peacock-600 text-white">
          <span className="text-[10px] font-semibold uppercase leading-none">{new Date(inv.start).toLocaleDateString([], { month: 'short' })}</span>
          <span className="text-lg font-bold leading-tight num">{new Date(inv.start).getDate()}</span>
        </div>
        <div className="min-w-0 flex-1">
          <p className="font-bold text-brand-ink">
            {inv.cancelled && <span className="mr-1 text-red-700">Cancelled:</span>}
            {inv.title || 'Meeting'}
          </p>
          <p className={`text-xs ${inv.cancelled ? 'text-red-800 line-through' : 'text-slate-700'}`}>{when(inv)}</p>
          {inv.location && !safeHttpUrl(inv.location) && <p className="truncate text-xs text-slate-500">{inv.location}</p>}
          {who && <p className="truncate text-xs text-slate-500">{inv.youOrganised ? 'You organised this' : `From ${who}`}{inv.attendees > 1 ? ` · ${inv.attendees} people invited` : ''}</p>}
        </div>
        {join && !inv.cancelled && (
          <a href={join} target="_blank" rel="noreferrer" className="btn-secondary shrink-0 !px-2.5 !py-1 text-xs"><IconVideo size={14} /> Join</a>
        )}
      </div>

      {!inv.cancelled && inv.clashes?.length > 0 && (
        <p className="mt-2 flex items-start gap-1.5 rounded-lg bg-amber-50 px-2 py-1.5 text-xs text-amber-900">
          <IconAlert size={13} className="mt-0.5 shrink-0" />
          <span>Clashes with {inv.clashes.map((c) => `“${c.title}”`).join(', ')} in your PA calendar.</span>
        </p>
      )}
      {!inv.cancelled && inv.calendarChecked && !inv.clashes?.length && !inv.past && (
        <p className="mt-2 flex items-center gap-1.5 text-xs text-emerald-800"><IconCheck size={13} /> You're free then.</p>
      )}

      {inv.cancelled ? (
        inv.paConnected && !inv.removed && (
          <div className="mt-2 flex items-center gap-2 text-xs">
            <span className="text-red-900">The organizer cancelled it.</span>
            <button className="btn-secondary ml-auto !px-2.5 !py-1 text-xs" onClick={remove} disabled={!!busy}>
              {busy === 'remove' && <span className="spinner" />} Remove from PA calendar
            </button>
          </div>
        )
      ) : inv.past ? (
        <p className="mt-2 text-xs text-slate-500">This meeting is over.</p>
      ) : inv.canAnswer ? (
        <>
          <div className="mt-2.5 flex flex-wrap items-center gap-1.5">
            <span className="mr-1 text-xs font-semibold text-slate-600">{done ? `${done.done}.` : 'Going?'}</span>
            {ANSWERS.map((a) => (
              <button key={a.key} type="button" onClick={() => answer(a.key)} disabled={!!busy || replied === a.key}
                className={`rounded-full px-3 py-1 text-xs font-semibold ring-1 transition-colors ${replied === a.key
                  ? 'bg-peacock-600 text-white ring-peacock-600'
                  : 'bg-white text-slate-700 ring-slate-300 hover:ring-peacock-400'}`}>
                {busy === a.key ? <span className="spinner" /> : a.label}
              </button>
            ))}
            {!note && <button type="button" className="text-xs font-semibold text-peacock-700 hover:underline" onClick={() => setNote(true)}>Add a note</button>}
          </div>
          {note && (
            <input className="input mt-2 !py-1 text-xs" value={comment} onChange={(e) => setComment(e.target.value)} maxLength={1000} autoFocus
              placeholder="A note for the organizer, sent with your answer" aria-label="Note for the organizer" />
          )}
          <p className="mt-1.5 text-[11px] text-slate-500">
            <IconCalendar size={11} className="mr-1 inline align-[-1px]" />
            {inv.paConnected ? 'Yes or Maybe also puts it in your Magizhchi PA calendar. ' : ''}{who ? `${who} gets your answer.` : ''}
          </p>
        </>
      ) : null}
    </section>
  )
}
