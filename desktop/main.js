// Magizhchi MailLook for the desktop (Electron).
//
// A window around the MailLook web app, plus what a browser tab can't do:
//   • it stays in the tray when closed, and starts with Windows, so alerts arrive all day
//   • the "Needs me" count sits on the taskbar icon
//   • it can be the computer's mail app: mailto: links open a new mail here
//   • links in mail open in the default browser; sign-in pages stay in the window
//   • right-click with spelling suggestions, remembered window size, and updates by itself
const { app, BrowserWindow, Menu, Tray, Notification, dialog, shell, nativeImage, ipcMain, session } = require('electron')
const path = require('path')
const fs = require('fs')
const { classify, host } = require('./links.js')
const { parseMailto, mailtoFromArgv } = require('./mailto.js')

let autoUpdater = null
try { autoUpdater = require('electron-updater').autoUpdater } catch { /* running from source */ }

const APP_ID = 'software.magizhchi.maillook'
const APP_URL = (process.env.MAILLOOK_APP_URL || 'https://maillook.magizhchi.software').replace(/\/+$/, '')
// The API answers the Google and Microsoft sign-in callbacks, so it is part of the app too.
const API_HOSTS = (process.env.MAILLOOK_API_HOSTS || 'maillookback.magizhchi.software').split(',').map((s) => s.trim()).filter(Boolean)
const START_HIDDEN = process.argv.includes('--hidden')
const ICON = path.join(__dirname, 'icon.png')

// Settings, sign-in and cache live in %APPDATA%\Magizhchi MailLook (not the package's npm name).
app.setName('Magizhchi MailLook')
app.setPath('userData', path.join(app.getPath('appData'), 'Magizhchi MailLook'))

let win = null
let tray = null
let quitting = false
let needsMe = 0
let pendingMailto = mailtoFromArgv(process.argv)

// ── settings (window size, start with Windows) ───────────────────────────────

const SETTINGS_FILE = () => path.join(app.getPath('userData'), 'settings.json')
let settings = {}
function loadSettings() {
  try { settings = JSON.parse(fs.readFileSync(SETTINGS_FILE(), 'utf8')) || {} } catch { settings = {} }
}
function saveSettings() {
  try { fs.writeFileSync(SETTINGS_FILE(), JSON.stringify(settings, null, 2)) } catch { /* read-only profile */ }
}

let boundsTimer = null
function rememberBounds() {
  clearTimeout(boundsTimer)
  boundsTimer = setTimeout(() => {
    if (!win || win.isDestroyed()) return
    settings.maximized = win.isMaximized()
    if (!win.isMaximized() && !win.isMinimized()) settings.bounds = win.getBounds()
    saveSettings()
  }, 400)
}

function startWithWindows(on) {
  settings.openAtLogin = !!on
  saveSettings()
  if (!app.isPackaged) return
  try { app.setLoginItemSettings({ openAtLogin: !!on, args: ['--hidden'] }) } catch { /* unsupported */ }
}

// ── the window ────────────────────────────────────────────────────────────────

function showWin() {
  if (!win) return
  if (win.isMinimized()) win.restore()
  win.show()
  win.focus()
}

function send(channel, payload) {
  if (win && !win.isDestroyed()) win.webContents.send(channel, payload)
}

/** A new mail in MailLook, from a mailto: link or the tray. */
function compose(fields) {
  showWin()
  send('maillook:compose', fields || {})
}

function openInApp(p) {
  showWin()
  send('maillook:open', p || '/')
}

function loadApp(url = APP_URL) {
  // Always ask for a fresh index.html, so a new MailLook release shows up on the next start.
  win.loadURL(url, { extraHeaders: 'pragma: no-cache\ncache-control: no-cache\n' })
}

const where = (u) => classify(u, APP_URL, API_HOSTS)

