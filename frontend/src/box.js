import { useEffect, useState } from 'react'
import { get, post } from './api.js'

// Magizhchi Box status (configured, connected, space and plan), shared by the attachment shelf and the
// Accounts page. Loaded once and refreshed after a save, so the shelf never waits on it.
let cached = null
let pending = null
const listeners = new Set()

function publish(s) {
  cached = s
  listeners.forEach((fn) => fn(s))
  return s
}

export function refreshBox() {
  pending = get('/api/box/status')
    .then(publish)
    .catch(() => publish({ configured: false, connected: false }))
    .finally(() => { pending = null })
  return pending
}

export function connectBox() {
  return post('/api/box/connect').then(publish)
}

export function useBox() {
  const [s, set] = useState(cached)
  useEffect(() => {
    listeners.add(set)
    if (!cached && !pending) refreshBox()
    return () => listeners.delete(set)
  }, [])
  return s
}

const DAY = 86400000

/** What to tell the person about their Box plan, if anything: { tone, text } or null. */
export function planNotice(usage) {
  if (!usage) return null
  if (usage.pendingPayment) return { tone: 'info', text: 'Your Magizhchi Box payment is being verified.' }
  const date = (iso) => new Date(iso).toLocaleDateString(undefined, { day: 'numeric', month: 'short' })
  if (usage.planState === 'GRACE') {
    return { tone: 'warn', text: `Your Box plan has ended. Your space is kept until ${usage.graceEndsAt ? date(usage.graceEndsAt) : 'the end of grace'}. Recharge to keep it.` }
  }
  if (usage.planState === 'EXPIRED' && usage.usedBytes > usage.limitBytes) {
    return { tone: 'error', text: 'Saving to Box is paused: you use more than the Free 5 GB. Recharge to save again.' }
  }
  if (usage.usedBytes != null && usage.limitBytes && usage.usedBytes >= usage.limitBytes) {
    return { tone: 'error', text: 'Your Box is full. Recharge or free up space to save more.' }
  }
  if (usage.planExpiresAt && new Date(usage.planExpiresAt).getTime() - Date.now() < 7 * DAY && usage.planState === 'ACTIVE') {
    return { tone: 'warn', text: `Your Box plan ends on ${date(usage.planExpiresAt)}. Recharge to keep your space.` }
  }
  return null
}
