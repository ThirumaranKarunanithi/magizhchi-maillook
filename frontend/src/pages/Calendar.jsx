import { useCallback, useEffect, useMemo, useState } from 'react'
import { get, qs } from '../api.js'
import { useMail } from '../state.jsx'
import { IconBack, IconCalendar, IconCheck, IconChevron, IconMenu, IconRefresh, IconX } from '../components/Icons.jsx'

const DAY = 86400000
const HOUR_PX = 44
const VIEW_KEY = 'mlook_cal_view'

function startOfWeek(d) {
  const x = new Date(d.getFullYear(), d.getMonth(), d.getDate())
  const dow = (x.getDay() + 6) % 7 // Monday first
  x.setDate(x.getDate() - dow)
  return x
}
const addDays = (d, n) => new Date(d.getFullYear(), d.getMonth(), d.getDate() + n)
const sameDay = (a, b) => a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate()
const time = (d) => d.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })

function rangeLabel(from, days) {
  const to = addDays(from, days - 1)
  const sameMonth = from.getMonth() === to.getMonth()
  const a = from.toLocaleDateString([], { day: 'numeric', ...(sameMonth ? {} : { month: 'short' }) })
  const b = to.toLocaleDateString([], { day: 'numeric', month: 'short', year: 'numeric' })
  return `${a} – ${b}`
}

function readView() {
  try {
    return localStorage.getItem(VIEW_KEY) || 'week'
  } catch {
    return 'week'
  }
}

/** Events that overlap, side by side: each gets a column and the number of columns in its cluster. */
function layout(events) {
  const sorted = [...events].sort((a, b) => a.s - b.s || b.e - a.e)
  const out = []
  let cluster = []
  let clusterEnd = -Infinity
  const flush = () => {
    const cols = []
    for (const ev of cluster) {
      let c = cols.findIndex((end) => end <= ev.s)
      if (c < 0) { c = cols.length; cols.push(0) }
      cols[c] = ev.e
      out.push({ ...ev, col: c })
    }
    for (const o of out.slice(out.length - cluster.length)) o.cols = cols.length
    cluster = []
  }
  for (const ev of sorted) {
    if (ev.s >= clusterEnd && cluster.length) flush()
    cluster.push(ev)
    clusterEnd = Math.max(clusterEnd, ev.e)
  }
  if (cluster.length) flush()
  return out
}

function Detail({ item, webUrl, onClose }) {
  if (!item) return null
  const s = new Date(item.start || item.dueAt)
  const e = item.end ? new Date(item.end) : null
  return (
    <div className="modal-back fixed inset-0 z-50 flex items-end justify-center sm:items-center sm:p-4" onMouseDown={(ev) => ev.target === ev.currentTarget && onClose()}>
      <div className="card w-full max-w-md !rounded-b-none p-4 sm:!rounded-2xl" role="dialog" aria-modal="true" aria-label={item.title}>
        <div className="flex items-start gap-2">
          <span className="mt-1.5 h-3 w-3 shrink-0 rounded-full" style={{ background: item.color || (item.dueAt ? '#f5b320' : '#0e7c86') }} />
          <h2 className="min-w-0 flex-1 text-lg font-bold leading-snug text-brand-ink">{item.title}</h2>
          <button className="icon-btn" onClick={onClose} aria-label="Close"><IconX size={16} /></button>
        </div>
        <p className="mt-1 text-sm text-slate-700">
          {item.dueAt ? <>Task due {s.toLocaleDateString([], { weekday: 'long', day: 'numeric', month: 'long' })}, {time(s)}</>
            : item.allDay ? <>{s.toLocaleDateString([], { weekday: 'long', day: 'numeric', month: 'long' })} · all day</>
              : <>{s.toLocaleDateString([], { weekday: 'long', day: 'numeric', month: 'long' })}, {time(s)}{e ? ` – ${time(e)}` : ''}</>}
        </p>
        {item.recurring && <p className="mt-0.5 text-xs text-slate-500">Repeats</p>}
        {item.location && <p className="mt-2 text-sm"><span className="text-slate-500">Where: </span>{item.location}</p>}
        {item.organizer && <p className="mt-1 text-sm"><span className="text-slate-500">Organiser: </span>{item.organizer}</p>}
        {item.response && item.response !== 'ACCEPTED' && <p className="mt-1 text-sm"><span className="text-slate-500">Your answer: </span>{item.response.toLowerCase().replace('_', ' ')}</p>}
        {item.priority && <p className="mt-1 text-sm"><span className="text-slate-500">Priority: </span>{item.priority.toLowerCase()}</p>}
        {item.description && <p className="mt-3 max-h-48 overflow-y-auto whitespace-pre-wrap text-sm text-slate-700">{item.description}</p>}
        {webUrl && (
          <a className="btn-secondary mt-4" href={webUrl} target="_blank" rel="noreferrer">Open in Magizhchi PA ↗</a>
        )}
      </div>
    </div>
  )
}

