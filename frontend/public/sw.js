/* Magizhchi MailLook alerts: shows new mail and reminders while MailLook is closed or in the background. */

self.addEventListener('install', () => self.skipWaiting())
self.addEventListener('activate', (event) => event.waitUntil(self.clients.claim()))

self.addEventListener('push', (event) => {
  let data = {}
  try {
    data = event.data ? event.data.json() : {}
  } catch {
    data = { title: 'Magizhchi MailLook', body: event.data ? event.data.text() : '' }
  }
  event.waitUntil((async () => {
    // Always shown: browsers count a push without an alert against the site.
    await self.registration.showNotification(data.title || 'Magizhchi MailLook', {
      body: data.body || '',
      tag: data.tag || 'maillook',
      renotify: true,
      icon: '/brand/maillook-mark-512.png',
      badge: '/brand/maillook-mark-sm.png',
      data: { url: data.url || '/' },
    })
  })())
})

self.addEventListener('notificationclick', (event) => {
  event.notification.close()
  const target = new URL(event.notification.data?.url || '/', self.location.origin).href
  event.waitUntil((async () => {
    const wins = await self.clients.matchAll({ type: 'window', includeUncontrolled: true })
    for (const c of wins) {
      if (new URL(c.url).origin === self.location.origin) {
        await c.focus()
        if ('navigate' in c) return c.navigate(target)
        return undefined
      }
    }
    return self.clients.openWindow(target)
  })())
})
