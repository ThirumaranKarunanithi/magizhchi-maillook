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
            placeholder="File name, sender or subject" value={qInput} onChange={(e) => setQInput(e.target.value)} aria-label="Search files" />
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
        {items?.length === 0 && (
          <div className="py-16 text-center">
            <p className="text-4xl" aria-hidden="true">📎</p>
            <p className="mt-3 font-semibold text-brand-ink">{q || kind ? 'No files match' : 'No files yet'}</p>
            <p className="mx-auto mt-1 max-w-sm text-sm text-slate-600">{q || kind ? 'Try another name, or All.' : 'Files people send you, and files you send, show up here.'}</p>
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