function createWindow() {
  const b = settings.bounds || {}
  win = new BrowserWindow({
    width: b.width || 1320,
    height: b.height || 860,
    x: b.x, y: b.y,
    minWidth: 380,
    minHeight: 520,
    show: false,
    backgroundColor: '#fbf8f2',
    title: 'Magizhchi MailLook',
    icon: ICON,
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      sandbox: false,
      spellcheck: true,
    },
  })
  if (settings.maximized) win.maximize()
  win.once('ready-to-show', () => { if (!START_HIDDEN) win.show() })
  win.on('resize', rememberBounds)
  win.on('move', rememberBounds)

  // X keeps MailLook in the tray so alerts keep coming. Quit is in the tray and the File menu.
  win.on('close', (e) => {
    if (quitting) return
    e.preventDefault()
    win.hide()
    if (!settings.toldAboutTray) {
      settings.toldAboutTray = true
      saveSettings()
      toast('MailLook is still running', 'It stays in the tray so you get alerts. Right-click the tray icon to quit.')
    }
  })

  const wc = win.webContents
  wc.on('will-navigate', (e, url) => {
    const kind = where(url)
    if (kind === 'app' || kind === 'sign-in') return
    e.preventDefault()
    if (kind === 'browser') shell.openExternal(url)
    else if (kind === 'mailto') compose(parseMailto(url))
  })
  wc.setWindowOpenHandler(({ url }) => {
    const kind = where(url)
    if (kind === 'browser') { shell.openExternal(url); return { action: 'deny' } }
    if (kind === 'mailto') { compose(parseMailto(url)); return { action: 'deny' } }
    // A blank tab the app fills in a moment later, or a sign-in page: a small window we watch.
    return {
      action: 'allow',
      overrideBrowserWindowOptions: {
        show: false, parent: win, width: 520, height: 720, autoHideMenuBar: true,
        icon: ICON, backgroundColor: '#ffffff', webPreferences: { contextIsolation: true, sandbox: true },
      },
    }
  })
  wc.on('did-create-window', watchPopup)

  wc.on('did-fail-load', (_e, code, _desc, url, isMainFrame) => {
    if (!isMainFrame || code === -3) return // -3: replaced by another navigation
    win.loadFile(path.join(__dirname, 'offline.html'), { query: { retry: where(url) === 'app' ? url : APP_URL } })
  })
  wc.on('render-process-gone', (_e, details) => {
    console.error('[maillook] page stopped:', details && details.reason, details && details.exitCode)
    if (!quitting) setTimeout(() => loadApp(), 1000)
  })
  wc.on('context-menu', (_e, params) => contextMenu(wc, params))
  wc.on('did-finish-load', () => {
    if (pendingMailto) { compose(parseMailto(pendingMailto)); pendingMailto = null }
  })

  loadApp()
}

/**
 * A window the app opened. MailLook opens a blank one inside the click (so it isn't blocked) and then
 * points it somewhere: a Meet room or a Box file goes to the browser and the window closes; Google or
 * Microsoft sign-in shows here, and when it comes back to MailLook the main window picks it up.
 */
function watchPopup(child) {
  const decide = (url) => {
    if (!url || url === 'about:blank') return
    const kind = where(url)
    if (kind === 'sign-in' || (kind === 'app' && API_HOSTS.includes(host(url)))) {
      if (!child.isVisible()) child.show()
      return
    }
    if (kind === 'app') loadApp(url)
    else if (kind === 'browser') shell.openExternal(url)
    else if (kind === 'mailto') compose(parseMailto(url))
    setImmediate(() => { if (!child.isDestroyed()) child.destroy() })
  }
  child.webContents.on('did-start-navigation', (details) => {
    // Electron 31 passes an event object carrying url and isMainFrame.
    const url = details?.url
    if (details && details.isMainFrame === false) return
    decide(url)
  })
  child.webContents.on('context-menu', (_e, params) => contextMenu(child.webContents, params))
  // Nothing loaded after a while: a blank window nobody will fill.
  setTimeout(() => {
    if (!child.isDestroyed() && !child.isVisible()) child.destroy()
  }, 20000)
}

// ── right-click ──────────────────────────────────────────────────────────────

