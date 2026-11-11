import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { useMail } from '../state.jsx'
import { useLanes } from '../state.jsx'
import { localInput, snoozeChoices, whenLabel } from '../when.js'
import { PLACES, folderLabel } from './MoveMenu.jsx'
import {
  IconArchive, IconBack, IconCheck, IconChevron, IconEye, IconFlag, IconFolder, IconLanes, IconMore, IconMove, IconSnooze, IconTrash,
} from './Icons.jsx'

/**
 * One ⋯ button holding every action on a mail: Done, Snooze, lane, folder, flag, read, and Trash at the bottom.
 * Nothing happens on the first touch, so a thumb that lands on it while opening a mail can't archive or delete anything.
 * The popover sits against the viewport so the list can't clip it; submenus open in place with a Back row.
 */
export default function MailActionsMenu({
  message: m, sentView = false, onArchive, onTrash, onSnooze, onMoveLane, onMove, onFlag, onSeen, className = '', size = 16, label,
}) {
  const { folders, refreshFolders } = useMail()
  const LANES = useLanes()
  const [open, setOpen] = useState(false)
  const [panel, setPanel] = useState('main')
  const [pos, setPos] = useState(null)
  const [pick, setPick] = useState('')
  const [remember, setRemember] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const btn = useRef(null)
  const pop = useRef(null)

  function place() {
    if (!btn.current) return
    const r = btn.current.getBoundingClientRect()
    const w = Math.min(264, window.innerWidth - 16)
    const h = pop.current?.offsetHeight || 320
    const below = r.bottom + 4 + h <= window.innerHeight - 8
    const top = below ? r.bottom + 4 : Math.max(8, r.top - 4 - h)
    setPos({ top, left: Math.max(8, Math.min(r.right - w, window.innerWidth - w - 8)), width: w })
  }

  function toggle(e) {
    e.stopPropagation()
    e.preventDefault()
    if (open) { setOpen(false); return }
    setPanel('main')
    setRemember(false)
    const d = new Date()
    d.setDate(d.getDate() + 1)
    d.setHours(9, 0, 0, 0)
    setPick(localInput(d))
    place()
    setOpen(true)
  }

  // re-place once the real height is known, and whenever the panel changes size
  useLayoutEffect(() => { if (open) place() }, [open, panel, loading])

  useEffect(() => {
    if (!open) return undefined
    const onDown = (e) => { if (!btn.current?.contains(e.target) && !pop.current?.contains(e.target)) setOpen(false) }
    const onKey = (e) => {
      if (e.key !== 'Escape') return
      if (panel !== 'main') setPanel('main')
      else setOpen(false)
    }
    const onScroll = (e) => { if (!pop.current?.contains(e.target)) setOpen(false) }
    document.addEventListener('pointerdown', onDown)
    document.addEventListener('keydown', onKey)
    window.addEventListener('scroll', onScroll, true)
    window.addEventListener('resize', onScroll)
    return () => {
      document.removeEventListener('pointerdown', onDown)
      document.removeEventListener('keydown', onKey)
      window.removeEventListener('scroll', onScroll, true)
      window.removeEventListener('resize', onScroll)
    }
  }, [open, panel])

  function act(fn) {
    setOpen(false)
    Promise.resolve().then(fn).catch(() => { /* the handler already said what went wrong */ })
  }

  function openFolders() {
    setPanel('move')
    if (folders == null && !loading) {
      setLoading(true)
      setError('')
      refreshFolders().catch((e) => setError(e.message)).finally(() => setLoading(false))
    }
  }

  const inbox = m.folder === 'INBOX'
  const canSnooze = inbox && onSnooze
  const canLane = inbox && onMoveLane
  const canMove = onMove && !sentView && m.folder !== 'SENT'
  const own = (folders || []).find((a) => a.accountId === m.accountId)
  const custom = pick ? new Date(pick) : null
  const customOk = custom && !Number.isNaN(custom.getTime()) && custom.getTime() > Date.now() + 60000

  const Back = ({ title }) => (
    <button type="button" className="menu-item !py-2 font-semibold" onClick={() => setPanel('main')}>
      <IconBack size={15} className="text-slate-500" /><span className="flex-1">{title}</span>
    </button>
  )
  const Sub = ({ Icon, children, onClick, hint }) => (
    <button type="button" role="menuitem" className="menu-item !py-2" onClick={onClick} aria-haspopup="menu">
      <Icon size={16} className="text-slate-500" /><span className="flex-1">{children}</span>
      {hint && <span className="max-w-[90px] truncate text-xs text-slate-500">{hint}</span>}
      <IconChevron size={14} className="text-slate-400" />
    </button>
  )

  return (
    <>
      <button ref={btn} type="button" className={`icon-btn ${open ? 'is-open' : ''} ${className}`} onClick={toggle}
        onPointerDown={(e) => e.stopPropagation()} onTouchStart={(e) => e.stopPropagation()} onKeyDown={(e) => e.stopPropagation()}
        aria-haspopup="menu" aria-expanded={open} aria-label={label || 'More actions'} title={label || 'More actions'}>
        <IconMore size={size} />
      </button>
      {open && pos && createPortal(
        <div ref={pop} className="menu fixed z-[60] max-h-[70vh] overflow-y-auto overflow-x-hidden" style={{ top: pos.top, left: pos.left, width: pos.width }}
          role="menu" onClick={(e) => e.stopPropagation()} onPointerDown={(e) => e.stopPropagation()}>
          {panel === 'main' && <>
            {m.folder !== 'ARCHIVE' && !sentView && onArchive && (
              <button type="button" role="menuitem" className="menu-item !py-2" onClick={() => act(() => onArchive(m.id))}>
                <IconArchive size={16} className="text-slate-500" /><span className="flex-1">Done (archive)</span>
              </button>
            )}
            {canSnooze && <Sub Icon={IconSnooze} onClick={() => setPanel('snooze')} hint={m.snoozedUntil ? whenLabel(m.snoozedUntil) : ''}>Snooze</Sub>}
            {canLane && <Sub Icon={IconLanes} onClick={() => setPanel('lane')} hint={LANES.find((l) => l.key === m.lane)?.label}>Move to lane</Sub>}
            {canMove && <Sub Icon={IconMove} onClick={openFolders}>Move to folder</Sub>}
            {onFlag && (
              <button type="button" role="menuitem" className="menu-item !py-2" onClick={() => act(() => onFlag(m.id, !m.flagged))}>
                <IconFlag size={16} filled={m.flagged} className={m.flagged ? 'text-red-600' : 'text-slate-500'} />
                <span className="flex-1">{m.flagged ? 'Remove flag' : 'Flag'}</span>
              </button>
            )}
            {onSeen && (
              <button type="button" role="menuitem" className="menu-item !py-2" onClick={() => act(() => onSeen(m.id, !m.seen))}>
                <IconEye size={16} className="text-slate-500" /><span className="flex-1">{m.seen ? 'Mark as unread' : 'Mark as read'}</span>
              </button>
            )}
            {onTrash && m.folder !== 'TRASH' && <>
              <div className="my-1 border-t border-slate-200" />
              <button type="button" role="menuitem" className="menu-item !py-2 !text-red-700" onClick={() => act(() => onTrash(m.id))}>
                <IconTrash size={16} /><span className="flex-1">Move to Trash</span>
              </button>
            </>}
          </>}

          {panel === 'snooze' && <>
            <Back title="Snooze until" />
            {snoozeChoices().map((c) => (
              <button key={c.key} type="button" role="menuitem" className="menu-item !py-2" onClick={() => act(() => onSnooze(m.id, c.at.toISOString()))}>
                <span className="flex-1">{c.label}</span>
                <span className="num text-xs text-slate-500">{whenLabel(c.at)}</span>
              </button>
            ))}
            <div className="border-t border-slate-200 px-3 pb-2 pt-2">
              <label className="label" htmlFor={`rm-snooze-${m.id}`}>Pick a time</label>
              <div className="flex gap-1.5">
                <input id={`rm-snooze-${m.id}`} type="datetime-local" className="input min-w-0 flex-1 !px-2 !py-1 text-xs" value={pick}
                  min={localInput(new Date())} onChange={(e) => setPick(e.target.value)} />
                <button type="button" className="btn-secondary !px-2 !py-1 text-xs" disabled={!customOk}
                  onClick={() => act(() => onSnooze(m.id, custom.toISOString()))}>Snooze</button>
              </div>
            </div>
            {m.snoozedUntil && (
              <button type="button" role="menuitem" className="menu-item border-t border-slate-200 text-peacock-700" onClick={() => act(() => onSnooze(m.id, null))}>
                Unsnooze: back to the inbox now
              </button>
            )}
          </>}

          {panel === 'lane' && <>
            <Back title="Move to lane" />
            {LANES.map((l) => (
              <button key={l.key} type="button" role="menuitem" className="menu-item" onClick={() => act(() => onMoveLane(m.id, l.key, remember))}>
                <span className="w-4">{m.lane === l.key && <IconCheck size={15} className="text-brand-primary" />}</span>
                <span className="flex-1">
                  <span className="block font-medium">{l.label}</span>
                  <span className="block text-[11px] text-slate-500">{l.hint}</span>
                </span>
              </button>
            ))}
            <label className="mt-1 flex cursor-pointer items-start gap-2 border-t border-slate-200 px-3 pb-1.5 pt-2 text-xs text-slate-600">
              <input type="checkbox" className="mt-0.5 accent-[#b45309]" checked={remember} onChange={(e) => setRemember(e.target.checked)} />
              <span>Always for this sender{m.fromEmail ? <span className="block truncate text-slate-500">{m.fromEmail}</span> : null}</span>
            </label>
          </>}

          {panel === 'move' && <>
            <Back title="Move to folder" />
            {PLACES.filter((p) => p.code !== 'TRASH').map(({ code, label: name, Icon }) => (
              <button key={code} type="button" role="menuitem" className="menu-item" disabled={code === m.folder}
                onClick={() => act(() => onMove(m.id, code, name))}>
                <Icon size={15} className="text-slate-500" /><span className="flex-1">{name}</span>
                {code === m.folder && <IconCheck size={15} className="text-brand-primary" />}
              </button>
            ))}
            <div className="mt-1 truncate border-t border-slate-200 px-3 pb-1 pt-2 text-[11px] text-slate-500">
              <span className="font-semibold uppercase tracking-wider">Folders</span>
              {m.accountEmail && <span> in {m.accountEmail}</span>}
            </div>
            {loading && <p className="flex items-center gap-2 px-3 py-2 text-xs text-slate-500"><span className="spinner" /> Asking the mail server…</p>}
            {!loading && (error || own?.error) && <p className="px-3 py-2 text-xs text-red-700">{error || own.error}</p>}
            {!loading && own && !own.error && !own.folders.length && (
              <p className="px-3 py-2 text-xs text-slate-500">This mailbox has no folders of its own.</p>
            )}
            {!loading && own?.folders.map((f) => (
              <button key={f.code} type="button" role="menuitem" className="menu-item" title={f.path} disabled={f.code === m.folder}
                onClick={() => act(() => onMove(m.id, f.code, folderLabel(f.code)))}>
                <IconFolder size={15} className="text-slate-500" /><span className="min-w-0 flex-1 truncate">{f.name}</span>
                {f.code === m.folder && <IconCheck size={15} className="text-brand-primary" />}
              </button>
            ))}
          </>}
        </div>,
        document.body,
      )}
    </>
  )
}
