import { safeHttpUrl } from '../util.js'

// Web addresses in plain text: http(s)://… or www.…, often wrapped in <…> by mail programs.
const URL_RE = /\b(?:https?:\/\/|www\.)[^\s<>"]+/gi
// Punctuation that ends a sentence rather than the address.
const TRAILING = /[.,;:!?'")\]}>]+$/

/** "signin.aws.amazon.com/resetpassword…": the site and the start of the path, never a wall of token characters. */
export function shortUrl(href) {
  try {
    const u = new URL(href)
    const host = u.hostname.replace(/^www\./, '')
    const path = u.pathname === '/' ? '' : u.pathname
    const shown = host + (path.length > 28 ? path.slice(0, 28) + '…' : path)
    return u.search || u.hash || path.length > 28 ? shown.replace(/…?$/, '…') : shown
  } catch {
    return href.length > 48 ? href.slice(0, 48) + '…' : href
  }
}

/** Text cut into plain pieces (strings) and links ({href, label, at}). */
export function splitLinks(text) {
  const out = []
  let last = 0
  for (const m of String(text || '').matchAll(URL_RE)) {
    let raw = m[0]
    const tail = raw.match(TRAILING)
    // Keep a closing bracket that belongs to the address, like …/Foo_(bar)
    if (tail && !(tail[0] === ')' && raw.includes('('))) raw = raw.slice(0, -tail[0].length)
    const href = safeHttpUrl(raw.startsWith('www.') ? `https://${raw}` : raw)
    if (!href) continue
    if (m.index > last) out.push(text.slice(last, m.index))
    out.push({ href, label: shortUrl(href), at: m.index })
    last = m.index + raw.length
  }
  if (last < text.length) out.push(text.slice(last))
  return out
}

/** Does this link text just repeat a web address (so a short label reads better)? */
export function looksLikeUrl(text) {
  const t = String(text || '').trim()
  return t.length > 40 && /^(https?:\/\/|www\.)\S+$/i.test(t)
}

/** Plain text with its web addresses as short links that open in a new window. */
export default function LinkedText({ text }) {
  if (!text) return null
  return splitLinks(text).map((p) => (typeof p === 'string' ? p : (
    <a key={p.at} href={p.href} target="_blank" rel="noopener noreferrer" title={p.href}
      className="break-words font-medium text-peacock-700 underline decoration-peacock-300 underline-offset-2 hover:decoration-peacock-700">
      {p.label.split(/(?<=\/)/).flatMap((part, j) => (j ? [<wbr key={j} />, part] : [part]))}
    </a>
  )))
}
