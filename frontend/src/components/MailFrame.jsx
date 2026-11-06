import { useEffect, useRef, useState } from 'react'

// HTML mail goes into a sandboxed iframe. Scripts can never run (no allow-scripts, plus a CSP that forbids
// them), links open in a new tab. allow-same-origin is there only so this page can measure the letter and
// grow the frame to fit it: one scroll for the whole reading pane, no box-in-a-box.
const CSP = "default-src 'none'; img-src * data: blob:; style-src 'unsafe-inline' *; font-src * data:; media-src * data:"
const INJECT =
  `<meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="${CSP}">` +
  '<meta name="referrer" content="no-referrer"><base target="_blank">' +
  '<style>html,body{margin:0;padding:0;background:transparent}' +
  'body{padding:28px clamp(18px,4vw,40px) 32px;font:15px/1.65 "Plus Jakarta Sans",system-ui,"Segoe UI","Noto Sans Tamil",sans-serif;' +
  'color:#16323a;overflow-wrap:anywhere}img{max-width:100%;height:auto}pre{white-space:pre-wrap}' +
  'table{max-width:100%}a{color:#0b6b74}blockquote{margin:12px 0;padding-left:12px;border-left:3px solid #e6d8bd;color:#4f6d71}</style>'

export function buildSrcDoc(html) {
  const src = String(html || '')
  if (/<head[^>]*>/i.test(src)) return src.replace(/<head[^>]*>/i, (m) => m + INJECT)
  if (/<html[^>]*>/i.test(src)) return src.replace(/<html[^>]*>/i, (m) => `${m}<head>${INJECT}</head>`)
  return `<!doctype html><html><head>${INJECT}</head><body>${src}</body></html>`
}

export default function MailFrame({ html, title = 'Message body' }) {
  const ref = useRef(null)
  const [height, setHeight] = useState(240)

  useEffect(() => {
    const frame = ref.current
    if (!frame) return
    let observer
    function fit() {
      const doc = frame.contentDocument
      if (!doc?.documentElement) return
      const h = Math.max(doc.documentElement.scrollHeight, doc.body?.scrollHeight || 0)
      setHeight(Math.min(Math.max(h, 120), 20000))
    }
    function onLoad() {
      fit()
      const doc = frame.contentDocument
      if (doc?.body && 'ResizeObserver' in window) {
        observer = new ResizeObserver(fit)
        observer.observe(doc.body)
      }
      // late images
      doc?.querySelectorAll('img').forEach((img) => img.addEventListener('load', fit))
    }
    frame.addEventListener('load', onLoad)
    if (frame.contentDocument?.readyState === 'complete' && frame.contentDocument.body?.childNodes.length) onLoad()
    return () => {
      frame.removeEventListener('load', onLoad)
      observer?.disconnect()
    }
  }, [html])

  return (
    <div className="paper overflow-hidden">
      <iframe
        ref={ref}
        title={title}
        sandbox="allow-same-origin allow-popups allow-popups-to-escape-sandbox"
        referrerPolicy="no-referrer"
        srcDoc={buildSrcDoc(html)}
        className="block w-full border-0"
        style={{ height, background: 'transparent' }}
      />
    </div>
  )
}
