// Browser and phone alerts (Web Push). The service worker in /sw.js shows them while MailLook is closed.
import { get, post } from './api.js'

export function pushSupported() {
  // The desktop app has no push service; it shows alerts of its own while it runs in the tray.
  if (typeof window !== 'undefined' && window.maillookDesktop) return false
  // The Android app gets alerts through Firebase instead (native.js).
  if (typeof window !== 'undefined' && window.Capacitor?.isNativePlatform?.()) return false
  return typeof window !== 'undefined' && 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window
}

/** iPhones and iPads only allow alerts for web apps added to the home screen. */
export function needsHomeScreen() {
  const ios = /iphone|ipad|ipod/i.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1)
  const standalone = window.matchMedia?.('(display-mode: standalone)').matches || navigator.standalone
  return ios && !standalone
}

function keyBytes(base64url) {
  const pad = '='.repeat((4 - (base64url.length % 4)) % 4)
  const raw = atob((base64url + pad).replace(/-/g, '+').replace(/_/g, '/'))
  return Uint8Array.from(raw, (c) => c.charCodeAt(0))
}

async function registration() {
  const reg = (await navigator.serviceWorker.getRegistration('/')) || (await navigator.serviceWorker.register('/sw.js', { scope: '/' }))
  await navigator.serviceWorker.ready
  return reg
}

/** This browser's subscription, if it has one. */
export async function currentSubscription() {
  if (!pushSupported()) return null
  const reg = await navigator.serviceWorker.getRegistration('/')
  return reg ? reg.pushManager.getSubscription() : null
}

/** Asks for permission and registers this browser. Resolves to the server's settings. */
export async function enablePush(publicKey) {
  if (!pushSupported()) throw new Error('This browser can’t show alerts.')
  const permission = await Notification.requestPermission()
  if (permission !== 'granted') {
    throw new Error(permission === 'denied'
      ? 'Alerts are blocked for MailLook in this browser. Allow them in the site settings (the lock icon by the address), then try again.'
      : 'Alerts weren’t allowed.')
  }
  const reg = await registration()
  let sub = await reg.pushManager.getSubscription()
  if (!sub) sub = await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: keyBytes(publicKey) })
  const json = sub.toJSON()
  return post('/api/push/subscribe', { endpoint: json.endpoint, keys: json.keys })
}

export async function disablePush() {
  const sub = await currentSubscription()
  if (!sub) return get('/api/push')
  const endpoint = sub.endpoint
  try {
    await sub.unsubscribe()
  } catch {
    /* the server forgets it either way */
  }
  return post('/api/push/unsubscribe', { endpoint })
}