function contextMenu(wc, p) {
  const items = []
  if (p.misspelledWord) {
    for (const s of (p.dictionarySuggestions || []).slice(0, 5)) {
      items.push({ label: s, click: () => wc.replaceMisspelling(s) })
    }
    if (!p.dictionarySuggestions?.length) items.push({ label: 'No suggestions', enabled: false })
    items.push({ label: 'Add to dictionary', click: () => wc.session.addWordToSpellCheckerDictionary(p.misspelledWord) })
    items.push({ type: 'separator' })
  }
  if (p.linkURL && /^(https?|mailto):/i.test(p.linkURL)) {
    if (/^mailto:/i.test(p.linkURL)) items.push({ label: 'Write to this address', click: () => compose(parseMailto(p.linkURL)) })
    else items.push({ label: 'Open link in browser', click: () => shell.openExternal(p.linkURL) })
    items.push({ label: 'Copy link', click: () => require('electron').clipboard.writeText(p.linkURL.replace(/^mailto:/i, '')) })
    items.push({ type: 'separator' })
  }
  const f = p.editFlags || {}
  if (p.isEditable) {
    items.push({ role: 'undo', enabled: f.canUndo }, { role: 'redo', enabled: f.canRedo }, { type: 'separator' })
    items.push({ role: 'cut', enabled: f.canCut }, { role: 'copy', enabled: f.canCopy }, { role: 'paste', enabled: f.canPaste })
    items.push({ role: 'selectAll' })
  } else if (p.selectionText && p.selectionText.trim()) {
    items.push({ role: 'copy' })
  }
  if (p.mediaType === 'image' && p.srcURL && /^https?:/i.test(p.srcURL)) {
    items.push({ label: 'Save image as…', click: () => wc.downloadURL(p.srcURL) })
  }
  while (items.length && items[items.length - 1].type === 'separator') items.pop()
  if (items.length) Menu.buildFromTemplate(items).popup()
}

// ── tray, menu, badge, alerts ────────────────────────────────────────────────

function toast(title, body, path) {
  try {
    if (!Notification.isSupported()) return
    const n = new Notification({ title, body, icon: ICON, silent: false })
    n.on('click', () => (path ? openInApp(path) : showWin()))
    n.show()
  } catch { /* alerts unavailable */ }
}

function createTray() {
  tray = new Tray(nativeImage.createFromPath(ICON).resize({ width: 16, height: 16 }))
  tray.on('click', showWin)
  tray.on('double-click', showWin)
  rebuildTray()
}

function rebuildTray() {
  if (!tray) return
  tray.setToolTip(needsMe > 0 ? `Magizhchi MailLook: ${needsMe} need${needsMe === 1 ? 's' : ''} you` : 'Magizhchi MailLook')
  const items = [
    { label: 'Open MailLook', click: showWin },
    { label: needsMe > 0 ? `Needs me (${needsMe})` : 'Needs me', click: () => openInApp('/?view=needs_me') },
    { label: 'New mail', click: () => compose({}) },
    { type: 'separator' },
  ]
  if (app.isPackaged) {
    items.push({ label: 'Start with Windows', type: 'checkbox', checked: settings.openAtLogin !== false, click: (m) => startWithWindows(m.checked) })
  }
  items.push(
    { label: updateReady ? 'Restart to update' : 'Check for updates…', click: () => (updateReady ? installUpdate() : checkForUpdates(true)) },
    { type: 'separator' },
    { label: 'Quit MailLook', click: quit },
  )
  tray.setContextMenu(Menu.buildFromTemplate(items))
}

