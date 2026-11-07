import { senderName, shortTime } from '../util.js'
import { IconArchive, IconFlag, IconPaperclip, IconTrash, IconVideo } from './Icons.jsx'
import LaneMenu from './LaneMenu.jsx'

/** "Starts in 12 min" for mail whose Magizhchi Meet meeting is close; the server pins these first. */
function startsBadge(at) {
  if (!at) return null
  const m = Math.round((new Date(at).getTime() - Date.now()) / 60000)
  if (m > 30 || m < -15) return null
  return m > 0 ? `Starts in ${m} min` : 'Meeting now'
}

function Row({ m, selected, sentView, onSelect, onDone, onTrash, onMoveLane }) {
  const unread = !m.seen
  const who = sentView ? `To: ${(m.to || []).join(', ') || '(no recipients)'}` : senderName(m)
  return (
    <div
      role="button"
      tabIndex={0}
      aria-current={selected ? 'true' : undefined}
      onClick={() => onSelect(m.id)}
      onKeyDown={(e) => { if (e.key === 'Enter') onSelect(m.id) }}
      className={`msg-row group relative cursor-pointer border-b border-slate-200/70 py-2.5 pl-4 pr-3 transition-colors ${
        selected ? 'bg-amber-50' : unread ? 'bg-white hover:bg-slate-50' : 'bg-white/60 hover:bg-slate-50'
      }`}
    >
      <span className="absolute inset-y-0 left-0 w-1" style={{ background: m.accountColor || '#0e7c86' }} title={m.accountEmail} />
      {selected && <span className="absolute inset-y-0 left-1 w-0.5 bg-brand-gold" />}
      <div className="flex items-center gap-2">
        {unread && <span className="h-2 w-2 shrink-0 rounded-full bg-brand-gold" aria-label="Unread" />}
        <span className={`min-w-0 flex-1 truncate text-sm ${unread ? 'font-bold text-brand-ink' : 'font-medium text-slate-700'}`}>{who}</span>
        {m.threadCount > 1 && <span className="rounded bg-slate-100 px-1.5 text-[11px] font-semibold text-slate-600 num">{m.threadCount}</span>}
        {m.flagged && <IconFlag filled size={13} className="text-red-600" />}
        {m.hasAttachments && <IconPaperclip size={14} className="text-brand-primary" aria-label="Has attachments" />}
        <span className={`shrink-0 text-xs num ${unread ? 'font-semibold text-brand-primary' : 'text-slate-500'}`}>{shortTime(m.receivedAt)}</span>
      </div>
      <div className={`truncate text-[13px] ${unread ? 'font-semibold text-brand-ink' : 'text-slate-700'}`}>
        {startsBadge(m.meetingStartsAt) && (
          <span className="mr-1.5 inline-flex items-center gap-1 rounded-md bg-emerald-50 px-1.5 py-0.5 align-[1px] text-[11px] font-semibold text-emerald-800 ring-1 ring-emerald-300">
            <IconVideo size={11} />{startsBadge(m.meetingStartsAt)}
          </span>
        )}
        {m.subject || '(no subject)'}
      </div>
      <div className="truncate pr-24 text-xs text-slate-500">{m.snippet}</div>
      {m.attachmentNames?.length > 0 && (
        <div className="mt-1.5 flex min-w-0 items-center gap-1.5 pr-24">
          {m.attachmentNames.slice(0, 2).map((n, i) => (
            <span key={i} className="inline-flex min-w-0 max-w-[60%] items-center gap-1 rounded-md bg-amber-50 px-1.5 py-0.5 text-[11px] font-medium text-amber-700 ring-1 ring-amber-200/70" title={n}>
              <IconPaperclip size={11} className="shrink-0" /><span className="truncate">{n}</span>
            </span>
          ))}
          {m.attachmentCount > 2 && <span className="shrink-0 text-[11px] font-semibold text-amber-700">+{m.attachmentCount - 2}</span>}
        </div>
      )}

      <div className="row-actions absolute bottom-1.5 right-2 flex items-center gap-0.5 rounded-lg bg-white/95 shadow-sm ring-1 ring-slate-200">
        <button className="icon-btn" title="Done (archive)" onClick={(e) => { e.stopPropagation(); onDone(m.id) }}>
          <IconArchive size={16} />
        </button>
        <LaneMenu compact currentLane={m.lane} sender={m.fromEmail} onMove={(lane, remember) => onMoveLane(m.id, lane, remember)} />
        <button className="icon-btn hover:!text-red-700" title="Move to Trash" onClick={(e) => { e.stopPropagation(); onTrash(m.id) }}>
          <IconTrash size={16} />
        </button>
      </div>
    </div>
  )
}

export default function MessageList({
  items, loading, error, selectedId, sentView, empty, nextCursor, loadingMore, onLoadMore, onRetry, ...handlers
}) {
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
        <Row key={m.id} m={m} selected={m.id === selectedId} sentView={sentView} {...handlers} />
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
