export const LANES = [
  { key: 'needs_me', label: 'Needs me', hint: 'People waiting on you' },
  { key: 'fyi', label: 'For my info', hint: 'Worth knowing, no reply needed' },
  { key: 'can_wait', label: 'Can wait', hint: 'Newsletters, receipts, the rest' },
]

export const LANE_LABEL = Object.fromEntries(LANES.map((l) => [l.key, l.label]))

/** The person's own sections by lane ("s12"), kept current by the app state: {name, icon, color}. */
export const SECTIONS = {}

/** A lane's or a section's name, for messages like "Moved to …". */
export function laneName(key) {
  return LANE_LABEL[key] || (SECTIONS[key] ? `${SECTIONS[key].icon} ${SECTIONS[key].name}` : key)
}

/** Mailbox views the list can show: a lane of the inbox, or a folder. */
export const VIEWS = {
  needs_me: { title: 'Needs me', lane: 'needs_me', folder: 'INBOX' },
  fyi: { title: 'For my info', lane: 'fyi', folder: 'INBOX' },
  can_wait: { title: 'Can wait', lane: 'can_wait', folder: 'INBOX' },
  all: { title: 'All mail', lane: 'all', folder: 'INBOX' },
  sent: { title: 'Sent', lane: 'all', folder: 'SENT' },
  archive: { title: 'Archive', lane: 'all', folder: 'ARCHIVE' },
  spam: { title: 'Spam', lane: 'all', folder: 'SPAM' },
  trash: { title: 'Trash', lane: 'all', folder: 'TRASH' },
  drafts: { title: 'Drafts', drafts: true },
  snoozed: { title: 'Snoozed', lane: 'all', folder: 'SNOOZED' },
  waiting: { title: 'Waiting on others', lane: 'all', folder: 'WAITING' },
}

/** The signature block MailLook puts under new mail: the usual "-- " line, then the text. */
export function signatureBlock(signature) {
  const s = (signature || '').trim()
  return s ? `\n\n-- \n${s}` : ''
}

const DAY = 24 * 60 * 60 * 1000

/** Outlook-style short time: 10:42 today, Yesterday, Mon this week, 6 Nov, 6 Nov 2025. */
export function shortTime(iso) {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  const now = new Date()
  const startToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const t = d.getTime()
  if (t >= startToday) return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
  if (t >= startToday - DAY) return 'Yesterday'
  if (t >= startToday - 6 * DAY) return d.toLocaleDateString([], { weekday: 'short' })
  if (d.getFullYear() === now.getFullYear()) return d.toLocaleDateString([], { day: 'numeric', month: 'short' })
  return d.toLocaleDateString([], { day: 'numeric', month: 'short', year: 'numeric' })
}

/** "5 min ago" style, for sync times. */
export function relativeTime(iso) {
  if (!iso) return 'never'
  const t = new Date(iso).getTime()
  if (Number.isNaN(t)) return ''
  const s = Math.round((Date.now() - t) / 1000)
  if (s < 45) return 'just now'
  const m = Math.round(s / 60)
  if (m < 60) return `${m} min ago`
  const h = Math.round(m / 60)
  if (h < 24) return `${h} h ago`
  const days = Math.round(h / 24)
  return days === 1 ? 'yesterday' : `${days} days ago`
}

export function fullDate(iso) {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  return d.toLocaleString([], { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' })
}

export function formatSize(bytes) {
  if (bytes == null) return ''
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`
  if (bytes < 1024 ** 3) return `${(bytes / 1024 ** 2).toFixed(1)} MB`
  // Box space: whole numbers stay whole (5 GB, 2 TB), the rest get one decimal (3.8 GB)
  const [n, unit] = bytes < 1024 ** 4 ? [bytes / 1024 ** 3, 'GB'] : [bytes / 1024 ** 4, 'TB']
  return `${Number.isInteger(Math.round(n * 10) / 10) ? Math.round(n) : n.toFixed(1)} ${unit}`
}

export const EMAIL_RE = /^[^\s@,;<>]+@[^\s@,;<>]+\.[^\s@,;<>]+$/

/** Splits pasted text (lines, commas, semicolons, "Name <a@b>" forms) into unique addresses. */
export function splitAddresses(text) {
  const out = []
  const seen = new Set()
  for (let part of String(text || '').split(/[\n,;]+/)) {
    part = part.trim()
    if (!part) continue
    const angle = /<([^>]+)>/.exec(part)
    const addr = (angle ? angle[1] : part).trim()
    const key = addr.toLowerCase()
    if (seen.has(key)) continue
    seen.add(key)
    out.push(addr)
  }
  return out
}

export function senderName(m) {
  return m?.fromName || m?.fromEmail || '(unknown sender)'
}

/** Only follow http(s) links handed back by the server (never javascript: and friends). */
export function safeHttpUrl(url) {
  try {
    const u = new URL(url)
    return u.protocol === 'http:' || u.protocol === 'https:' ? u.href : null
  } catch {
    return null
  }
}

/** Plain text of a mail body, for quoting in replies. DOMParser documents never run scripts or load images. */
export function bodyAsText(detail) {
  if (detail?.bodyText) return detail.bodyText
  if (detail?.bodyHtml) {
    try {
      const doc = new DOMParser().parseFromString(detail.bodyHtml, 'text/html')
      doc.querySelectorAll('style,script,head,title').forEach((n) => n.remove())
      doc.querySelectorAll('br').forEach((n) => n.replaceWith('\n'))
      doc.querySelectorAll('p,div,tr,li,h1,h2,h3,h4,blockquote').forEach((n) => n.append('\n'))
      return (doc.body?.textContent || '').replace(/\n{3,}/g, '\n\n').trim()
    } catch {
      return ''
    }
  }
  return detail?.snippet || ''
}

export function happinessTone(score) {
  if (score == null) return 'muted'
  if (score >= 70) return 'good'
  if (score >= 40) return 'busy'
  return 'over'
}

export const PRESET_COLORS = ['#0e7c86', '#0b5563', '#f5b320', '#b45309', '#c2410c', '#be185d', '#7c3aed', '#2563eb', '#15803d', '#4f6d71']