function buildMenu() {
  const template = [
    {
      label: 'File',
      submenu: [
        { label: 'New mail', accelerator: 'CmdOrCtrl+N', click: () => compose({}) },
        { type: 'separator' },
        { label: 'Close window', accelerator: 'CmdOrCtrl+W', click: () => win && win.hide() },
        { label: 'Quit MailLook', accelerator: 'CmdOrCtrl+Q', click: quit },
      ],
    },
    { role: 'editMenu' },
    {
      label: 'View',
      submenu: [
        { label: 'Needs me', accelerator: 'CmdOrCtrl+1', click: () => openInApp('/?view=needs_me') },
        { label: 'For my info', accelerator: 'CmdOrCtrl+2', click: () => openInApp('/?view=fyi') },
        { label: 'Can wait', accelerator: 'CmdOrCtrl+3', click: () => openInApp('/?view=can_wait') },
        { label: 'Today', accelerator: 'CmdOrCtrl+T', click: () => openInApp('/today') },
        { type: 'separator' },
        { role: 'reload' },
        { role: 'resetZoom' }, { role: 'zoomIn' }, { role: 'zoomOut' },
        { type: 'separator' },
        { role: 'togglefullscreen' },
        ...(app.isPackaged ? [] : [{ role: 'toggleDevTools' }]),
      ],
    },
    {
      label: 'Help',
      submenu: [
        { label: 'Check for updates…', click: () => checkForUpdates(true) },
        { label: 'Make MailLook my mail app', click: makeDefaultMailApp },
        { type: 'separator' },
        { label: `Magizhchi MailLook ${app.getVersion()}`, enabled: false },
      ],
    },
  ]
  Menu.setApplicationMenu(Menu.buildFromTemplate(template))
}

function makeDefaultMailApp() {
  const ok = app.setAsDefaultProtocolClient('mailto')
  if (process.platform === 'win32') {
    // Windows lets only the person choose the default mail app, in Settings.
    dialog.showMessageBox(win, {
      type: 'info', title: 'Mail links',
      message: 'Choose MailLook in Windows Settings',
      detail: 'Windows opens its Default apps page. Under "MAILTO", pick Magizhchi MailLook. After that, mail links in any app open a new mail here.',
      buttons: ['Open Settings', 'Not now'], defaultId: 0, cancelId: 1,
    }).then((r) => { if (r.response === 0) shell.openExternal('ms-settings:defaultapps') })
  } else if (!ok) {
    dialog.showMessageBox(win, { type: 'warning', message: 'MailLook could not become the mail app here.' })
  }
}

function setBadge(n, dataUrl) {
  needsMe = Math.max(0, n | 0)
  rebuildTray()
  try {
    if (process.platform === 'win32' && win) {
      win.setOverlayIcon(needsMe > 0 && dataUrl ? nativeImage.createFromDataURL(dataUrl) : null, needsMe > 0 ? `${needsMe} need you` : '')
    } else {
      app.setBadgeCount(needsMe)
    }
  } catch { /* taskbar unavailable */ }
}

function quit() {
  quitting = true
  app.quit()
}

// ── updates (latest.yml beside the installer on Hostinger) ───────────────────

let updateReady = false
let updateAsked = false

function installUpdate() {
  quitting = true
  try { autoUpdater.quitAndInstall() } catch { app.quit() }
}

function checkForUpdates(asked) {
  if (updateReady) return installUpdate()
  if (!autoUpdater || !app.isPackaged) {
    if (asked) dialog.showMessageBox(win, { type: 'info', title: 'Updates', message: `Magizhchi MailLook ${app.getVersion()}`, detail: 'This copy runs from source. The installed app updates itself.' })
    return
  }
  updateAsked = !!asked
  autoUpdater.checkForUpdates().catch((e) => {
    if (!updateAsked) return
    updateAsked = false
    dialog.showMessageBox(win, { type: 'warning', title: 'Updates', message: 'Could not check for updates', detail: String((e && e.message) || e) })
  })
}

function initUpdates() {
  if (!autoUpdater || !app.isPackaged) return
  autoUpdater.autoDownload = true
  autoUpdater.autoInstallOnAppQuit = true
  autoUpdater.on('update-available', (info) => {
    if (!updateAsked) return
    updateAsked = false
    dialog.showMessageBox(win, { type: 'info', title: 'Updates', message: `Version ${info?.version || 'newer'} is downloading`, detail: 'You will be told when it is ready.' })
  })
  autoUpdater.on('update-not-available', () => {
    if (!updateAsked) return
    updateAsked = false
    dialog.showMessageBox(win, { type: 'info', title: 'Updates', message: 'You are up to date', detail: `Magizhchi MailLook ${app.getVersion()} is the newest version.` })
  })
  autoUpdater.on('update-downloaded', () => {
    updateReady = true
    rebuildTray()
    toast('MailLook update ready', 'It installs when MailLook restarts. Click to restart now.')
  })
  autoUpdater.on('error', (e) => console.warn('[update]', e && e.message))
  checkForUpdates(false)
  setInterval(() => checkForUpdates(false), 6 * 60 * 60 * 1000)
}

