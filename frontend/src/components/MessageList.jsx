import { useRef } from 'react'
import { senderName, shortTime } from '../util.js'
import { IconArchive, IconBell, IconCheck, IconClock, IconFlag, IconNote, IconPaperclip, IconSnooze, IconTrash, IconVideo } from './Icons.jsx'
import MoveMenu from './MoveMenu.jsx'
import SnoozeMenu from './SnoozeMenu.jsx'
import { whenLabel } from '../when.js'
import LaneMenu from './LaneMenu.jsx'

/** "Starts in 12 min" for mail whose Magizhchi Meet meeting is close; the server pins these first. */
function startsBadge(at) {
  if (!at) return null
  const m = Math.round((new Date(at).getTime() - Date.now()) / 60000)
  if (m > 30 || m < -15) return null
  return m > 0 ? `Starts in ${m} min` : 'Meeting now'
}

/** A touch screen: a long press starts ticking, then taps tick. */
const TOUCH = typeof window !== 'undefined' && window.matchMedia?.('(hover: none)').matches

export function PickBox({ checked, mixed = false, onClick, label, className = '' }) {
  return (
    <button type="button" role="checkbox" aria-checked={mixed ? 'mixed' : checked} aria-label={label} title={label}
      onClick={(e) => { e.stopPropagation(); onClick(e) }}
      className={`pick-box inline-flex h-4 w-4 shrink-0 items-center justify-center rounded border-[1.5px] transition-colors ${checked || mixed
        ? 'border-peacock-700 bg-peacock-700 text-white' : 'border-slate-400 bg-white hover:border-peacock-700'} ${className}`}>
      {mixed ? <span className="h-0.5 w-2 rounded bg-white" /> : checked ? <IconCheck size={11} strokeWidth={3} /> : null}
    </button>
  )
}

