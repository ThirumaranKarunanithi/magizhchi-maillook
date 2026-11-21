// Bills from your mail: shared helpers for the Bills page and the bill card on a mail.

export const DATA_WEB = 'https://datas.magizhchi.software'

/** ₹1,180 or ₹1,180.50 (Indian grouping); other currencies as "USD 12.5". */
export function money(amount, currency = 'INR') {
  if (amount == null || amount === '') return ''
  const n = Number(amount)
  if (!Number.isFinite(n)) return String(amount)
  const cents = Math.round(n * 100) % 100 !== 0
  if (!currency || currency === 'INR') {
    return '₹' + n.toLocaleString('en-IN', { minimumFractionDigits: cents ? 2 : 0, maximumFractionDigits: 2 })
  }
  return `${currency} ${n.toLocaleString('en-IN', { maximumFractionDigits: 2 })}`
}

const asDay = (iso) => new Date(`${iso}T00:00:00`)

/** Days from today (YYYY-MM-DD) to the due date: negative when overdue, null with no date. */
export function daysLeft(dueDate, today) {
  if (!dueDate) return null
  return Math.round((asDay(dueDate) - asDay(today)) / 86400000)
}

export function shortDay(iso) {
  if (!iso) return ''
  const d = asDay(iso)
  return d.toLocaleDateString([], { weekday: 'short', day: 'numeric', month: 'short', year: d.getFullYear() === new Date().getFullYear() ? undefined : 'numeric' })
}

/** "Due tomorrow", "Overdue by 3 days", "Paid", "Auto-pay · 15 Nov". */
export function dueLabel(bill, today) {
  if (bill.kind === 'credit_note') return 'Credit note'
  if (bill.kind === 'receipt') return 'Paid (receipt)'
  if (bill.status === 'paid') return bill.paidHow === 'alert' ? 'Paid · bank alert' : bill.paidByReceipt ? 'Paid · receipt came' : 'Paid'
  const d = daysLeft(bill.dueDate, today)
  if (bill.autopay) return bill.dueDate ? `Auto-pay · ${shortDay(bill.dueDate)}` : 'Auto-pay'
  if (d == null) return 'No due date'
  if (d < 0) return d === -1 ? 'Overdue by 1 day' : `Overdue by ${-d} days`
  if (d === 0) return 'Due today'
  if (d === 1) return 'Due tomorrow'
  if (d < 7) return `Due ${shortDay(bill.dueDate)}`
  return `Due ${shortDay(bill.dueDate)}`
}

/** amber for soon, red for overdue, green for paid, grey otherwise. */
export function dueTone(bill, today) {
  if (bill.status === 'paid' || bill.kind !== 'bill') return 'paid'
  if (bill.autopay) return 'calm'
  const d = daysLeft(bill.dueDate, today)
  if (d == null) return 'calm'
  if (d < 0) return 'overdue'
  if (d <= 3) return 'soon'
  return 'calm'
}

export const TONE_CLASS = {
  overdue: 'bg-red-50 text-red-800 ring-red-200',
  soon: 'bg-amber-50 text-amber-900 ring-amber-200',
  paid: 'bg-emerald-50 text-emerald-800 ring-emerald-200',
  calm: 'bg-slate-100 text-slate-700 ring-slate-200',
}

/** A field Data wasn't sure about (below 0.8): the card asks the person to check it. */
export const unsure = (bill, field) => bill.confidence && bill.confidence[field] != null && bill.confidence[field] < 0.8

// "LiveKit Cloud" and "LiveKit", "Railway Corporation" and "Railway" are the same company.
const SUFFIX = /\b(inc|llc|ltd|limited|pvt|private|corp|corporation|company|co|pbc|cloud|payments?|technologies|services|india)\b/g
const vendorKey = (v) => (v || '').toLowerCase().replace(/\(.*?\)/g, ' ').replace(/[^a-z0-9 ]+/g, ' ').replace(SUFFIX, ' ').replace(/\s+/g, ' ').trim()

/**
 * One bill, however many mails it came in. A failed card payment sends the invoice, a "payment failed" mail and
 * "failed again" mails; a payment to confirm sends a reminder or two. Each of those was a bill of its own, so one
 * $1,703.40 LiveKit charge showed five times and the totals added it five times. Unpaid bills from the same
 * company for the same amount (and the same month, when they have a due date) become one bill, which says how many
 * mails it came in. When a receipt or a paid bill for the same company and amount came after them, they were paid:
 * they go to Paid instead of waiting to be paid again.
 */
