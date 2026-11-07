import { useCallback, useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { del, get, post, qs } from '../api.js'
import { useMail } from '../state.jsx'
import { LANE_LABEL, shortTime, VIEWS } from '../util.js'
import MessageList from '../components/MessageList.jsx'
import ReadingPane from '../components/ReadingPane.jsx'
import AskCard from '../components/AskCard.jsx'
import ConfirmDialog from '../components/ConfirmDialog.jsx'
import { folderLabel } from '../components/MoveMenu.jsx'
import { whenLabel } from '../when.js'
import { IconArchive, IconCheck, IconCompose, IconDraft, IconFlag, IconMenu, IconPaperclip, IconRefresh, IconSearch, IconSparkle, IconTrash, IconX } from '../components/Icons.jsx'

const PAGE = 50

const EMPTY = {
  needs_me: { emoji: '🎉', title: 'Nothing needs you right now', text: 'Nobody is waiting on you. Go make yourself a nice cup of chai.' },
  fyi: { emoji: '☀️', title: "You're all caught up", text: 'No news to skim. Enjoy the quiet.' },
  can_wait: { emoji: '🌼', title: 'Nothing waiting', text: 'The "later" pile is empty. Lovely.' },
  all: { emoji: '🌸', title: 'Inbox zero. Magizhchi!', text: 'Every message is handled. That is what happy looks like.' },
  sent: { emoji: '✉️', title: 'Nothing sent yet', text: 'Mail you send from Magizhchi MailLook shows up here.' },
  archive: { emoji: '📦', title: 'The archive is empty', text: 'Press Done on a message and it rests here.' },
  spam: { emoji: '🛡️', title: 'No spam', text: 'Your mail server catches junk and puts it here. Nothing caught lately.' },
  trash: { emoji: '🧺', title: 'Trash is empty', text: 'Mail you trash waits here. Restore puts it back in the Inbox.' },
  folder: { emoji: '📁', title: 'Nothing in this folder', text: 'Mail you move here, or that your other mail apps file here, shows up after the next sync.' },
  drafts: { emoji: '📝', title: 'No drafts', text: 'Close the composer before sending and what you wrote waits here.' },
  snoozed: { emoji: '⏰', title: 'Nothing snoozed', text: 'Snooze a mail and it waits here, then comes back on top when you said, with a ping on your phone.' },
  waiting: { emoji: '🤝', title: 'Nobody owes you a reply', text: 'Mail you sent that asked for something shows up here when there is no answer after two days.' },
}

const SINCE = [
  { key: '', label: 'Any time' },
  { key: 'week', label: 'Past week', days: 7 },
  { key: 'month', label: 'Past month', days: 30 },
  { key: 'year', label: 'Past year', days: 365 },
]
const NO_FILTERS = { unread: false, flagged: false, hasAttachment: false, since: '', from: '' }
const TZ = (() => {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || ''
  } catch {
    return ''
  }
})()

/** Reads like a question rather than a few words to find. */
const QUESTION = /\?\s*$|^(who|what|when|where|which|why|how|did|do|does|is|was|were|has|have|find|show|list|any)\b/i

function sinceIso(key) {
  const s = SINCE.find((x) => x.key === key)
  return s?.days ? new Date(Date.now() - s.days * 86400000).toISOString() : ''
}

function Toggle({ on, onClick, children, title }) {
  return (
    <button type="button" aria-pressed={on} onClick={onClick} title={title}
      className={`inline-flex items-center gap-1 rounded-full border px-2.5 py-1 text-sm sm:px-2 sm:py-0.5 sm:text-[11px] font-semibold transition-colors ${on
        ? 'border-peacock-300 bg-peacock-50 text-peacock-800'
        : 'border-slate-200 text-slate-600 hover:border-slate-300 hover:text-brand-ink'}`}>
      {on && <IconCheck size={11} strokeWidth={2.6} />}
      {children}
    </button>
  )
}

