import { useEffect, useState } from 'react'
import { downloadFile, openFile, post } from '../api.js'
import { showsInViewer, viewAttachment } from '../viewer.js'
import { refreshBox, useBox } from '../box.js'
import { formatSize } from '../util.js'
import { IconCloudCheck, IconCloudUp, IconDownload, IconEye, IconPaperclip } from './Icons.jsx'

// What kind of file it is, from its name and type: drives the tile colour, the label and whether the
// browser can show it directly ("Open") or it has to be saved first.
const KINDS = [
  { test: /pdf$/i, type: /pdf/, label: 'PDF', hue: ['#ff9c8f', '#d8402f', '#5e1209'], viewable: true },
  { test: /\.(png|jpe?g|gif|webp|bmp|svg|heic)$/i, type: /^image\//, label: 'Image', hue: ['#8ff0e6', '#169aa0', '#063a42'], viewable: true },
  { test: /\.(xlsx?|csv|ods)$/i, type: /sheet|excel|csv/, label: 'Sheet', hue: ['#9df0bf', '#1f9e5c', '#0a3b22'] },
  { test: /\.(docx?|odt|rtf)$/i, type: /word|document/, label: 'Document', hue: ['#a9c4ff', '#3466d6', '#10245c'] },
  { test: /\.(pptx?|odp|key)$/i, type: /presentation|powerpoint/, label: 'Slides', hue: ['#ffcf8f', '#e07514', '#5c2a04'] },
  { test: /\.(ics|vcs)$/i, type: /calendar/, label: 'Calendar', hue: ['#ffe08a', '#f2a516', '#6b3d04'] },
  { test: /\.(zip|rar|7z|tar|gz)$/i, type: /zip|compressed|archive/, label: 'Archive', hue: ['#c9d6d6', '#5f7c80', '#1d3236'] },
  { test: /\.(txt|log|md)$/i, type: /^text\/plain/, label: 'Text', hue: ['#e6eeeb', '#8aa4a6', '#2b4b53'], viewable: true },
  { test: /\.(mp4|mov|webm|mp3|wav|m4a)$/i, type: /^(video|audio)\//, label: 'Media', hue: ['#e4b8ff', '#8a3fd1', '#2f0b52'], viewable: true },
]
const OTHER = { label: 'File', hue: ['#b9cbcb', '#4f6d71', '#14292e'] }

export function kindOf(a) {
  return KINDS.find((k) => k.test.test(a.name || '') || k.type.test(a.contentType || '')) || OTHER
}

/** A lit page with a folded corner, in the file type's colour, with its label on it. */
export function FileTile({ kind }) {
  const [hi, mid, lo] = kind.hue
  const id = `ft${kind.label}`
  return (
    <svg width="38" height="46" viewBox="0 0 38 46" aria-hidden="true" className="shrink-0" style={{ filter: 'drop-shadow(0 4px 6px rgba(0,0,0,.45))' }}>
      <defs>
        <linearGradient id={`${id}b`} x1="0.1" y1="0" x2="0.9" y2="1">
          <stop offset="0" stopColor={hi} /><stop offset="0.55" stopColor={mid} /><stop offset="1" stopColor={lo} />
        </linearGradient>
        <linearGradient id={`${id}g`} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#fff" stopOpacity=".5" /><stop offset="1" stopColor="#fff" stopOpacity="0" />
        </linearGradient>
      </defs>
      <path d="M6 1h19l12 12v26a6 6 0 0 1-6 6H6a6 6 0 0 1-6-6V7a6 6 0 0 1 6-6Z" transform="translate(.5 0)" fill={`url(#${id}b)`} />
      <path d="M6.5 1.5h18v8c0 2 1 3 3 3h9V20C26 15 14 15 .5 21V7a5.5 5.5 0 0 1 6-5.5Z" fill={`url(#${id}g)`} />
      <path d="M25.5 1v8a3.5 3.5 0 0 0 3.5 3.5h8.5Z" fill="#fff" fillOpacity=".55" />
      <text x="19" y="36" textAnchor="middle" fontSize={kind.label.length > 5 ? 6.5 : 8} fontWeight="800" fill="#fff"
        style={{ letterSpacing: '.02em' }}>{kind.label.toUpperCase()}</text>
    </svg>
  )
}

/**
 * The files in this mail, shown at the top where nobody can miss them: each one with its type, size,
 * Open (when the browser can show it), Download and Save to Magizhchi Box, plus Download all and
 * Save all to Box. A file already in Box shows a green "In Box" cloud that opens Box.
 */
export default function AttachmentShelf({ messageId, attachments, notify, compact = false }) {
  const [busy, setBusy] = useState(null)
  const box = useBox()
  const [inBox, setInBox] = useState(() => new Set((attachments || []).filter((a) => a.inBox).map((a) => a.index)))
  const [boxFull, setBoxFull] = useState(null) // Box's own words when it refused for space
  useEffect(() => {
    setInBox(new Set((attachments || []).filter((a) => a.inBox).map((a) => a.index)))
    setBoxFull(null)
  }, [messageId, attachments])
  if (!attachments?.length) return null
  const path = (a) => `/api/messages/${messageId}/attachments/${a.index}`
  const total = attachments.reduce((n, a) => n + (a.size || 0), 0)
  const boxOn = !!box?.configured
  const allInBox = attachments.every((a) => inBox.has(a.index))
  const openBox = () => box?.webUrl && window.open(box.webUrl, '_blank', 'noopener')

  async function run(key, fn) {
    setBusy(key)
    try {
      await fn()
    } catch (e) {
      if (e.status === 413 && /full|plan/i.test(e.message)) setBoxFull(e.message)
      notify(e.message, 'error')
    } finally {
      setBusy(null)
    }
  }

  function saved(res) {
    setInBox((prev) => new Set([...prev, ...(res.inBox || [])]))
    notify(res.message, res.status === 'partial' ? 'error' : 'success')
    if (!box?.connected || res.saved) refreshBox()
  }

  const saveToBox = (a) => run(`b${a.index}`, async () => saved(await post(`${path(a)}/box`)))
  const saveAllToBox = () => run('box', async () => saved(await post(`/api/messages/${messageId}/box`)))

  async function saveAll() {
    await run('all', async () => {
      for (const a of attachments) await downloadFile(path(a), a.name)
    })
  }

  return (
    <section aria-label="Attachments" className={compact ? 'mt-3' : 'attach-shelf rounded-2xl p-3'}>
      {!compact && (
        <div className="mb-2.5 flex flex-wrap items-center gap-2 px-1">
          <IconPaperclip size={16} className="text-brand-primary" />
          <h2 className="whitespace-nowrap text-sm font-bold text-brand-ink">
            {attachments.length === 1 ? '1 attachment' : `${attachments.length} attachments`}
            <span className="ml-1.5 font-medium text-slate-500">{formatSize(total)}</span>
          </h2>
          <div className="ml-auto flex flex-wrap justify-end gap-1.5">
            {boxOn && (allInBox ? (
              <button className="chip !bg-emerald-50 !py-1 !font-semibold !text-emerald-800 !ring-emerald-300" onClick={openBox} title="Open Magizhchi Box">
                <IconCloudCheck size={14} /> {attachments.length > 1 ? 'All in Box' : 'In Box'}
              </button>
            ) : (
              <button className="btn-secondary !py-1 text-xs" onClick={saveAllToBox} disabled={busy != null}
                title={`Save ${attachments.length > 1 ? 'every file' : 'it'} to your Magizhchi Box`}>
                {busy === 'box' ? <span className="spinner !h-3 !w-3" /> : <IconCloudUp size={15} />}
                {attachments.length > 1 ? 'Save all to Box' : 'Save to Box'}
              </button>
            ))}
            {attachments.length > 1 && (
              <button className="btn-secondary !py-1 text-xs" onClick={saveAll} disabled={busy != null}>
                {busy === 'all' ? <span className="spinner !h-3 !w-3" /> : <IconDownload size={14} />} Download all
              </button>
            )}
          </div>
        </div>
      )}
      {boxFull && (
        <div role="alert" className="mb-2.5 flex flex-wrap items-center gap-x-3 gap-y-1 rounded-xl border border-amber-300 bg-amber-50 px-3 py-2 text-xs text-amber-900">
          <span className="min-w-0 flex-1">{boxFull}</span>
          {box?.webUrl && <a className="font-bold underline" href={box.webUrl} target="_blank" rel="noopener noreferrer">Recharge in Magizhchi Box</a>}
        </div>
      )}
      <ul className={`grid gap-2 ${attachments.length > 1 ? "sm:grid-cols-2" : ""}`}>
        {attachments.map((a) => {
          const kind = kindOf(a)
          return (
            <li key={a.index} className="file-card group flex min-w-0 items-center gap-3 rounded-xl p-2.5 pr-2">
              <FileTile kind={kind} />
              <div className="min-w-0 flex-1">
                <div className="truncate text-sm font-semibold text-brand-ink" title={a.name}>{a.name}</div>
                <div className="text-xs text-slate-500">{kind.label} · {formatSize(a.size)}</div>
              </div>
              <div className="flex shrink-0 items-center gap-1">
                {(kind.viewable || showsInViewer(a)) && (
                  <button className="icon-btn" title={`Open ${a.name}`} aria-label={`Open ${a.name}`} disabled={busy != null}
                    onClick={() => run(`o${a.index}`, () => (showsInViewer(a) ? viewAttachment(a, path(a)) : openFile(path(a), a.contentType)))}>
                    {busy === `o${a.index}` ? <span className="spinner" /> : <IconEye size={17} />}
                  </button>
                )}
                {boxOn && (inBox.has(a.index) ? (
                  <button className="icon-btn !text-emerald-600" title="In your Magizhchi Box: open Box" aria-label={`${a.name} is in Magizhchi Box. Open Box`}
                    onClick={openBox}>
                    <IconCloudCheck size={18} />
                  </button>
                ) : (
                  <button className="icon-btn" title={`Save ${a.name} to Magizhchi Box`} aria-label={`Save ${a.name} to Magizhchi Box`} disabled={busy != null}
                    onClick={() => saveToBox(a)}>
                    {busy === `b${a.index}` ? <span className="spinner" /> : <IconCloudUp size={18} />}
                  </button>
                ))}
                <button className="icon-btn !text-brand-primary" title={`Download ${a.name}`} aria-label={`Download ${a.name}`} disabled={busy != null}
                  onClick={() => run(`d${a.index}`, () => downloadFile(path(a), a.name))}>
                  {busy === `d${a.index}` ? <span className="spinner" /> : <IconDownload size={17} />}
                </button>
              </div>
            </li>
          )
        })}
      </ul>
    </section>
  )
}