/** Your Magizhchi PA calendar, read here: the week as a grid on wide screens, a day-by-day list on phones. */
export default function Calendar() {
  const { pa, setMenuOpen } = useMail()
  const [anchor, setAnchor] = useState(() => startOfWeek(new Date()))
  const [view, setView] = useState(readView)
  const [data, setData] = useState(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const [open, setOpen] = useState(null)
  const [now, setNow] = useState(() => new Date())
  const [narrow, setNarrow] = useState(() => window.matchMedia('(max-width: 767px)').matches)

  useEffect(() => {
    const mq = window.matchMedia('(max-width: 767px)')
    const on = () => setNarrow(mq.matches)
    mq.addEventListener('change', on)
    const t = setInterval(() => setNow(new Date()), 60000)
    return () => { mq.removeEventListener('change', on); clearInterval(t) }
  }, [])

  const shown = narrow ? 'agenda' : view
  const days = shown === 'agenda' ? 14 : 7

  const load = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const from = anchor
      const to = addDays(anchor, days)
      setData(await get(`/api/pa/agenda${qs({ from: from.toISOString(), to: to.toISOString() })}`))
    } catch (e) {
      setError(e.message)
    } finally {
      setLoading(false)
    }
  }, [anchor, days])

  useEffect(() => {
    if (pa?.connected) load()
    else if (pa) setLoading(false)
  }, [load, pa])

  function pickView(v) {
    setView(v)
    try { localStorage.setItem(VIEW_KEY, v) } catch { /* fine */ }
  }

  const dayList = useMemo(() => Array.from({ length: days }, (_, i) => addDays(anchor, i)), [anchor, days])

  // Per day: timed events, all-day events and tasks due.
  const byDay = useMemo(() => {
    const map = dayList.map((d) => ({ day: d, timed: [], allDay: [], tasks: [] }))
    for (const ev of data?.events || []) {
      const s = new Date(ev.start)
      const e = new Date(ev.end || ev.start)
      map.forEach((slot) => {
        const d0 = slot.day.getTime()
        const d1 = d0 + DAY
        if (e.getTime() <= d0 || s.getTime() >= d1) return
        if (ev.allDay || e - s >= DAY) slot.allDay.push(ev)
        else slot.timed.push({ ...ev, s: Math.max(s.getTime(), d0), e: Math.min(Math.max(e.getTime(), s.getTime() + 15 * 60000), d1) })
      })
    }
    for (const t of data?.tasks || []) {
      const d = new Date(t.dueAt)
      const slot = map.find((x) => sameDay(x.day, d))
      if (slot) slot.tasks.push(t)
    }
    map.forEach((slot) => { slot.timed = layout(slot.timed) })
    return map
  }, [data, dayList])

  const hours = useMemo(() => {
    let lo = 8
    let hi = 19
    for (const slot of byDay) for (const ev of slot.timed) {
      lo = Math.min(lo, new Date(ev.s).getHours())
      hi = Math.max(hi, new Date(ev.e - 1).getHours() + 1)
    }
    return { lo: Math.max(0, lo), hi: Math.min(24, hi) }
  }, [byDay])

  const step = (dir) => setAnchor((a) => addDays(a, dir * days))
  const isThisWeek = sameDay(anchor, startOfWeek(new Date()))
  const total = (data?.events?.length || 0) + (data?.tasks?.length || 0)

  return (
    <section className="slab rise-3d d1 flex min-h-0 flex-1 flex-col overflow-hidden max-lg:rounded-none">
      <header className="flex flex-wrap items-center gap-2 border-b border-slate-200/70 px-3 py-2.5 sm:px-4">
        <button className="icon-btn lg:hidden" onClick={() => setMenuOpen(true)} aria-label="Open menu"><IconMenu /></button>
        <IconCalendar size={20} className="hidden text-brand-primary sm:block" />
        <div className="min-w-0 flex-1">
          <h1 className="truncate text-base font-bold leading-tight">Calendar</h1>
          <p className="truncate text-[11px] text-slate-500">From Magizhchi PA · {rangeLabel(anchor, days)}</p>
        </div>
        <div className="flex items-center gap-1">
          <button className="icon-btn" onClick={() => step(-1)} aria-label="Earlier"><IconBack size={17} /></button>
          <button className="btn-secondary !px-2.5 !py-1 text-xs" onClick={() => setAnchor(startOfWeek(new Date()))} disabled={isThisWeek}>Today</button>
          <button className="icon-btn" onClick={() => step(1)} aria-label="Later"><IconChevron size={17} /></button>
          <button className="icon-btn" onClick={load} disabled={loading || !pa?.connected} title="Refresh"><IconRefresh size={16} className={loading ? 'animate-spin' : ''} /></button>
        </div>
        {!narrow && (
          <div className="flex rounded-lg border border-slate-200 p-0.5 text-xs font-semibold" role="group" aria-label="Layout">
            {[['week', 'Week'], ['agenda', 'List']].map(([k, l]) => (
              <button key={k} aria-pressed={view === k} onClick={() => pickView(k)}
                className={`rounded-md px-2.5 py-1 ${view === k ? 'bg-peacock-50 text-peacock-800' : 'text-slate-600 hover:text-brand-ink'}`}>{l}</button>
            ))}
          </div>
        )}
        {pa?.webUrl && <a className="hidden text-xs font-semibold text-peacock-700 hover:underline sm:inline" href={pa.webUrl} target="_blank" rel="noreferrer">Open PA ↗</a>}
      </header>

      {pa && !pa.connected ? (
        <div className="flex flex-1 flex-col items-center justify-center p-8 text-center">
          <IconCalendar size={44} className="text-slate-400" />
          <p className="mt-4 text-lg font-bold text-brand-ink">Your calendar lives in Magizhchi PA</p>
          <p className="mx-auto mt-1 max-w-sm text-sm text-slate-500">{pa.message || 'Sign in with Magizhchi ID to see your PA calendar here.'}</p>
        </div>
      ) : error ? (
        <div className="p-8 text-center text-sm">
          <p className="text-red-700">{error}</p>
          <button className="btn-secondary mt-3" onClick={load}>Try again</button>
        </div>
      ) : shown === 'agenda' ? (
        <div className="scroll-thin min-h-0 flex-1 overflow-y-auto px-3 py-3 sm:px-5">
          {loading && !data && <p className="flex items-center gap-2 text-sm text-slate-500"><span className="spinner" /> Loading your calendar…</p>}
          {data && !total && (
            <p className="py-10 text-center text-sm text-slate-500">Nothing on your calendar for these two weeks. Add a mail to it from “Add to PA”.</p>
          )}
          {data && total > 0 && byDay.filter((s) => s.timed.length || s.allDay.length || s.tasks.length).map((slot) => {
            const today = sameDay(slot.day, now)
            return (
              <div key={slot.day.toISOString()} className="mb-4">
                <h2 className={`sticky top-0 z-10 -mx-1 bg-[var(--slab-bg)] px-1 py-1 text-xs font-bold uppercase tracking-wider ${today ? 'text-brand-primary' : 'text-slate-500'}`}>
                  {today ? 'Today · ' : ''}{slot.day.toLocaleDateString([], { weekday: 'long', day: 'numeric', month: 'short' })}
                </h2>
                <ul className="space-y-1.5">
                  {[...slot.allDay.map((e) => ({ ...e, k: 'a' })), ...slot.timed.map((e) => ({ ...e, k: 't' })), ...slot.tasks.map((t) => ({ ...t, k: 'k' }))]
                    .sort((a, b) => (a.k === 'a' ? -1 : b.k === 'a' ? 1 : (a.s || new Date(a.dueAt).getTime()) - (b.s || new Date(b.dueAt).getTime())))
                    .map((it) => (
                      <li key={`${it.k}-${it.id}-${it.start || it.dueAt}`}>
                        <button className="flex w-full items-center gap-3 rounded-xl border border-slate-200 bg-white/80 px-3 py-2 text-left hover:border-slate-300" onClick={() => setOpen(it)}>
                          <span className="w-16 shrink-0 text-xs font-semibold text-slate-600 num">
                            {it.k === 'a' ? 'All day' : time(new Date(it.k === 'k' ? it.dueAt : it.s))}
                          </span>
                          <span className="h-8 w-1 shrink-0 rounded-full" style={{ background: it.k === 'k' ? '#f5b320' : it.color || '#0e7c86' }} />
                          <span className="min-w-0 flex-1">
                            <span className="flex items-center gap-1.5 truncate text-sm font-semibold text-brand-ink">
                              {it.k === 'k' && <IconCheck size={13} className="shrink-0 text-amber-600" />}{it.title}
                            </span>
                            <span className="block truncate text-xs text-slate-500">
                              {it.k === 'k' ? 'Task due' : it.k === 't' ? `until ${time(new Date(it.e))}` : ''}{it.location ? ` · ${it.location}` : ''}
                            </span>
                          </span>
                        </button>
                      </li>
                    ))}
                </ul>
              </div>
            )
          })}
        </div>
      ) : (
        <div className="scroll-thin min-h-0 flex-1 overflow-auto">
          <div className="min-w-[720px]">
            {/* day headings, all-day events and tasks */}
            <div className="sticky top-0 z-20 grid border-b border-slate-200/80 bg-[var(--slab-bg)]" style={{ gridTemplateColumns: '52px repeat(7, minmax(0, 1fr))' }}>
              <div />
              {byDay.map((slot) => {
                const today = sameDay(slot.day, now)
                return (
                  <div key={slot.day.toISOString()} className="min-w-0 border-l border-slate-200/70 px-1.5 pb-1.5 pt-2">
                    <div className={`text-center text-[11px] font-semibold uppercase tracking-wide ${today ? 'text-brand-primary' : 'text-slate-500'}`}>
                      {slot.day.toLocaleDateString([], { weekday: 'short' })}
                      <span className={`ml-1 inline-flex h-6 min-w-6 items-center justify-center rounded-full px-1 text-sm num ${today ? 'bg-brand-gold font-bold text-[#4a2a04]' : 'text-brand-ink'}`}>{slot.day.getDate()}</span>
                    </div>
                    <div className="mt-1 space-y-0.5">
                      {slot.allDay.map((ev) => (
                        <button key={ev.id + ev.start} onClick={() => setOpen(ev)} className="block w-full truncate rounded px-1.5 py-0.5 text-left text-[11px] font-semibold text-white"
                          style={{ background: ev.color || '#0e7c86' }}>{ev.title}</button>
                      ))}
                      {slot.tasks.map((t) => (
                        <button key={t.id} onClick={() => setOpen(t)} className="flex w-full items-center gap-1 truncate rounded bg-amber-50 px-1.5 py-0.5 text-left text-[11px] font-semibold text-amber-900 ring-1 ring-amber-200">
                          <IconCheck size={10} className="shrink-0" /><span className="truncate">{t.title}</span>
                        </button>
                      ))}
                    </div>
                  </div>
                )
              })}
            </div>
            {/* the hours */}
            <div className="relative grid" style={{ gridTemplateColumns: '52px repeat(7, minmax(0, 1fr))', height: (hours.hi - hours.lo) * HOUR_PX }}>
              <div className="relative">
                {Array.from({ length: hours.hi - hours.lo }, (_, i) => (
                  <span key={i} className="absolute right-2 -translate-y-1/2 text-[10px] text-slate-500 num" style={{ top: i * HOUR_PX }}>
                    {i === 0 ? '' : new Date(2000, 0, 1, hours.lo + i).toLocaleTimeString([], { hour: 'numeric' })}
                  </span>
                ))}
              </div>
              {byDay.map((slot) => {
                const d0 = slot.day.getTime() + hours.lo * 3600000
                const today = sameDay(slot.day, now)
                return (
                  <div key={slot.day.toISOString()} className={`relative border-l border-slate-200/70 ${today ? 'bg-amber-50/40' : ''}`}>
                    {Array.from({ length: hours.hi - hours.lo }, (_, i) => (
                      <div key={i} className="absolute inset-x-0 border-t border-slate-200/60" style={{ top: i * HOUR_PX }} />
                    ))}
                    {today && now.getHours() >= hours.lo && now.getHours() < hours.hi && (
                      <div className="absolute inset-x-0 z-10 h-0.5 bg-red-500" style={{ top: ((now - d0) / 3600000) * HOUR_PX }}>
                        <span className="absolute -left-1 -top-[3px] h-2 w-2 rounded-full bg-red-500" />
                      </div>
                    )}
                    {slot.timed.map((ev) => {
                      const top = Math.max(0, ((ev.s - d0) / 3600000) * HOUR_PX)
                      const h = Math.max(18, ((ev.e - ev.s) / 3600000) * HOUR_PX - 2)
                      return (
                        <button key={ev.id + ev.start} onClick={() => setOpen(ev)} title={`${ev.title} · ${time(new Date(ev.s))}`}
                          className="absolute overflow-hidden rounded-md px-1.5 py-0.5 text-left text-[11px] leading-tight text-white shadow-sm ring-1 ring-black/5 hover:brightness-110"
                          style={{ top, height: h, left: `calc(${(ev.col / ev.cols) * 100}% + 2px)`, width: `calc(${100 / ev.cols}% - 4px)`, background: ev.color || '#0e7c86' }}>
                          <span className="block truncate font-semibold">{ev.title}</span>
                          {h > 30 && <span className="block truncate opacity-90 num">{time(new Date(ev.s))}{ev.location ? ` · ${ev.location}` : ''}</span>}
                        </button>
                      )
                    })}
                  </div>
                )
              })}
            </div>
          </div>
          {data && !total && <p className="py-6 text-center text-sm text-slate-500">A clear week. Add a mail to your calendar from “Add to PA”.</p>}
        </div>
      )}
      <Detail item={open} webUrl={data?.webUrl || pa?.webUrl} onClose={() => setOpen(null)} />
    </section>
  )
}