function DraftList({ drafts, accounts, onOpen, onDiscard }) {
  if (!drafts.length) return null
  return (
    <ul>
      {drafts.map((d) => {
        const a = accounts.find((x) => x.id === d.accountId)
        const snippet = (d.body || '').replace(/\n-- \n[\s\S]*$/, '').replace(/\s+/g, ' ').trim()
        return (
          <li key={d.id} className="msg-row group relative border-b border-slate-200/70 bg-white">
            <button type="button" className="block w-full py-2.5 pl-4 pr-12 text-left hover:bg-slate-50" onClick={() => onOpen(d)}>
              <span className="absolute inset-y-0 left-0 w-1" style={{ background: a?.color || '#94a3b8' }} />
              <span className="flex items-center gap-2">
                <span className="text-[11px] font-bold uppercase tracking-wide text-red-700">Draft</span>
                <span className="min-w-0 flex-1 truncate text-sm font-medium text-slate-700">
                  {d.to?.length ? `To: ${d.to.join(', ')}` : '(no recipients yet)'}
                </span>
                <span className="shrink-0 text-xs text-slate-500 num">{shortTime(d.updatedAt)}</span>
              </span>
              <span className="block truncate text-[13px] text-brand-ink">{d.subject || '(no subject)'}</span>
              <span className="block truncate text-xs text-slate-500">{snippet || 'Nothing written yet'}</span>
            </button>
            <button type="button" className="icon-btn absolute right-2 top-1/2 -translate-y-1/2 hover:!text-red-700" title="Discard draft"
              aria-label={`Discard draft ${d.subject || ''}`} onClick={() => onDiscard(d)}>
              <IconTrash size={16} />
            </button>
          </li>
        )
      })}
    </ul>
  )
}

function EmptyState({ view, q, accountChosen, filtered }) {
  if (q || filtered) {
    return (
      <div className="px-6 py-14 text-center">
        <div className="text-3xl">🔍</div>
        <p className="mt-2 font-semibold">{q ? <>No mail matches “{q}”</> : 'No mail matches these filters'}</p>
        <p className="mt-1 text-sm text-slate-500">Try a name, a word from the subject, or fewer filters.</p>
      </div>
    )
  }
  const e = EMPTY[view] || EMPTY.all
  return (
    <div className="px-6 py-14 text-center">
      <div className="text-5xl" aria-hidden="true">{e.emoji}</div>
      <p className="mt-3 text-lg font-bold text-brand-ink">{e.title}</p>
      <p className="mx-auto mt-1 max-w-xs text-sm text-slate-500">{e.text}</p>
      {accountChosen && <p className="mt-3 text-xs text-slate-400">Showing one account. Pick “All accounts” to see everything.</p>}
    </div>
  )
}

