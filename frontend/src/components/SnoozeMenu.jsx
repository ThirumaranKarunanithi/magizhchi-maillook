import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { localInput, snoozeChoices, whenLabel } from '../when.js'
import { IconSnooze } from './Icons.jsx'

/**
 * Snooze: hide this mail until a better moment; it comes back on top, unread, and rings your phone.
 * The popover sits against the viewport so the list and reading pane can't clip it.
 */
export default function SnoozeMenu({ message, onSnooze, compact = false }) {
  const [open, setOpen] = useState(false)
  const [pick, setPick] = useState('')
  const [pos, setPos] = useState(null)
  const ref = useRef(null)
  const popRef = useRef(null)

  function toggle(e) {
    e.stopPropagation()
    if (!open && ref.current) {
      const r = ref.current.getBoundingClientRect()
      const w = Math.min(260, window.innerWidth - 16)
      const top = r.bottom + 4 + 300 > window.innerHeight ? Math.max(8, r.top - 300) : r.bottom + 4
      setPos({ top, left: Math.max(8, Math.min(r.right - w, window.innerWidth - w - 8)), width: w })
      const d = new Date()
      d.setDate(d.getDate() + 1)
      d.setHours(9, 0, 0, 0)
      setPick(localInput(d))
    }
    setOpen((o) => !o)
  }

  useEffect(() => {
    if (!open) return undefined
    const onDown = (e) => { if (!ref.current?.contains(e.target) && !popRef.current?.contains(e.target)) setOpen(false) }
    const onKey = (e) => e.key === 'Escape' && setOpen(false)
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  function choose(at) {
    setOpen(false)
    onSnooze(message.id, at ? at.toISOString() : null)
  }

  const custom = pick ? new Date(pick) : null
  const customOk = custom && !Number.isNaN(custom.getTime()) && custom.getTime() > Date.now() + 60000

  return (
    <span className="inline-flex" ref={ref} onClick={(e) => e.stopPropagation()}>
      <button className={compact ? 'icon-btn' : 'btn-ghost'} onClick={toggle} aria-haspopup="menu" aria-expanded={open}
        title={message.snoozedUntil ? `Snoozed until ${whenLabel(message.snoozedUntil)}` : 'Snooze: bring it back later'}>
        <IconSnooze size={compact ? 16 : 17} />
        {!compact && <span className="hidden xl:inline">Snooze</span>}
      </button>
      {open && pos && createPortal(
        <div ref={popRef} className="menu fixed z-[60]" style={{ top: pos.top, left: pos.left, width: pos.width }} role="menu"
          onMouseDown={(e) => e.stopPropagation()}>
          <div className="px-3 pb-1 pt-1.5 text-[11px] font-semibold uppercase tracking-wider text-slate-500">Bring it back</div>
          {snoozeChoices().map((c) => (
            <button key={c.key} role="menuitem" className="menu-item" onClick={() => choose(c.at)}>
              <span className="flex-1">{c.label}</span>
              <span className="text-xs text-slate-500 num">{whenLabel(c.at)}</span>
            </button>
          ))}
          <div className="border-t border-slate-200 px-3 pb-2 pt-2">
            <label className="label" htmlFor={`snooze-${message.id}`}>Pick a time</label>
            <div className="flex gap-1.5">
              <input id={`snooze-${message.id}`} type="datetime-local" className="input !py-1 text-xs" value={pick}
                min={localInput(new Date())} onChange={(e) => setPick(e.target.value)} />
              <button className="btn-secondary !px-2 !py-1 text-xs" disabled={!customOk} onClick={() => choose(custom)}>Snooze</button>
            </div>
          </div>
          {message.snoozedUntil && (
            <button role="menuitem" className="menu-item border-t border-slate-200 text-peacock-700" onClick={() => choose(null)}>
              Unsnooze: back to the inbox now
            </button>
          )}
        </div>,
        document.body,
      )}
    </span>
  )
}
