import { useEffect, useRef, useState } from 'react'
import { useMail } from '../state.jsx'
import { IconArchive, IconCheck, IconFolder, IconInbox, IconMove, IconSpam, IconTrash } from './Icons.jsx'

const PLACES = [
  { code: 'INBOX', label: 'Inbox', Icon: IconInbox },
  { code: 'ARCHIVE', label: 'Archive', Icon: IconArchive },
  { code: 'SPAM', label: 'Spam', Icon: IconSpam },
  { code: 'TRASH', label: 'Trash', Icon: IconTrash },
]

/** Human name for a folder code: Inbox, Spam… or the mailbox's own folder name. */
export function folderLabel(code) {
  const p = PLACES.find((x) => x.code === code)
  if (p) return p.label
  if (code === 'SENT') return 'Sent'
  const path = String(code || '').replace(/^F:/, '')
  return path.split('/').pop() || path
}

/** "Move to folder": Inbox, Archive, Spam, Trash, then this mailbox's own folders (listed on first open). */
export default function MoveMenu({ message, onMove, compact = false }) {
  const { folders, refreshFolders } = useMail()
  const [open, setOpen] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const ref = useRef(null)

  useEffect(() => {
    if (!open) return undefined
    const onDown = (e) => { if (ref.current && !ref.current.contains(e.target)) setOpen(false) }
    const onKey = (e) => e.key === 'Escape' && setOpen(false)
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  function toggle() {
    const next = !open
    setOpen(next)
    if (next && folders == null && !loading) {
      setLoading(true)
      setError('')
      refreshFolders().catch((e) => setError(e.message)).finally(() => setLoading(false))
    }
  }

  const own = (folders || []).find((a) => a.accountId === message.accountId)
  const pick = (code) => { setOpen(false); if (code !== message.folder) onMove(message.id, code, folderLabel(code)) }

  return (
    <div className="relative" ref={ref} onClick={(e) => e.stopPropagation()}>
      <button className={compact ? 'icon-btn' : 'btn-ghost'} onClick={toggle} aria-haspopup="menu" aria-expanded={open} title="Move to folder">
        <IconMove size={compact ? 16 : 17} />
        {!compact && <span className="hidden sm:inline">Move</span>}
      </button>
      {open && (
        <div className="menu right-0 top-full mt-1 max-h-[60vh] w-60 overflow-y-auto" role="menu">
          {PLACES.map(({ code, label, Icon }) => (
            <button key={code} role="menuitem" className="menu-item" onClick={() => pick(code)} disabled={code === message.folder}>
              <Icon size={15} className="text-slate-500" />
              <span className="flex-1">{label}</span>
              {code === message.folder && <IconCheck size={15} className="text-brand-primary" />}
            </button>
          ))}
          <div className="mt-1 truncate border-t border-slate-200 px-3 pb-1 pt-2 text-[11px] text-slate-500">
            <span className="font-semibold uppercase tracking-wider">Folders</span>
            {message.accountEmail && <span> in {message.accountEmail}</span>}
          </div>
          {loading && <p className="flex items-center gap-2 px-3 py-2 text-xs text-slate-500"><span className="spinner" /> Asking the mail server…</p>}
          {!loading && (error || own?.error) && <p className="px-3 py-2 text-xs text-red-700">{error || own.error}</p>}
          {!loading && own && !own.error && !own.folders.length && (
            <p className="px-3 py-2 text-xs text-slate-500">This mailbox has no folders of its own.</p>
          )}
          {!loading && own?.folders.map((f) => (
            <button key={f.code} role="menuitem" className="menu-item" onClick={() => pick(f.code)} title={f.path} disabled={f.code === message.folder}>
              <IconFolder size={15} className="text-slate-500" />
              <span className="min-w-0 flex-1 truncate">{f.name}</span>
              {f.code === message.folder && <IconCheck size={15} className="text-brand-primary" />}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
