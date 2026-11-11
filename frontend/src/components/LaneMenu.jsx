import { useEffect, useRef, useState } from 'react'
import { useLanes } from '../state.jsx'
import { IconCheck, IconLanes } from './Icons.jsx'

/** "Move to lane" button with a small menu and an "always for this sender" checkbox. */
export default function LaneMenu({ currentLane, onMove, compact = false, align = 'right', sender, noRemember = false }) {
  const LANES = useLanes()
  const [open, setOpen] = useState(false)
  const [remember, setRemember] = useState(false)
  const ref = useRef(null)

  useEffect(() => {
    if (!open) return
    const onDown = (e) => { if (ref.current && !ref.current.contains(e.target)) setOpen(false) }
    const onKey = (e) => e.key === 'Escape' && setOpen(false)
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  return (
    <div className="relative" ref={ref} onClick={(e) => e.stopPropagation()}>
      <button
        className={compact ? 'icon-btn' : 'btn-ghost'}
        onClick={() => setOpen((o) => !o)}
        aria-haspopup="menu"
        aria-expanded={open}
        title="Move to lane"
      >
        <IconLanes size={compact ? 16 : 17} />
        {!compact && <span className="hidden sm:inline">Move to lane</span>}
      </button>
      {open && (
        <div className={`menu top-full mt-1 ${align === 'left' ? 'left-0' : 'right-0'}`} role="menu">
          {LANES.map((l) => (
            <button key={l.key} role="menuitem" className="menu-item"
              onClick={() => { setOpen(false); onMove(l.key, remember) }}>
              <span className="w-4">{currentLane === l.key && <IconCheck size={15} className="text-brand-primary" />}</span>
              <span className="flex-1">
                <span className="block font-medium">{l.label}</span>
                <span className="block text-[11px] text-slate-500">{l.hint}</span>
              </span>
            </button>
          ))}
          {!noRemember && <label className="mt-1 flex cursor-pointer items-start gap-2 border-t border-slate-200 px-3 pb-1.5 pt-2 text-xs text-slate-600">
            <input type="checkbox" className="mt-0.5 accent-[#b45309]" checked={remember} onChange={(e) => setRemember(e.target.checked)} />
            <span>Always for this sender{sender ? <span className="block truncate text-slate-500">{sender}</span> : null}</span>
          </label>}
        </div>
      )}
    </div>
  )
}