export function foldBills(bills) {
  const keyOf = (b) => `${vendorKey(b.vendor)}|${b.currency || 'INR'}|${b.amount == null ? '' : Number(b.amount).toFixed(2)}`
  const paidAfter = new Map() // key → the newest receipt or paid bill
  for (const b of bills) {
    if (b.amount == null || !(b.kind === 'receipt' || (b.kind === 'bill' && b.status === 'paid'))) continue
    const k = keyOf(b)
    if (!paidAfter.has(k) || paidAfter.get(k).id < b.id) paidAfter.set(k, b)
  }
  const groups = new Map()
  const out = []
  for (const b of bills) {
    if (b.kind !== 'bill' || b.status !== 'due' || b.amount == null) { out.push(b); continue }
    const k = `${keyOf(b)}|${b.dueDate ? b.dueDate.slice(0, 7) : 'none'}`
    if (!groups.has(k)) { groups.set(k, [b]); out.push(k) } else groups.get(k).push(b)
  }
  return out.map((x) => {
    if (typeof x !== 'string') return x
    const list = groups.get(x)
    // The one to show: one with an invoice number and a due date, else the newest.
    const main = [...list].sort((a, b) => (!!b.invoiceNo - !!a.invoiceNo) || (!!b.dueDate - !!a.dueDate) || b.id - a.id)[0]
    const paid = paidAfter.get(keyOf(main))
    const folded = list.length > 1 ? { ...main, mails: list.length, foldedIds: list.map((b) => b.id), jump: list.find((b) => b.jump)?.jump } : main
    if (paid && paid.id > Math.min(...list.map((b) => b.id))) {
      return { ...folded, status: 'paid', paidHow: 'receipt', paidByReceipt: paid.id, jump: undefined }
    }
    return folded
  })
}

/** Groups for the Bills page, in the order they're shown. */
export function groupBills(bills, today) {
  const g = { overdue: [], week: [], later: [], undated: [], paid: [] }
  for (const b of bills) {
    if (b.status === 'paid' || b.kind !== 'bill') g.paid.push(b)
    else if (b.dueDate == null) g.undated.push(b)
    else {
      const d = daysLeft(b.dueDate, today)
      if (d < 0) g.overdue.push(b)
      else if (d <= 7) g.week.push(b)
      else g.later.push(b)
    }
  }
  g.paid.sort((a, b) => (b.paidAt || b.dueDate || '').localeCompare(a.paidAt || a.dueDate || ''))
  return g
}

/** What a bill comes to in rupees: its amount, or today's rupee value of a dollar bill (null if unknown). */
export const inrOf = (b) => (b.amount == null ? null : !b.currency || b.currency === 'INR' ? Number(b.amount) : b.amountInr != null ? Number(b.amountInr) : null)

/** "≈ ₹1,878" under a bill in another currency, or ''. */
export const inrNote = (b) => (b.currency && b.currency !== 'INR' && b.amountInr != null ? `≈ ${money(Math.round(Number(b.amountInr)))}` : '')

/** Total in rupees; bills in other currencies count at today's rate. */
export const totalInr = (list) => list.reduce((s, b) => s + (inrOf(b) ?? 0), 0)

/** A UPI payment link (opens Google Pay, PhonePe, Paytm… on a phone), for rupee bills with a UPI ID. */
export function upiLink(b) {
  if (!b.upiId || (b.currency && b.currency !== 'INR')) return null
  const p = new URLSearchParams({ pa: b.upiId, pn: (b.vendor || '').slice(0, 50), cu: 'INR' })
  if (b.amount != null) p.set('am', Number(b.amount).toFixed(2))
  const note = b.invoiceNo ? `Invoice ${b.invoiceNo}` : b.customerRef ? `Account ${b.customerRef}` : b.accountName || ''
  if (note) p.set('tn', note.slice(0, 50))
  return `upi://pay?${p.toString().replace(/\+/g, '%20')}`
}

/** On a phone a UPI link opens the payment apps; on a computer it is shown as a QR code to scan. */
export const onPhone = () => typeof navigator !== 'undefined' && /Android|iPhone|iPad|MagizhchiMailLookApp/i.test(navigator.userAgent)

/** Kinds of spending, as the summary and the editor name them. */
export const CATEGORY_LABEL = {
  internet: 'Internet',
  phone: 'Phone',
  power: 'Electricity',
  utilities: 'Gas and water',
  software: 'Software and cloud',
  insurance: 'Insurance',
  education: 'Education',
  home: 'Rent and home',
  tax: 'Tax',
  entertainment: 'Entertainment',
  shopping: 'Shopping',
  other: 'Other',
}