export default function Mail() {
  const [params, setParams] = useSearchParams()
  const folderCode = params.get('view') === 'folder' && params.get('f')?.startsWith('F:') ? params.get('f') : null
  const view = folderCode ? 'folder' : VIEWS[params.get('view')] ? params.get('view') : 'needs_me'
  const cfg = folderCode ? { title: params.get('name') || folderLabel(folderCode), lane: 'all', folder: folderCode } : VIEWS[view]
  const { accounts, accountId, refreshCounts, refreshAccounts, mailEpoch, notify, setMenuOpen, openComposer, drafts, refreshDrafts, aiEnabled, counts, bumpMail } = useMail()
  const [sweep, setSweep] = useState(null) // null | 'ask' | 'busy': "Archive all" of a lane
  const [asked, setAsked] = useState(null) // { q, loading } | { q, error } | { q, result }
  const askSeq = useRef(0)

  const [qInput, setQInput] = useState('')
  const [q, setQ] = useState('')
  const [filters, setFilters] = useState(NO_FILTERS)
  const [fromInput, setFromInput] = useState('')
  const [fromOpen, setFromOpen] = useState(false)
  const filtered = filters.unread || filters.flagged || filters.hasAttachment || !!filters.since || !!filters.from
  const setFilter = (k, v) => setFilters((f) => ({ ...f, [k]: v }))
  const [items, setItems] = useState([])
  const [nextCursor, setNextCursor] = useState(null)
  const [loading, setLoading] = useState(true)
  const [loadingMore, setLoadingMore] = useState(false)
  const [error, setError] = useState('')
  const [selectedId, setSelectedId] = useState(null)
  const [syncing, setSyncing] = useState(false)
  const reqSeq = useRef(0)
  const syncTimer = useRef(null)

  // debounce search
  useEffect(() => {
    const t = setTimeout(() => setQ(qInput.trim()), 350)
    return () => clearTimeout(t)
  }, [qInput])

  // A new search, view or account leaves the AI answer behind.
  useEffect(() => {
    if (asked && asked.q !== qInput.trim()) setAsked(null)
  }, [qInput]) // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { setAsked(null) }, [view, accountId, folderCode])

  async function askAi(question = qInput.trim()) {
    if (!question || question.length < 3) return
    const seq = ++askSeq.current
    setAsked({ q: question, loading: true })
    try {
      const res = await get(`/api/ai/ask${qs({ q: question, accountId: accountId ?? '', tz: TZ })}`)
      if (seq !== askSeq.current) return
      setAsked({ q: question, result: res })
      if (res.sources?.[0] && window.matchMedia('(min-width: 768px)').matches) setSelectedId(res.sources[0])
    } catch (e) {
      if (seq === askSeq.current) setAsked({ q: question, error: e.message })
    }
  }

  useEffect(() => {
    const t = setTimeout(() => setFilter('from', fromInput.trim()), 350)
    return () => clearTimeout(t)
  }, [fromInput])

  useEffect(() => () => clearTimeout(syncTimer.current), [])

  const baseQuery = useCallback(
    (extra = {}) => qs({
      lane: cfg.lane, folder: cfg.folder, accountId: accountId ?? '', q, limit: PAGE,
      unread: filters.unread || '', flagged: filters.flagged || '', hasAttachment: filters.hasAttachment || '',
      from: filters.from, after: sinceIso(filters.since), tz: TZ, ...extra,
    }),
    [cfg.lane, cfg.folder, accountId, q, filters],
  )

  const load = useCallback(
    async ({ silent = false } = {}) => {
      if (cfg.drafts) {
        setLoading(false)
        setError('')
        return
      }
      const seq = ++reqSeq.current
      if (!silent) setLoading(true)
      setError('')
      try {
        const res = await get(`/api/messages${baseQuery()}`)
        if (seq !== reqSeq.current) return
        setItems(res?.items || [])
        setNextCursor(res?.nextCursor || null)
      } catch (e) {
        if (seq !== reqSeq.current) return
        setError(e.message)
        if (!silent) setItems([])
      } finally {
        if (seq === reqSeq.current) setLoading(false)
      }
    },
    [baseQuery, cfg.drafts],
  )

  useEffect(() => {
    load()
  }, [load, mailEpoch])

  useEffect(() => {
    if (cfg.drafts) refreshDrafts()
  }, [cfg.drafts, refreshDrafts, mailEpoch])

  async function discardDraft(d) {
    try {
      await del(`/api/drafts/${d.id}`)
      notify('Draft discarded', 'info')
    } catch (e) {
      notify(e.message, 'error')
    }
    refreshDrafts()
  }

  const shownDrafts = accountId == null ? drafts : drafts.filter((d) => d.accountId === accountId)

  // a new view or account starts with nothing open
  useEffect(() => {
    setSelectedId(null)
  }, [view, accountId, folderCode])

  // A tapped alert opens its mail (?open=123).
  const openParam = params.get('open')
  useEffect(() => {
    const id = Number(openParam)
    if (!id) return
    setSelectedId(id)
    setParams((p) => { const n = new URLSearchParams(p); n.delete('open'); return n }, { replace: true })
  }, [openParam]) // eslint-disable-line react-hooks/exhaustive-deps

  async function loadMore() {
    if (!nextCursor) return
    const seq = reqSeq.current
    setLoadingMore(true)
    try {
      const res = await get(`/api/messages${baseQuery({ cursor: nextCursor })}`)
      if (seq !== reqSeq.current) return
      setItems((prev) => {
        const have = new Set(prev.map((m) => m.id))
        return [...prev, ...(res?.items || []).filter((m) => !have.has(m.id))]
      })
      setNextCursor(res?.nextCursor || null)
    } catch (e) {
      notify(e.message, 'error')
    } finally {
      setLoadingMore(false)
    }
  }

  // Archive all of "Can wait" (or "For my info") in one go, with Undo.
  const sweepable = !folderCode && (cfg.lane === 'can_wait' || cfg.lane === 'fyi')
  const laneTotal = (() => {
    if (!sweepable) return 0
    if (accountId == null) return counts?.lanes?.[cfg.lane]?.total ?? 0
    return null // per-mailbox totals aren't counted; the dialog says "everything"
  })()
  async function archiveLane() {
    setSweep('busy')
    try {
      const res = await post('/api/messages/bulk/archive-lane', { lane: cfg.lane, accountId: accountId ?? null })
      setSweep(null)
      setItems([])
      bumpMail()
      refreshCounts()
      const msg = res.failed > 0 ? `Archived ${res.moved}. ${res.failed} couldn\u2019t be moved just now.` : `Archived ${res.moved} ✓`
      notify(msg, res.failed > 0 ? 'error' : 'success', res.ids?.length ? {
        ms: 12000,
        action: {
          label: 'Undo',
          run: async () => {
            try {
              await post('/api/messages/bulk/move', { ids: res.ids, folder: 'INBOX' })
              bumpMail()
              refreshCounts()
              notify(`Back in ${cfg.title} ✓`, 'success')
            } catch (e) {
              notify(e.message, 'error')
            }
          },
        },
      } : {})
    } catch (e) {
      setSweep(null)
      notify(e.message, 'error')
    }
  }

  async function refresh() {
    setSyncing(true)
    try {
      await post('/api/sync')
    } catch (e) {
      notify(e.message, 'error')
    }
    clearTimeout(syncTimer.current)
    syncTimer.current = setTimeout(() => {
      load({ silent: true })
      refreshCounts()
      refreshAccounts()
      setSyncing(false)
    }, 3000)
  }

  const patchLocal = (id, patch) => setItems((prev) => prev.map((m) => (m.id === id ? { ...m, ...patch } : m)))

  function removeLocal(id) {
    if (selectedId === id) {
      // like Outlook: the next message opens once this one is handled
      const i = items.findIndex((m) => m.id === id)
      const rest = items.filter((m) => m.id !== id)
      setSelectedId(i < 0 ? null : (rest[i]?.id ?? rest[i - 1]?.id ?? null))
    }
    setItems((prev) => prev.filter((m) => m.id !== id))
  }

  async function onSeen(id) {
    patchLocal(id, { seen: true })
    try {
      await post(`/api/messages/${id}/seen`, { seen: true })
      refreshCounts()
    } catch {
      /* not worth bothering anyone about */
    }
  }

  async function onFlag(id, flagged) {
    patchLocal(id, { flagged })
    try {
      await post(`/api/messages/${id}/flag`, { flagged })
    } catch (e) {
      patchLocal(id, { flagged: !flagged })
      notify(e.message, 'error')
      throw e
    }
  }

  async function onArchive(id) {
    removeLocal(id)
    try {
      await post(`/api/messages/${id}/archive`)
      notify('Done. One less thing on your plate ✓', 'success')
      refreshCounts()
    } catch (e) {
      notify(e.message, 'error')
      load({ silent: true })
    }
  }

  async function onTrash(id) {
    removeLocal(id)
    try {
      await post(`/api/messages/${id}/trash`)
      notify('Moved to Trash', 'info')
      refreshCounts()
    } catch (e) {
      notify(e.message, 'error')
      load({ silent: true })
    }
  }

  async function onMove(id, folder, label) {
    removeLocal(id)
    try {
      await post(`/api/messages/${id}/move`, { folder })
      notify(folder === 'INBOX' && (cfg.folder === 'SPAM') ? 'Not spam. Back in the Inbox ✓' : `Moved to ${label}`, 'success')
      refreshCounts()
    } catch (e) {
      notify(e.message, 'error')
      load({ silent: true })
    }
  }

  async function onSnooze(id, at) {
    const inSnoozed = cfg.folder === 'SNOOZED'
    if (at || inSnoozed) removeLocal(id)
    try {
      await post(`/api/messages/${id}/snooze`, { at })
      notify(at ? `Snoozed until ${whenLabel(at)}. It comes back on top.` : 'Back in your inbox', 'success')
      refreshCounts()
      if (!at && !inSnoozed) load({ silent: true })
    } catch (e) {
      notify(e.message, 'error')
      load({ silent: true })
    }
  }

  async function onMoveLane(id, lane, rememberSender) {
    try {
      await post(`/api/messages/${id}/lane`, { lane, rememberSender: !!rememberSender })
    } catch (e) {
      notify(e.message, 'error')
      return false
    }
    if (cfg.lane !== 'all' && cfg.lane !== lane) removeLocal(id)
    else patchLocal(id, { lane, laneSource: 'user' })
    notify(`Moved to ${LANE_LABEL[lane]}${rememberSender ? ', and future mail from this sender will go there too' : ''}`, 'success')
    refreshCounts()
    return true
  }

  const account = accountId != null ? accounts.find((a) => a.id === accountId) : null

  return (
    <div className="flex min-h-0 flex-1 gap-2.5">
      {/* message list */}
      <section className={`${selectedId ? 'hidden md:flex' : 'flex'} slab rise-3d d1 w-full shrink-0 flex-col overflow-hidden max-lg:rounded-none md:w-[340px] xl:w-[400px]`}>
        <div className="border-b border-slate-200/70 px-3 pb-2 pt-3">
          <div className="flex items-center gap-1">
            <button className="icon-btn lg:hidden" onClick={() => setMenuOpen(true)} aria-label="Open menu"><IconMenu /></button>
            <div className="min-w-0 flex-1 px-1">
              <h1 className="truncate text-base font-bold leading-tight">{cfg.title}</h1>
              <p className="truncate text-[11px] text-slate-500">
                {account ? (
                  <><span className="mr-1 inline-block h-2 w-2 rounded-full align-middle" style={{ background: account.color }} />{account.email}</>
                ) : 'All accounts'}
              </p>
            </div>
            {sweepable && items.length > 0 && (
              <button className="btn-ghost !px-2 !py-1 text-xs" onClick={() => setSweep('ask')} disabled={sweep != null} title={`Archive everything in ${cfg.title}`}>
                {sweep === 'busy' ? <span className="spinner !h-3 !w-3" /> : <IconArchive size={14} />} Archive all
              </button>
            )}
            {sweep === 'ask' && (
              <ConfirmDialog title={`Archive all of ${cfg.title}?`} confirmLabel={laneTotal ? `Archive ${laneTotal}` : 'Archive all'}
                onCancel={() => setSweep(null)} onConfirm={archiveLane}>
                {laneTotal ? `All ${laneTotal} mails` : 'Every mail'} in {cfg.title}{accountId != null ? ' for this mailbox' : ''} move{laneTotal === 1 ? 's' : ''} to Archive. Snoozed mail stays put.
                You can undo it right after, and archived mail is always in Archive and search.
              </ConfirmDialog>
            )}
            <button className="icon-btn" onClick={refresh} disabled={syncing} title="Check for new mail">
              <IconRefresh size={17} className={syncing ? 'animate-spin' : ''} />
            </button>
          </div>
          <div className="relative mt-2">
            <IconSearch size={15} className="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-slate-400" />
            <input className="input !py-1.5 !pl-8 !pr-8" type="search" placeholder={cfg.drafts ? 'Search works in your mail folders' : 'Search people, subjects, words…'}
              value={qInput} onChange={(e) => setQInput(e.target.value)} aria-label="Search mail" disabled={!!cfg.drafts}
              onKeyDown={(e) => { if (e.key === 'Enter' && aiEnabled && QUESTION.test(qInput.trim())) askAi() }}
              title="Also: from:priya  has:attachment  is:unread  is:flagged  after:2026-11-01  newer_than:7d" />
            {qInput && (
              <button className="absolute right-2 top-1/2 -translate-y-1/2 text-slate-400 hover:text-brand-ink" onClick={() => setQInput('')} aria-label="Clear search">
                <IconX size={14} />
              </button>
            )}
          </div>
          {!cfg.drafts && aiEnabled && qInput.trim().length >= 3 && !asked && (
            <button type="button" className="mt-1.5 flex w-full items-center gap-1.5 truncate rounded-lg border border-dashed border-peacock-300 bg-peacock-50/50 px-2 py-1 text-left text-xs font-semibold text-peacock-800 hover:bg-peacock-50"
              onClick={() => askAi()} title="AI turns your question into a search and answers it from your mail">
              <IconSparkle size={13} className="shrink-0" /><span className="truncate">Ask AI: “{qInput.trim()}”</span>
              {QUESTION.test(qInput.trim()) && <span className="ml-auto shrink-0 font-normal text-slate-500">Enter</span>}
            </button>
          )}
          {!cfg.drafts && (
            <div className="mt-2 flex flex-wrap items-center gap-1" role="group" aria-label="Filters">
              <Toggle on={filters.unread} onClick={() => setFilter('unread', !filters.unread)}>Unread</Toggle>
              <Toggle on={filters.flagged} onClick={() => setFilter('flagged', !filters.flagged)}><IconFlag size={11} />Flagged</Toggle>
              <Toggle on={filters.hasAttachment} onClick={() => setFilter('hasAttachment', !filters.hasAttachment)} title="Has attachments">
                <IconPaperclip size={11} />Files
              </Toggle>
              <Toggle on={!!filters.from || fromOpen} onClick={() => setFromOpen((o) => !o)} title="Only mail from someone">
                {filters.from ? <span className="max-w-[90px] truncate">From: {filters.from}</span> : 'From…'}
              </Toggle>
              <select value={filters.since} onChange={(e) => setFilter('since', e.target.value)} aria-label="When"
                className={`rounded-full border bg-transparent px-2 py-1 text-sm sm:px-1.5 sm:py-0.5 sm:text-[11px] font-semibold outline-none ${filters.since
                  ? 'border-peacock-300 bg-peacock-50 text-peacock-800' : 'border-slate-200 text-slate-600'}`}>
                {SINCE.map((s) => <option key={s.key} value={s.key}>{s.label}</option>)}
              </select>
              {filtered && (
                <button type="button" className="ml-auto text-[11px] font-semibold text-peacock-700 hover:underline"
                  onClick={() => { setFilters(NO_FILTERS); setFromInput(''); setFromOpen(false) }}>
                  Clear
                </button>
              )}
            </div>
          )}
          {!cfg.drafts && fromOpen && (
            <input className="input mt-1.5 !py-1 text-xs" type="search" placeholder="Name or address, e.g. priya or @vendor.com" autoFocus
              value={fromInput} onChange={(e) => setFromInput(e.target.value)} aria-label="From"
              onKeyDown={(e) => e.key === 'Escape' && setFromOpen(false)} />
          )}
        </div>
        <div className="scroll-thin min-h-0 flex-1 overflow-y-auto max-md:pb-24">
          {asked && (
            <AskCard question={asked.q} state={asked} items={asked.result?.items} onOpen={setSelectedId}
              onClose={() => setAsked(null)} onRetry={() => askAi(asked.q)} />
          )}
          {cfg.drafts ? (
            shownDrafts.length ? (
              <DraftList drafts={shownDrafts} accounts={accounts}
                onOpen={(d) => openComposer({ mode: d.mode || 'new', draft: d })} onDiscard={discardDraft} />
            ) : <EmptyState view="drafts" accountChosen={accountId != null} />
          ) : (
          <MessageList
            items={asked ? asked.result?.items || [] : items}
            loading={asked ? !!asked.loading : loading}
            error={asked ? null : error}
            selectedId={selectedId}
            sentView={view === 'sent'}
            nextCursor={asked ? null : nextCursor}
            loadingMore={loadingMore}
            onLoadMore={loadMore}
            onRetry={() => load()}
            onSelect={setSelectedId}
            onDone={onArchive}
            onTrash={onTrash}
            onMoveLane={onMoveLane}
            onMove={onMove}
            onSnooze={onSnooze}
            empty={asked ? (asked.error ? <span /> : <EmptyState q={asked.q} />) : <EmptyState view={view} q={q} filtered={filtered} accountChosen={accountId != null} />}
          />
          )}
        </div>
      </section>

      {/* phones: Compose stays one thumb away while the list is showing */}
      {!selectedId && (
        <button type="button" className="btn-primary btn-compose fab md:hidden" onClick={() => openComposer({ mode: 'new' })}>
          <span className="disc"><IconCompose size={14} strokeWidth={2.2} /></span>
          Compose
        </button>
      )}

      {/* reading pane */}
      <section className={`${selectedId ? 'flex' : 'hidden md:flex'} slab rise-3d d2 min-w-0 flex-1 flex-col overflow-hidden max-lg:rounded-none`}>
        {selectedId ? (
          <ReadingPane
            key={selectedId}
            id={selectedId}
            onBack={() => setSelectedId(null)}
            onSeen={onSeen}
            onFlag={onFlag}
            onArchive={onArchive}
            onTrash={onTrash}
            onMoveLane={onMoveLane}
            onMove={onMove}
            onSnooze={onSnooze}
            onNote={(id, has) => patchLocal(id, { hasNote: has })}
          />
        ) : cfg.drafts ? (
          <div className="flex flex-1 flex-col items-center justify-center p-8 text-center">
            <IconDraft size={44} className="text-slate-400" />
            <p className="mt-4 text-lg font-bold text-brand-ink">Pick a draft to finish it</p>
            <p className="mx-auto mt-1 max-w-xs text-sm text-slate-500">Drafts save themselves as you type and are kept in MailLook, so they wait for you on any device.</p>
          </div>
        ) : (
          <div className="flex flex-1 flex-col items-center justify-center p-8 text-center">
            <img src="/brand/maillook-mark.svg" alt="" width="112" height="112" className="reflect float-slow" style={{ filter: "drop-shadow(0 18px 30px rgba(0,0,0,.55))" }} />
            <p className="mt-14 text-lg font-bold text-brand-ink">Pick a message to read</p>
            <p className="mt-1 text-sm text-slate-500">Hover a message to mark it Done, move it, or trash it.</p>
          </div>
        )}
      </section>
    </div>
  )
}
