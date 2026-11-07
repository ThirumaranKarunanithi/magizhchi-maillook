import { useEffect, useState } from 'react'

// "Install MailLook": Chrome, Edge and Android offer an install prompt once the page qualifies; it has to
// be caught as it arrives and shown later, from a button. iPhone and iPad have no prompt: the person adds
// it from Safari's Share menu, so we explain that instead.

let deferred = null
const listeners = new Set()
const publish = () => listeners.forEach((fn) => fn())

if (typeof window !== 'undefined') {
  window.addEventListener('beforeinstallprompt', (e) => {
    e.preventDefault()
    deferred = e
    publish()
  })
  window.addEventListener('appinstalled', () => {
    deferred = null
    publish()
  })
  // The service worker makes MailLook installable and carries alerts; registering it asks for nothing.
  if ('serviceWorker' in navigator && window.isSecureContext) {
    window.addEventListener('load', () => {
      navigator.serviceWorker.getRegistration('/').then((r) => r || navigator.serviceWorker.register('/sw.js', { scope: '/' })).catch(() => {})
    })
  }
}

function standalone() {
  try {
    // The Android app counts as installed.
    if (window.Capacitor?.isNativePlatform?.()) return true
    return window.matchMedia('(display-mode: standalone)').matches || window.navigator.standalone === true
  } catch {
    return false
  }
}

function isIos() {
  const ua = navigator.userAgent || ''
  return /iphone|ipad|ipod/i.test(ua) || (/macintosh/i.test(ua) && navigator.maxTouchPoints > 1)
}

/** { canPrompt, ios, installed, install() }: whether and how MailLook can be installed here. */
export function useInstall() {
  const [, tick] = useState(0)
  useEffect(() => {
    const fn = () => tick((n) => n + 1)
    listeners.add(fn)
    return () => listeners.delete(fn)
  }, [])
  const installed = standalone()
  return {
    installed,
    canPrompt: !installed && !!deferred,
    ios: !installed && isIos(),
    async install() {
      if (!deferred) return false
      const e = deferred
      deferred = null
      publish()
      e.prompt()
      const choice = await e.userChoice.catch(() => null)
      return choice?.outcome === 'accepted'
    },
  }
}
