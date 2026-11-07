// Times people pick for snoozes and reminders, in their own time zone.

const pad = (n) => String(n).padStart(2, '0')

/** A value for <input type="datetime-local"> (local time). */
export function localInput(d) {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function at(base, days, hour, minute = 0) {
  const d = new Date(base)
  d.setDate(d.getDate() + days)
  d.setHours(hour, minute, 0, 0)
  return d
}

/** "Tue 9:00", "Today 18:00", "Tomorrow 9:00", "14 Nov 9:00". */
export function whenLabel(iso) {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  const now = new Date()
  const time = d.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })
  const days = Math.round((at(d, 0, 0) - at(now, 0, 0)) / 86400000)
  if (days === 0) return `Today ${time}`
  if (days === 1) return `Tomorrow ${time}`
  if (days > 1 && days < 7) return `${d.toLocaleDateString([], { weekday: 'short' })} ${time}`
  return `${d.toLocaleDateString([], { day: 'numeric', month: 'short' })} ${time}`
}

/** Snooze choices from now: later today, this evening, tomorrow, the weekend, next week. */
export function snoozeChoices(now = new Date()) {
  const out = []
  const h = now.getHours()
  if (h < 17) out.push({ key: 'later', label: 'Later today', at: new Date(Math.min(now.getTime() + 3 * 3600000, at(now, 0, 20).getTime())) })
  if (h < 18) out.push({ key: 'evening', label: 'This evening', at: at(now, 0, 18) })
  out.push({ key: 'tomorrow', label: 'Tomorrow', at: at(now, 1, 9) })
  const dow = now.getDay() // 0 Sun … 6 Sat
  if (dow >= 1 && dow <= 4) out.push({ key: 'weekend', label: 'This weekend', at: at(now, 6 - dow, 9) })
  out.push({ key: 'week', label: 'Next week', at: at(now, ((8 - dow) % 7) || 7, 9) })
  return out
}

/** "Remind me if nobody replies" choices, counted from sending. */
export const FOLLOW_UP = [
  { key: '', label: 'No reminder' },
  { key: '1d', label: 'In 1 day', days: 1 },
  { key: '2d', label: 'In 2 days', days: 2 },
  { key: '3d', label: 'In 3 days', days: 3 },
  { key: '1w', label: 'In a week', days: 7 },
]

/** The moment a follow-up choice means: that many days on, at 9 in the morning. */
export function followUpAt(key, now = new Date()) {
  const f = FOLLOW_UP.find((x) => x.key === key)
  return f?.days ? at(now, f.days, 9) : null
}
