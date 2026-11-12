// Opens MailLook's files in Magizhchi Viewer (https://viewer.magizhchi.software), the suite's
// shared file viewer. It runs in an isolated frame over the app and has no login of its own,
// so MailLook hands each file over:
//
//   - a mail attachment        → its bytes, fetched here with the sign-in token
//   - a picture/video/audio
//     that lives in Box        → Box's short-lived link (the browser plays a link directly)
//
// The viewer is an upgrade, never a regression: when it is not configured or not reachable,
// and always inside the phone app (which hands files to the phone's own viewer), the Open
// button does exactly what it did before.
//
// The SDK is a copy in src/vendor (from Magizhchi-Viewer/frontend/public/sdk), not a script
// loaded from the viewer's site, so no outside code runs in MailLook's page.
import './vendor/magizhchi-viewer.js'

const BASE = (import.meta.env.VITE_VIEWER_URL || '').trim().replace(/\/+$/, '')

const MEDIA = /\.(png|jpe?g|jfif|gif|webp|bmp|svg|ico|avif|mp4|webm|ogv|mov|m4v|mp3|wav|ogg|oga|m4a|aac|flac|opus|weba)$/i
const DOCS = /\.(pdf|docx|xlsx|xlsm|pptx|zip|vcf|csv|tsv|json|md|markdown|txt|log|xml|html?|css|js|jsx|ts|tsx|java|kt|py|c|h|cpp|cs|go|rs|php|rb|sql|sh|ya?ml|ini|ics|eml|srt|vtt)$/i
const MEDIA_TYPE = /^(image\/(png|jpeg|gif|webp|bmp|svg\+xml|avif)|video\/|audio\/)/i
const DOC_TYPE = /^(application\/pdf|text\/|application\/json)/i
const BYTES_LIMIT = 200 * 1024 * 1024

let ready = false         // the viewer site answered
let unavailable = false   // it failed to start once; stop trying for this visit

const inApp = () => !!window.Capacitor?.isNativePlatform?.()
const isMedia = (f) => MEDIA_TYPE.test(f.contentType || '') || MEDIA.test(f.name || '')
const isDoc = (f) => DOC_TYPE.test(f.contentType || '') || DOCS.test(f.name || '')

/** Call once at start-up: checks in the background whether the viewer site is answering, so
 *  an Open click can decide straight away. */
export function warmViewer() {
  if (!BASE || !window.MagizhchiViewer || inApp()) return
  const ctrl = new AbortController()
  const timer = setTimeout(() => ctrl.abort(), 6000)
  fetch(`${BASE}/favicon.svg`, { mode: 'no-cors', cache: 'no-store', credentials: 'omit', signal: ctrl.signal })
    .then(() => { ready = true })
    .catch(() => { ready = false })
    .finally(() => clearTimeout(timer))
}

/**
 * Will this file open in the viewer? Synchronous on purpose.
 * @param f         { name, contentType, size }
 * @param linkOnly  the file can only be handed over as a link (a Box file): pictures, video
 *                  and audio work that way, documents do not, because Box's storage refuses
 *                  script reads from other sites.
 */
export function showsInViewer(f, { linkOnly = false } = {}) {
  if (!ready || unavailable || !f) return false
  if (isMedia(f)) return true
  return !linkOnly && isDoc(f) && !(f.size > BYTES_LIMIT)
}

function saveBlob(blob, name) {
  const u = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = u; a.download = name || 'attachment'
  document.body.appendChild(a); a.click(); a.remove()
  setTimeout(() => URL.revokeObjectURL(u), 60000)
}

function show(source, { index, onDownload, onNeed, onFail }) {
  window.MagizhchiViewer.open(source, {
    base: BASE,
    index,
    timeout: 7000,
    onNeed,       // a file listed by name only: fetched when the user reaches it
    onDownload,   // MailLook saves the file itself, from its own page
    onError: () => { unavailable = true; onFail?.() },
  })
}

/**
 * Several files with next/previous, opening on one of them. Only that one is fetched now;
 * each of the others is fetched when the user reaches it.
 * @param entries  [{ f: { name, contentType, size }, load, download? }]
 *                 load()      → Promise<{ blob } | { url }>: the file's bytes or a link to it
 *                 download()  → optional: how this file is saved (default: its bytes, or its link)
 * @param index    which entry to open on
 */
export async function viewSet(entries, index) {
  const got = new Map()   // index → what load() gave, so a download does not fetch twice
  const load = async (i) => {
    if (!got.has(i)) got.set(i, await entries[i].load())
    return got.get(i)
  }
  const meta = (e) => ({ name: e.f.name || 'attachment', mimeType: e.f.contentType || '', size: e.f.size })
  const first = await load(index)
  const save = async (i) => {
    const e = entries[i]
    if (e.download) return e.download()
    const src = await load(i)
    if (src.blob) saveBlob(src.blob, e.f.name)
    else window.open(src.url, '_blank', 'noopener')
  }
  show(entries.map((e, i) => (i === index ? { ...meta(e), ...first } : meta(e))), {
    index,
    onNeed: (_, i) => load(i),
    onDownload: (_, i) => { save(i).catch(() => {}) },
    // The viewer did not start: the clicked file is already here, so at least hand it over.
    onFail: () => { save(index).catch(() => {}) },
  })
}
