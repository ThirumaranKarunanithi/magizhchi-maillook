// What the MailLook web app may ask of the desktop app. Nothing else from Node or Electron is exposed.
const { contextBridge, ipcRenderer } = require('electron')

// A request that arrives before the web app is listening (a mailto: link that launched the app, or an
// alert clicked while it was still signing in) waits here until it is.
const waiting = { compose: [], open: [] }
const handlers = { compose: null, open: null }

function deliver(kind, payload) {
  if (handlers[kind]) handlers[kind](payload)
  else waiting[kind].push(payload)
}

ipcRenderer.on('maillook:compose', (_e, fields) => deliver('compose', JSON.stringify(fields || {})))
ipcRenderer.on('maillook:open', (_e, path) => deliver('open', String(path || '/')))

function listen(kind, fn) {
  if (typeof fn !== 'function') return () => {}
  handlers[kind] = fn
  const queued = waiting[kind].splice(0)
  queued.forEach((p) => fn(p))
  return () => {
    if (handlers[kind] === fn) handlers[kind] = null
  }
}

contextBridge.exposeInMainWorld('maillookDesktop', {
  version: ipcRenderer.sendSync('maillook:version'),
  platform: process.platform,
  /** Unread "Needs me" mail, shown on the taskbar and the tray. */
  setBadge: (n) => ipcRenderer.send('maillook:badge', Number(n) || 0),
  /** A desktop alert; clicking it brings MailLook forward on `path`. */
  notify: (title, body, path) => ipcRenderer.send('maillook:notify', { title: String(title || ''), body: String(body || ''), path: String(path || '/') }),
  /** fn(json): a new mail to open, from a mailto: link or the tray's "New mail". */
  onCompose: (fn) => listen('compose', fn),
  /** fn(path): a page to show, from a clicked alert or the tray. */
  onOpen: (fn) => listen('open', fn),
})
