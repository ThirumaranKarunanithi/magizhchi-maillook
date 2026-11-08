import { get } from './api.js'

// Magizhchi Forms in MailLook: is it set up on this server, and does this person have Forms?
let status = null
export function formsStatus(fresh = false) {
  if (!status || fresh) status = get('/api/forms/status').catch(() => ({ configured: false }))
  return status
}

export const FORMS_WEB = (import.meta.env.VITE_FORMS_WEB || 'https://forms.magizhchi.software').replace(/\/+$/, '')

export const STATUS_LABEL = {
  submitted: 'New',
  pending_approval: 'Waiting for approval',
  approved: 'Approved',
  rejected: 'Rejected',
}

export const STATUS_TONE = {
  pending_approval: 'bg-amber-50 text-amber-800 ring-amber-200',
  approved: 'bg-emerald-50 text-emerald-800 ring-emerald-200',
  rejected: 'bg-red-50 text-red-800 ring-red-200',
  submitted: 'bg-peacock-50 text-peacock-800 ring-peacock-200',
}

export function shortWhen(iso) {
  if (!iso) return ''
  const d = new Date(iso)
  const today = new Date()
  if (d.toDateString() === today.toDateString()) return d.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })
  return d.toLocaleDateString([], { day: 'numeric', month: 'short' })
}

/** "Not linked" from Forms: the person has never opened Magizhchi Forms, so it has no account for them. */
export const notLinked = (e) => e?.status === 404