// ── what the web app asks for ────────────────────────────────────────────────

ipcMain.on('maillook:version', (e) => { e.returnValue = app.getVersion() })
ipcMain.on('maillook:badge', (e, n) => {
  // The number is drawn here once per change; the overlay must be an image on Windows.
  setBadge(n, badgeImage(n))
})
ipcMain.on('maillook:notify', (_e, { title, body, path: p }) => {
  if (win && win.isVisible() && win.isFocused()) return // they're looking at it already
  toast(title || 'Magizhchi MailLook', body || '', p || '/')
})

/** A small red disc with the count, for the taskbar overlay. Drawn as a 32×32 bitmap. */
function badgeImage(n) {
  if (!(n > 0)) return null
  const size = 32
  const buf = Buffer.alloc(size * size * 4)
  const label = n > 99 ? '99+' : String(n)
  const r = 15.5
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const d = Math.hypot(x + 0.5 - 16, y + 0.5 - 16)
      const a = Math.max(0, Math.min(1, r - d))
      const i = (y * size + x) * 4
      // BGRA: a warm red (#d93a2b)
      buf[i] = 0x2b; buf[i + 1] = 0x3a; buf[i + 2] = 0xd9; buf[i + 3] = Math.round(a * 255)
    }
  }
  drawDigits(buf, size, label)
  return nativeImage.createFromBitmap(buf, { width: size, height: size }).toDataURL()
}

// 3×5 pixel digits, scaled up, in white.
const GLYPHS = {
  0: '111101101101111', 1: '010110010010111', 2: '111001111100111', 3: '111001111001111', 4: '101101111001001',
  5: '111100111001111', 6: '111100111101111', 7: '111001001001001', 8: '111101111101111', 9: '111101111001111', '+': '000010111010000',
}
function drawDigits(buf, size, label) {
  const scale = label.length >= 3 ? 2 : label.length === 2 ? 3 : 4
  const w = label.length * 3 * scale + (label.length - 1) * scale
  const h = 5 * scale
  let x0 = Math.round((size - w) / 2)
  const y0 = Math.round((size - h) / 2)
  for (const ch of label) {
    const g = GLYPHS[ch]
    if (!g) continue
    for (let gy = 0; gy < 5; gy++) {
      for (let gx = 0; gx < 3; gx++) {
        if (g[gy * 3 + gx] !== '1') continue
        for (let sy = 0; sy < scale; sy++) {
          for (let sx = 0; sx < scale; sx++) {
            const i = ((y0 + gy * scale + sy) * size + (x0 + gx * scale + sx)) * 4
            buf[i] = 255; buf[i + 1] = 255; buf[i + 2] = 255; buf[i + 3] = 255
          }
        }
      }
    }
    x0 += 4 * scale
  }
}

// ── start ────────────────────────────────────────────────────────────────────

if (!app.requestSingleInstanceLock()) {
  app.quit()
} else {
  app.on('second-instance', (_e, argv) => {
    const link = mailtoFromArgv(argv)
    if (link) compose(parseMailto(link))
    else showWin()
  })
  // macOS hands mail links over this way.
  app.on('open-url', (e, url) => {
    e.preventDefault()
    if (win) compose(parseMailto(url))
    else pendingMailto = url
  })

  app.setAppUserModelId(APP_ID)

  app.whenReady().then(() => {
    loadSettings()
    if (app.isPackaged && settings.openAtLogin === undefined) startWithWindows(true)
    try {
      const want = ['en-IN', 'en-GB', 'en-US']
      const have = session.defaultSession.availableSpellCheckerLanguages || []
      const langs = want.filter((l) => have.includes(l))
      if (langs.length) session.defaultSession.setSpellCheckerLanguages(langs)
    } catch { /* spellcheck unavailable */ }
    buildMenu()
    createWindow()
    createTray()
    initUpdates()
  })

  app.on('before-quit', () => { quitting = true })
  app.on('window-all-closed', () => { /* stays in the tray */ })
  app.on('activate', showWin)
}
