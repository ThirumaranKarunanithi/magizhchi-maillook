import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { downloadFile, get, openFile, post, qs } from '../api.js'
import { refreshBox, useBox } from '../box.js'
import { useMail } from '../state.jsx'
import { formatSize, senderName } from '../util.js'
import { FileTile, kindOf } from '../components/AttachmentShelf.jsx'
import { IconCloudCheck, IconCloudUp, IconDownload, IconEye, IconMenu, IconPaperclip, IconSearch, IconX } from '../components/Icons.jsx'

const KINDS = [
  { key: '', label: 'All' },
  { key: 'pdf', label: 'PDFs' },
  { key: 'image', label: 'Pictures' },
  { key: 'doc', label: 'Documents' },
  { key: 'sheet', label: 'Sheets' },
  { key: 'slides', label: 'Slides' },
  { key: 'archive', label: 'Zips' },
  { key: 'media', label: 'Audio & video' },
]

const VIEW_OF = { INBOX: 'all', SENT: 'sent', ARCHIVE: 'archive' }
/** The snippet with the searched words in bold (Box sends plain text and the terms). */
function Marked({ text, terms }) {
  const ws = (terms || []).filter((t) => t && t.length >= 2).map((t) => t.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
  if (!ws.length) return text
  const re = new RegExp(`(${ws.join('|')})`, 'gi')
  return text.split(re).map((part, i) => (i % 2 ? <mark key={i} className="rounded bg-amber-100 px-0.5 font-semibold text-brand-ink">{part}</mark> : part))
}

const day = (iso) => new Date(iso).toLocaleDateString([], { day: 'numeric', month: 'short', year: new Date(iso).getFullYear() === new Date().getFullYear() ? undefined : 'numeric' })

/** Files: every attachment from every mailbox in one place, to open, download or save to Magizhchi Box. */
export default function Files() {
  const { setMenuOpen, accountId, notify } = useMail()
  const navigate = useNavigate()
  const box = useBox()
  const [kind, setKind] = useState('')
  const [qInput, setQInput] = useState('')
  const [q, setQ] = useState('')
  const [items, setItems] = useState(null)
  const [next, setNext] = useState(null)
  const [loadingMore, setLoadingMore] = useState(false)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(null)
  const [inBox, setInBox] = useState(() => new Set())
  const seq = useRef(0)
  const [inside, setInside] = useState(null) // Box's answer for words inside documents
  const insideSeq = useRef(0)

  useEffect(() => {
    const t = setTimeout(() => setQ(qInput.trim()), 300)
    return () => clearTimeout(t)
  }, [qInput])

  const load = useCallback(async (cursor) => {
    const my = ++seq.current
    setError('')
    if (cursor) setLoadingMore(true)
    try {
      const res = await get(`/api/files${qs({ accountId: accountId ?? '', kind, q, cursor: cursor || '' })}`)
      if (my !== seq.current) return
      setItems((prev) => (cursor ? [...(prev || []), ...res.items] : res.items))
      setNext(res.nextCursor)
    } catch (e) {
      if (my === seq.current) setError(e.message)
    } finally {
      setLoadingMore(false)
    }
  }, [accountId, kind, q])

  useEffect(() => {
    setItems(null)
    load()
  }, [load])

  // Words inside documents: Magizhchi Box's own content search over their Box drive.
  useEffect(() => {
    const my = ++insideSeq.current
    if (q.length < 2) { setInside(null); return }
    setInside({ loading: true, results: [] })
    get(`/api/files/inside${qs({ q })}`)
      .then((res) => { if (my === insideSeq.current) setInside(res) })
      .catch((e) => { if (my === insideSeq.current) setInside({ error: e.message, results: [] }) })
  }, [q])
  const insideHits = (inside?.results || []).filter((f) => !kind || f.kind === kind)

  const openInBox = (f) => run(`x${f.boxFileId}`, async () => {
    const w = window.open('', '_blank') // opened now, while the click still counts, so it isn't blocked
    try {
      const { url } = await get(`/api/files/box/${f.boxFileId}/open`)
      if (w) { w.opener = null; w.location.href = url } else window.location.href = url
    } catch (e) {
      w?.close()
      throw e
    }
  })

  const key = (f) => `${f.messageId}:${f.index}`
  const path = (f) => `/api/messages/${f.messageId}/attachments/${f.index}`

  async function run(k, fn) {
    setBusy(k)
    try {
      await fn()
    } catch (e) {
      notify(e.message, 'error')
    } finally {
      setBusy(null)
    }
  }

  const saveToBox = (f) => run(`b${key(f)}`, async () => {
    const res = await post(`${path(f)}/box`)
    if ((res.inBox || []).includes(f.index) || res.saved) setInBox((s) => new Set([...s, key(f)]))
    notify(res.message, res.status === 'partial' ? 'error' : 'success')
    refreshBox()
  })

  return (
    <section className="slab rise-3d d1 flex min-h-0 flex-1 flex-col overflow-hidden max-lg:rounded-none">
      <header className="space-y-2 border-b border-slate-200/70 px-3 py-2.5 sm:px-4">
        <div className="flex items-center gap-2">
          <button className="icon-btn lg:hidden" onClick={() => setMenuOpen(true)} aria-label="Open menu"><IconMenu /></button>
          <IconPaperclip size={20} className="hidden text-peacock-600 sm:block" />
          <div className="min-w-0 flex-1">
            <h1 className="truncate text-base font-bold leading-tight">Files</h1>
            <p className="truncate text-[11px] text-slate-500">Every attachment from every mailbox</p>
          </div>
          {box?.webUrl && <a className="text-xs font-semibold text-peacock-700 hover:underline" href={box.webUrl} target="_blank" rel="noreferrer">Open Box ↗</a>}
        </div>
        <div className="relative">
          <IconSearch size={15} className="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-slate-400" />
          <input className="w-full rounded-xl border border-slate-200 bg-white/70 py-1.5 pl-8 pr-8 text-sm outline-none focus:border-peacock-400"
            placeholder="File name, sender, subject, or words inside a document" value={qInput} onChange={(e) => setQInput(e.target.value)} aria-label="Search files" />
          {qInput && (
            <button className="absolute right-2 top-1/2 -translate-y-1/2 text-slate-400 hover:text-brand-ink" onClick={() => setQInput('')} aria-label="Clear search"><IconX size={14} /></button>
          )}
        </div>
        <div className="scroll-thin -mx-1 flex gap-1.5 overflow-x-auto px-1 pb-0.5" role="tablist" aria-label="Kind of file">
          {KINDS.map((k) => (
            <button key={k.key} role="tab" aria-selected={kind === k.key}
              className={`shrink-0 rounded-full px-2.5 py-1 text-xs font-semibold ring-1 ${kind === k.key ? 'bg-peacock-600 text-white ring-peacock-600' : 'bg-white/70 text-slate-600 ring-slate-200 hover:bg-slate-50'}`}
              onClick={() => setKind(k.key)}>
              {k.label}
            </button>
          ))}
        </div>
      </header>

      <div className="scroll-thin min-h-0 flex-1 overflow-y-auto px-3 py-3 max-md:pb-24 sm:px-4">
        {error && <p className="mb-3 rounded-xl border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700" role="alert">{error}</p>}
        {items == null && !error && (
          <div className="grid gap-2 sm:grid-cols-2 xl:grid-cols-3" aria-busy="true">
            {[0, 1, 2, 3, 4, 5].map((i) => <div key={i} className="h-[68px] animate-pulse rounded-xl bg-slate-100" />)}
          </div>
        )}
        {q.length >= 2 && inside && (
          <InsideDocs inside={inside} hits={insideHits} q={q} busy={busy} onOpen={openInBox}
            onMail={(f) => navigate(`/?view=${VIEW_OF[f.folder] || 'all'}&open=${f.messageId}`)} />
        )}
        {q.length >= 2 && items?.length > 0 && insideHits.length > 0 && (
          <h2 className="mb-2 mt-1 text-[11px] font-semibold uppercase tracking-wider text-slate-500">Name, sender or subject</h2>
        )}
        {items?.length === 0 && !insideHits.length && !inside?.loading && (
          <div className="py-16 text-center">
            <p className="text-4xl" aria-hidden="true">📎</p>
            <p className="mt-3 font-semibold text-brand-ink">{q || kind ? 'No files match' : 'No files yet'}</p>
            <p className="mx-auto mt-1 max-w-sm text-sm text-slate-600">{q || kind ? (inside?.connected ? 'Nothing in the names, and no document in your Magizhchi Box has those words.' : 'Try another name, or All.') : 'Files people send you, and files you send, show up here.'}</p>
          </div>
        )}
        {items?.length > 0 && (
          <ul className="grid gap-2 sm:grid-cols-2 xl:grid-cols-3">
            {items.map((f) => {
              const k = kindOf(f)
              const id = key(f)
              const saved = f.inBox || inBox.has(id)
              return (
                <li key={id} className="file-card flex min-w-0 items-center gap-3 rounded-xl p-2.5 pr-2">
                  <FileTile kind={k} />
                  <div className="min-w-0 flex-1">
                    <div className="truncate text-sm font-semibold text-brand-ink" title={f.name}>{f.name}</div>
                    <button className="block max-w-full truncate text-left text-xs text-slate-500 hover:text-peacock-700 hover:underline"
                      title={`Open the mail: ${f.subject || '(no subject)'}`}
                      onClick={() => navigate(`/?view=${VIEW_OF[f.folder] || 'all'}&open=${f.messageId}`)}>
                      <span className="mr-1 inline-block h-1.5 w-1.5 rounded-full align-middle" style={{ background: f.accountColor || '#0e7c86' }} />
                      {f.folder === 'SENT' ? 'You sent' : senderName(f)} · {day(f.receivedAt)} · {formatSize(f.size)}
                    </button>
                  </div>
                  <div className="flex shrink-0 items-center gap-0.5">
                    {k.viewable && (
                      <button className="icon-btn" title={`Open ${f.name}`} aria-label={`Open ${f.name}`} disabled={busy != null}
                        onClick={() => run(`o${id}`, () => openFile(path(f), f.contentType))}>
                        {busy === `o${id}` ? <span className="spinner" /> : <IconEye size={17} />}
                      </button>
                    )}
                    {box?.configured && (saved ? (
                      <button className="icon-btn !text-emerald-600" title="In your Magizhchi Box" aria-label={`${f.name} is in Magizhchi Box`}
                        onClick={() => box.webUrl && window.open(box.webUrl, '_blank', 'noopener')}>
                        <IconCloudCheck size={18} />
                      </button>
                    ) : (
                      <button className="icon-btn" title={`Save ${f.name} to Magizhchi Box`} aria-label={`Save ${f.name} to Magizhchi Box`} disabled={busy != null}
                        onClick={() => saveToBox(f)}>
                        {busy === `b${id}` ? <span className="spinner" /> : <IconCloudUp size={18} />}
                      </button>
                    ))}
                    <button className="icon-btn !text-brand-primary" title={`Download ${f.name}`} aria-label={`Download ${f.name}`} disabled={busy != null}
                      onClick={() => run(`d${id}`, () => downloadFile(path(f), f.name))}>
                      {busy === `d${id}` ? <span className="spinner" /> : <IconDownload size={17} />}
                    </button>
                  </div>
                </li>
              )
            })}
          </ul>
        )}
        {next && (
          <div className="mt-3 text-center">
            <button className="btn-secondary text-xs" onClick={() => load(next)} disabled={loadingMore}>
              {loadingMore ? <span className="spinner !h-3 !w-3" /> : null} Show older files
            </button>
          </div>
        )}
      </div>
    </section>
  )
}

/** "Inside your documents": what Magizhchi Box found in the words of the files in your Box drive. */
function InsideDocs({ inside, hits, q, busy, onOpen, onMail }) {
  if (inside.loading) {
    return (
      <p className="mb-3 flex items-center gap-2 text-xs text-slate-500" aria-live="polite">
        <span className="spinner !h-3 !w-3" /> Looking inside your documents in Magizhchi Box…
      </p>
    )
  }
  if (inside.connected === false) {
    return (
      <p className="mb-3 rounded-xl bg-peacock-50/60 px-3 py-2 text-xs text-peacock-900 ring-1 ring-peacock-100">
        To find words <i>inside</i> documents, save them to Magizhchi Box (the cloud button on a file). Box reads PDFs, Word, Excel and scans, then finds them here.
      </p>
    )
  }
  if (inside.error || inside.unreachable) {
    return <p className="mb-3 text-xs text-slate-500">{inside.error || 'Magizhchi Box can’t be reached just now, so only names were searched.'}</p>
  }
  if (inside.enabled === false) {
    return <p className="mb-3 text-xs text-slate-500">Searching inside files is switched off in your Magizhchi Box drive. Turn it on in Box to find words inside documents.</p>
  }
  return (
    <div className="mb-4">
      <h2 className="mb-2 flex items-baseline gap-2 text-[11px] font-semibold uppercase tracking-wider text-slate-500">
        Inside your documents <span className="font-normal normal-case tracking-normal text-slate-400">· Magizhchi Box</span>
        {inside.pending > 0 && <span className="font-normal normal-case tracking-normal text-slate-400">· still reading {inside.pending} file{inside.pending === 1 ? '' : 's'}</span>}
      </h2>
      {!hits.length && <p className="text-xs text-slate-500">No document in your Box has “{q}” inside it.</p>}
      <ul className="grid gap-2 lg:grid-cols-2">
        {hits.map((f) => (
          <li key={f.boxFileId} className="file-card flex min-w-0 gap-3 rounded-xl p-2.5 pr-2">
            <FileTile kind={kindOf(f)} />
            <div className="min-w-0 flex-1">
              <div className="truncate text-sm font-semibold text-brand-ink" title={f.name}>{f.name}</div>
              {f.matches.slice(0, 2).map((m, i) => (
                <p key={i} className="mt-0.5 line-clamp-2 text-xs text-slate-600">
                  {m.page > 0 && <span className="mr-1 font-semibold text-slate-500">p.{m.page}</span>}
                  <Marked text={m.snippet} terms={inside.terms} />
                </p>
              ))}
              {f.messageId ? (
                <button className="mt-0.5 block max-w-full truncate text-left text-[11px] text-slate-500 hover:text-peacock-700 hover:underline"
                  title={`Open the mail: ${f.subject || '(no subject)'}`} onClick={() => onMail(f)}>
                  <span className="mr-1 inline-block h-1.5 w-1.5 rounded-full align-middle" style={{ background: f.accountColor || '#0e7c86' }} />
                  From the mail “{f.subject || '(no subject)'}”
                </button>
              ) : (
                <p className="mt-0.5 truncate text-[11px] text-slate-500">In Box{f.folderPath ? ` › ${f.folderPath.replace(/\/$/, '').split('/').join(' › ')}` : ''}{f.at ? ` · ${day(f.at)}` : ''}</p>
              )}
            </div>
            <button className="icon-btn shrink-0 self-center" title={`Open ${f.name} from Magizhchi Box`} aria-label={`Open ${f.name} from Magizhchi Box`}
              disabled={busy != null} onClick={() => onOpen(f)}>
              {busy === `x${f.boxFileId}` ? <span className="spinner" /> : <IconEye size={17} />}
            </button>
          </li>
        ))}
      </ul>
    </div>
  )
}
