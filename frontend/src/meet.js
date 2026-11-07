import { useEffect, useState } from 'react'
import { get } from './api.js'

// Magizhchi Meet status ({ configured, connected, webUrl, message }), loaded once and shared, so the
// Meet buttons simply stay hidden on a server without Meet.
let cached = null
let pending = null
const listeners = new Set()

function load() {
  pending = get('/api/meet/status')
    .catch(() => ({ configured: false, connected: false }))
    .then((s) => {
      cached = s
      listeners.forEach((fn) => fn(s))
      return s
    })
    .finally(() => { pending = null })
  return pending
}

export function useMeet() {
  const [s, set] = useState(cached)
  useEffect(() => {
    listeners.add(set)
    if (!cached && !pending) load()
    return () => listeners.delete(set)
  }, [])
  return s
}

const DAY_CODES = ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT']

/** The room's wall clock now: { day: 'SAT', minutes: 930 } in its own zone. */
function zoneNow(zone, now) {
  try {
    const parts = new Intl.DateTimeFormat('en-US', { timeZone: zone || 'UTC', weekday: 'short', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })
      .formatToParts(now)
    const get = (t) => parts.find((p) => p.type === t)?.value
    return { day: get('weekday').toUpperCase().slice(0, 3), minutes: Number(get('hour')) * 60 + Number(get('minute')) }
  } catch {
    return null
  }
}

const toMin = (hhmm) => {
  const [h, m] = (hhmm || '').split(':').map(Number)
  return Number.isFinite(h) && Number.isFinite(m) ? h * 60 + m : null
}

/**
 * Minutes until this room opens today (negative once it has started, null when it doesn't open
 * today or the time isn't known). One-off rooms use startsAt; weekly batches use their own zone.
 */
export function minutesToStart(room, now = new Date()) {
  if (room.startsAt) {
    const end = room.endsAt ? new Date(room.endsAt) : null
    if (end && end < now) return null
    const m = Math.round((new Date(room.startsAt) - now) / 60000)
    return m > 24 * 60 ? null : m
  }
  if (!room.repeat || !room.openTime) return null
  const z = zoneNow(room.zone, now)
  if (!z) return null
  const days = (room.days || '').split(',').map((d) => d.trim().slice(0, 3)).filter(Boolean)
  const today = room.repeat === 'DAILY'
    || (room.repeat === 'WEEKDAYS' && !['SAT', 'SUN'].includes(z.day))
    || (room.repeat === 'WEEKLY' && days.includes(z.day))
  if (!today) return null
  const open = toMin(room.openTime)
  const lock = toMin(room.lockTime)
  if (open == null) return null
  if (lock != null && z.minutes >= lock && lock > open) return null
  return open - z.minutes
}

/**
 * When a repeating room next opens, as "today, 4:45 PM", "tomorrow, 4:45 PM" or "Mon 9 Nov, 4:45 PM"
 * (the room's own clock). Null for one-off rooms, while a class is on, or when the days aren't known.
 */
export function nextClass(room, now = new Date()) {
  if (!room.repeat || !room.openTime) return null
  const z = zoneNow(room.zone, now)
  const open = toMin(room.openTime)
  if (!z || open == null) return null
  const days = (room.days || '').split(',').map((d) => d.trim().slice(0, 3)).filter(Boolean)
  const runs = (day) => room.repeat === 'DAILY'
    || (room.repeat === 'WEEKDAYS' && !['SAT', 'SUN'].includes(day))
    || (room.repeat === 'WEEKLY' && days.includes(day))
  const start = DAY_CODES.indexOf(z.day)
  if (start < 0) return null
  const h = Math.floor(open / 60), mm = open % 60
  const time = `${((h + 11) % 12) + 1}:${String(mm).padStart(2, '0')} ${h < 12 ? 'AM' : 'PM'}`
  for (let d = 0; d <= 7; d++) {
    if (!runs(DAY_CODES[(start + d) % 7])) continue
    if (d === 0) {
      if (z.minutes >= open) continue // on now or over for today
      return `today, ${time}`
    }
    if (d === 1) return `tomorrow, ${time}`
    const date = new Intl.DateTimeFormat('en-GB', { timeZone: room.zone || 'UTC', weekday: 'short', day: 'numeric', month: 'short' })
      .format(new Date(now.getTime() + d * 86400000))
    return `${date}, ${time}`
  }
  return null
}

/** "Saturdays 3:00–5:00 PM" style line for a weekly batch, or the one-off date. */
export function scheduleLine(room) {
  const t = (hhmm) => {
    const m = toMin(hhmm)
    if (m == null) return ''
    const h = Math.floor(m / 60), mm = m % 60
    return `${((h + 11) % 12) + 1}:${String(mm).padStart(2, '0')} ${h < 12 ? 'AM' : 'PM'}`
  }
  if (room.repeat && room.openTime) {
    const when = room.repeat === 'DAILY' ? 'Every day'
      : room.repeat === 'WEEKDAYS' ? 'Weekdays'
      : room.repeat === 'WEEKLY' && room.days
        ? room.days.split(',').map((d) => { const i = DAY_CODES.indexOf(d.trim().slice(0, 3)); return i < 0 ? d : ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'][i] }).join(', ')
        : 'Repeats'
    return `${when} · ${t(room.openTime)}${room.lockTime ? `–${t(room.lockTime)}` : ''}`
  }
  if (room.startsAt) {
    return new Date(room.startsAt).toLocaleString(undefined, { weekday: 'short', day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' })
  }
  return null
}
