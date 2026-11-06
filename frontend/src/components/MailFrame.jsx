// HTML mail goes into a sandboxed iframe: no scripts, no same-origin, links open in a new tab.
const INJECT =
  '<meta charset="utf-8"><meta name="referrer" content="no-referrer"><base target="_blank">' +
  '<style>html,body{margin:0;padding:0}body{padding:4px 2px;font:14px/1.55 "Plus Jakarta Sans",system-ui,"Segoe UI","Noto Sans Tamil",sans-serif;' +
  'color:#0b3a42;overflow-wrap:anywhere}img{max-width:100%;height:auto}pre{white-space:pre-wrap}a{color:#0b6b74}</style>'

export function buildSrcDoc(html) {
  const src = String(html || '')
  if (/<head[^>]*>/i.test(src)) return src.replace(/<head[^>]*>/i, (m) => m + INJECT)
  if (/<html[^>]*>/i.test(src)) return src.replace(/<html[^>]*>/i, (m) => `${m}<head>${INJECT}</head>`)
  return `<!doctype html><html><head>${INJECT}</head><body>${src}</body></html>`
}

export default function MailFrame({ html, title = 'Message body' }) {
  return (
    // An opaque-origin iframe can't be measured from outside, so it gets a tall box the reader can resize.
    <div className="overflow-hidden rounded-lg border border-slate-200 bg-white" style={{ resize: 'vertical', height: '62vh', minHeight: 260 }}>
      <iframe
        title={title}
        sandbox="allow-popups allow-popups-to-escape-sandbox"
        referrerPolicy="no-referrer"
        srcDoc={buildSrcDoc(html)}
        className="h-full w-full border-0 bg-white"
      />
    </div>
  )
}