function Row({ m, selected, picked, picking, sentView, onSelect, onPick, onDone, onTrash, onMoveLane, onMove, onSnooze }) {
  const press = useRef({ timer: null, fired: false })
  const unread = !m.seen
  const toLine = `To: ${(m.to || []).join(', ') || '(no recipients)'}`
  const who = sentView || m.backReason === 'no_reply' || m.waitingDays ? toLine : senderName(m)
  return (
    <div
      role="button"
      tabIndex={0}
      aria-current={selected ? 'true' : undefined}
      onClick={(e) => {
        if (press.current.fired) { press.current.fired = false; return }
        if (onPick && (e.shiftKey || e.ctrlKey || e.metaKey || (picking && TOUCH))) {
          e.preventDefault()
          onPick(m.id, { range: e.shiftKey })
          return
        }
        onSelect(m.id)
      }}
      onKeyDown={(e) => { if (e.key === 'Enter') onSelect(m.id) }}
      onMouseDown={(e) => { if (e.shiftKey && onPick) e.preventDefault() /* no text highlight on Shift-click */ }}
      onTouchStart={() => {
        if (!onPick) return
        clearTimeout(press.current.timer)
        press.current.timer = setTimeout(() => { press.current.fired = true; onPick(m.id, {}); navigator.vibrate?.(15) }, 450)
      }}
      onTouchMove={() => clearTimeout(press.current.timer)}
      onTouchEnd={() => clearTimeout(press.current.timer)}
      onContextMenu={(e) => { if (TOUCH && onPick) e.preventDefault() }}
      className={`msg-row group relative cursor-pointer select-none border-b border-slate-200/70 py-3 pl-4 pr-3 transition-colors sm:select-auto sm:py-2.5 ${picking ? 'picking' : ''} ${
        picked ? 'picked bg-peacock-50' : selected ? 'bg-amber-50' : unread ? 'bg-white hover:bg-slate-50' : 'bg-white/60 hover:bg-slate-50'
      }`}
    >
      <span className="absolute inset-y-0 left-0 w-1" style={{ background: m.accountColor || '#0e7c86' }} title={m.accountEmail} />
      {selected && <span className="absolute inset-y-0 left-1 w-0.5 bg-brand-gold" />}
      <div className="flex items-center gap-2">
        {onPick ? (
          <span className="pick-slot relative inline-flex h-4 w-4 shrink-0 items-center justify-center">
            {unread && <span className="pick-dot h-2 w-2 rounded-full bg-brand-gold" aria-label="Unread" />}
            <PickBox checked={!!picked} label={picked ? 'Untick' : 'Tick to select'}
              onClick={(e) => onPick(m.id, { range: e.shiftKey })} className="pick-check absolute inset-0" />
          </span>
        ) : unread && <span className="h-2 w-2 shrink-0 rounded-full bg-brand-gold" aria-label="Unread" />}
        <span className={`min-w-0 flex-1 truncate text-[17px] sm:text-sm ${unread ? 'font-bold text-brand-ink' : 'font-medium text-slate-700'}`}>{who}</span>
        {m.threadCount > 1 && <span className="rounded bg-slate-100 px-1.5 text-[11px] font-semibold text-slate-600 num">{m.threadCount}</span>}
        {m.hasNote && <IconNote size={13} className="text-amber-600" aria-label="Has your note" />}
        {m.flagged && <IconFlag filled size={13} className="text-red-600" />}
        {m.hasAttachments && <IconPaperclip size={14} className="text-brand-primary" aria-label="Has attachments" />}
        <span className={`shrink-0 text-sm sm:text-xs num ${unread ? 'font-semibold text-brand-primary' : 'text-slate-500'}`}>{shortTime(m.receivedAt)}</span>
      </div>
      <div className={`mt-0.5 truncate text-base sm:mt-0 sm:text-[13px] ${unread ? 'font-semibold text-brand-ink' : 'text-slate-800 sm:text-slate-700'}`}>
        {startsBadge(m.meetingStartsAt) && (
          <span className="mr-1.5 inline-flex items-center gap-1 rounded-md bg-emerald-50 px-1.5 py-0.5 align-[1px] text-xs sm:text-[11px] font-semibold text-emerald-800 ring-1 ring-emerald-300">
            <IconVideo size={11} />{startsBadge(m.meetingStartsAt)}
          </span>
        )}
        {m.backReason === 'snooze' && (
          <span className="mr-1.5 inline-flex items-center gap-1 rounded-md bg-amber-50 px-1.5 py-0.5 align-[1px] text-xs sm:text-[11px] font-semibold text-amber-800 ring-1 ring-amber-300">
            <IconSnooze size={11} />Back
          </span>
        )}
        {m.backReason === 'no_reply' && (
          <span className="mr-1.5 inline-flex items-center gap-1 rounded-md bg-amber-50 px-1.5 py-0.5 align-[1px] text-xs sm:text-[11px] font-semibold text-amber-800 ring-1 ring-amber-300">
            <IconBell size={11} />No reply yet
          </span>
        )}
        {m.waitingDays > 0 && (
          <span className="mr-1.5 inline-flex items-center gap-1 rounded-md bg-peacock-50 px-1.5 py-0.5 align-[1px] text-xs sm:text-[11px] font-semibold text-peacock-800 ring-1 ring-peacock-200">
            <IconClock size={11} />{m.waitingDays} days, no reply
          </span>
        )}
        {m.snoozedUntil && (
          <span className="mr-1.5 inline-flex items-center gap-1 rounded-md bg-slate-100 px-1.5 py-0.5 align-[1px] text-xs sm:text-[11px] font-semibold text-slate-700 ring-1 ring-slate-200">
            <IconSnooze size={11} />{whenLabel(m.snoozedUntil)}
          </span>
        )}
        {m.subject || '(no subject)'}
      </div>
      <div className="mt-0.5 truncate text-[15px] text-slate-600 sm:mt-0 sm:pr-24 sm:text-xs sm:text-slate-500">{m.waitingFor ? <>Waiting for {m.waitingFor}</> : m.snippet}</div>
      {m.attachmentNames?.length > 0 && (
        <div className="mt-1.5 flex min-w-0 items-center gap-1.5 sm:pr-24">
          {m.attachmentNames.slice(0, 2).map((n, i) => (
            <span key={i} className="inline-flex min-w-0 max-w-[60%] items-center gap-1 rounded-md bg-amber-50 px-1.5 py-0.5 text-[13px] sm:text-[11px] font-medium text-amber-700 ring-1 ring-amber-200/70" title={n}>
              <IconPaperclip size={11} className="shrink-0" /><span className="truncate">{n}</span>
            </span>
          ))}
          {m.attachmentCount > 2 && <span className="shrink-0 text-[11px] font-semibold text-amber-700">+{m.attachmentCount - 2}</span>}
        </div>
      )}

      {!picking && <div className="row-actions absolute bottom-1.5 right-2 flex items-center gap-0.5 rounded-lg bg-white/95 shadow-sm ring-1 ring-slate-200">
        <button className="icon-btn" title="Done (archive)" onClick={(e) => { e.stopPropagation(); onDone(m.id) }}>
          <IconArchive size={16} />
        </button>
        {m.folder === 'INBOX' && onSnooze && <SnoozeMenu compact message={m} onSnooze={onSnooze} />}
        {m.folder === 'INBOX' && <LaneMenu compact currentLane={m.lane} sender={m.fromEmail} onMove={(lane, remember) => onMoveLane(m.id, lane, remember)} />}
        {onMove && !sentView && <MoveMenu compact message={m} onMove={onMove} />}
        <button className="icon-btn hover:!text-red-700" title="Move to Trash" onClick={(e) => { e.stopPropagation(); onTrash(m.id) }}>
          <IconTrash size={16} />
        </button>
      </div>}
    </div>
  )
}

export default function MessageList({
  items, loading, error, selectedId, sentView, empty, nextCursor, loadingMore, onLoadMore, onRetry, picked, ...handlers
}) {
  const picking = !!picked && picked.size > 0
  if (loading && !items.length) {
    return (
      <div className="space-y-px" aria-busy="true">
        {Array.from({ length: 7 }).map((_, i) => (
          <div key={i} className="animate-pulse border-b border-slate-200/70 bg-white/60 px-4 py-3">
            <div className="h-3 w-1/3 rounded bg-slate-200" />
            <div className="mt-2 h-3 w-2/3 rounded bg-slate-100" />
            <div className="mt-1.5 h-2.5 w-5/6 rounded bg-slate-100" />
          </div>
        ))}
      </div>
    )
  }
  if (error && !items.length) {
    return (
      <div className="p-6 text-center text-sm">
        <p className="text-red-700">{error}</p>
        <button className="btn-secondary mt-3" onClick={onRetry}>Try again</button>
      </div>
    )
  }
  if (!items.length) return empty
  return (
    <div>
      {items.map((m) => (
        <Row key={m.id} m={m} selected={m.id === selectedId} picked={picked?.has(m.id)} picking={picking} sentView={sentView} {...handlers} />
      ))}
      {nextCursor && (
        <div className="p-3 text-center">
          <button className="btn-secondary" onClick={onLoadMore} disabled={loadingMore}>
            {loadingMore && <span className="spinner" />} Load more
          </button>
        </div>
      )}
      {!nextCursor && items.length > 8 && <p className="py-4 text-center text-xs text-slate-400">That's everything here.</p>}
    </div>
  )
}
