import { senderName } from '../util.js'
import { PickBox } from './MessageList.jsx'
import LaneMenu from './LaneMenu.jsx'
import MoveMenu from './MoveMenu.jsx'
import SnoozeMenu from './SnoozeMenu.jsx'
import { IconArchive, IconEye, IconFlag, IconSnooze, IconTrash, IconX } from './Icons.jsx'

/**
 * What a view lets you do to many mails at once. Lanes and snooze only make sense in the Inbox;
 * Done isn't offered in Archive, nor Trash in Trash.
 */
export function bulkAbilities(cfg) {
  const folder = cfg.folder || 'INBOX'
  return {
    done: folder !== 'ARCHIVE',
    lane: folder === 'INBOX',
    snooze: folder === 'INBOX',
    wake: folder === 'SNOOZED',
    move: folder !== 'SENT' && folder !== 'SNOOZED' && folder !== 'WAITING',
    trash: folder !== 'TRASH',
  }
}

/** The list header while mails are ticked: the count, then the actions, icon-sized to fit a 340px list. */
export function BulkBar({ count, allLoaded, someLoaded, onToggleAll, onClear, can, anyUnread, accountId, folder, busy, run }) {
  const fake = { id: 'bulk', accountId, folder }
  return (
    <div className="bulk-bar flex items-center gap-0.5">
      <span className="flex h-8 w-7 shrink-0 items-center justify-center">
        <PickBox checked={allLoaded} mixed={!allLoaded && someLoaded} onClick={onToggleAll}
          label={allLoaded ? 'Untick all' : 'Tick everything shown'} />
      </span>
      <p className="shrink-0 px-0.5 text-sm font-bold text-brand-ink" aria-live="polite" title={`${count.toLocaleString()} selected`}>
        <span className="num">{count.toLocaleString()}</span><span className="hidden font-semibold text-slate-600 xl:inline"> selected</span>
      </p>
      <span className="min-w-0 flex-1" />
      {busy && <span className="spinner !h-3.5 !w-3.5" />}
      {can.done && (
        <button className="icon-btn" title="Done (archive)" disabled={busy} onClick={() => run('archive')}><IconArchive size={16} /></button>
      )}
      <button className="icon-btn" title={anyUnread ? 'Mark as read' : 'Mark as unread'} disabled={busy}
        onClick={() => run(anyUnread ? 'seen' : 'unseen')}>
        <IconEye size={16} />
      </button>
      {can.lane && <LaneMenu compact noRemember onMove={(lane) => run('lane', { lane })} />}
      {can.snooze && <SnoozeMenu compact message={fake} onSnooze={(_, at) => run('snooze', { at })} />}
      {can.wake && (
        <button className="icon-btn" title="Unsnooze: back to the inbox now" disabled={busy} onClick={() => run('snooze', { at: null })}><IconSnooze size={16} /></button>
      )}
      {can.move && <MoveMenu compact message={fake} onMove={(_, code, label) => run('move', { folder: code, label })} />}
      {can.trash && (
        <button className="icon-btn hover:!text-red-700" title="Move to Trash" disabled={busy} onClick={() => run('trash')}><IconTrash size={16} /></button>
      )}
      <button className="icon-btn" title="Clear selection (Esc)" onClick={onClear}><IconX size={16} /></button>
    </div>
  )
}

/**
 * Under the header once every loaded mail is ticked and there are more: offers the whole view.
 * Once taken, says so and offers to go back to just the ones shown.
 */
export function SelectAllStrip({ shown, total, all, title, onAll, onShown }) {
  if (!all && (total == null || total <= shown)) return null
  return (
    <div className="border-b border-peacock-200 bg-peacock-50/70 px-3 py-1.5 text-center text-xs text-peacock-900">
      {all ? (
        <>All <b className="num">{total?.toLocaleString() ?? ''}</b> in {title} are selected.{' '}
          <button className="font-semibold text-peacock-700 underline-offset-2 hover:underline" onClick={onShown}>Only the {shown} shown</button></>
      ) : (
        <>All {shown} shown are selected.{' '}
          <button className="font-semibold text-peacock-700 underline-offset-2 hover:underline" onClick={onAll}>
            Select all <span className="num">{total.toLocaleString()}</span> in {title}
          </button></>
      )}
    </div>
  )
}

/** The reading pane while mails are ticked: how many, from whom, and the same actions with words. */
export function BulkPane({ count, all, title, pickedItems, can, anyUnread, anyUnflagged, busy, run, onClear }) {
  const senders = new Map()
  for (const m of pickedItems) {
    const k = (m.fromEmail || senderName(m)).toLowerCase()
    const s = senders.get(k) || { name: senderName(m), n: 0 }
    s.n += 1
    senders.set(k, s)
  }
  const top = [...senders.values()].sort((a, b) => b.n - a.n).slice(0, 6)
  const unreadN = pickedItems.filter((m) => !m.seen).length
  return (
    <div className="flex flex-1 flex-col items-center justify-center p-8 text-center">
      <p className="text-4xl font-bold text-brand-ink num">{count.toLocaleString()}</p>
      <p className="mt-1 text-lg font-bold text-brand-ink">{all ? `Every mail in ${title}` : count === 1 ? 'mail selected' : 'mails selected'}</p>
      {!all && unreadN > 0 && <p className="mt-0.5 text-sm text-slate-500">{unreadN} unread</p>}
      {!all && top.length > 0 && (
        <ul className="mt-4 w-full max-w-xs divide-y divide-slate-200/70 rounded-xl bg-white/60 text-left text-sm ring-1 ring-slate-200">
          {top.map((s) => (
            <li key={s.name} className="flex items-center gap-2 px-3 py-1.5">
              <span className="min-w-0 flex-1 truncate text-slate-700">{s.name}</span>
              <span className="text-xs font-semibold text-slate-500 num">{s.n}</span>
            </li>
          ))}
          {senders.size > top.length && <li className="px-3 py-1.5 text-xs text-slate-500">and {senders.size - top.length} more senders</li>}
        </ul>
      )}
      <div className="mt-6 flex flex-wrap justify-center gap-2">
        {can.done && <button className="btn-primary" disabled={busy} onClick={() => run('archive')}><IconArchive size={15} /> Done</button>}
        <button className="btn-secondary" disabled={busy} onClick={() => run(anyUnread ? 'seen' : 'unseen')}>
          <IconEye size={15} /> {anyUnread ? 'Mark read' : 'Mark unread'}
        </button>
        <button className="btn-secondary" disabled={busy} onClick={() => run(anyUnflagged ? 'flag' : 'unflag')}>
          <IconFlag size={15} /> {anyUnflagged ? 'Flag' : 'Unflag'}
        </button>
        {can.trash && <button className="btn-secondary hover:!text-red-700" disabled={busy} onClick={() => run('trash')}><IconTrash size={15} /> Trash</button>}
      </div>
      <button className="mt-4 text-sm font-semibold text-peacock-700 hover:underline" onClick={onClear}>Clear selection</button>
      <p className="mt-6 hidden text-xs text-slate-400 md:block">Shift-click ticks a run of mails · Ctrl-click ticks one · Ctrl+A ticks all · Esc clears</p>
    </div>
  )
}
