import { useCallback, useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { get, post, qs } from '../api.js'
import { useMail } from '../state.jsx'
import { LANE_LABEL, VIEWS } from '../util.js'
import MessageList from '../components/MessageList.jsx'
import ReadingPane from '../components/ReadingPane.jsx'
import { IconCompose, IconMenu, IconRefresh, IconSearch, IconX } from '../components/Icons.jsx'

const PAGE = 50

const EMPTY = {
  needs_me: { emoji: '🎉', title: 'Nothing needs you right now', text: 'Nobody is waiting on you. Go make yourself a nice cup of chai.' },
  fyi: { emoji: '☀️', title: "You're all caught up", text: 'No news to skim. Enjoy the quiet.' },
  can_wait: { emoji: '🌼', title: 'Nothing waiting', text: 'The "later" pile is empty. Lovely.' },
  all: { emoji: '🌸', title: 'Inbox zero. Magizhchi!', text: 'Every message is handled. That is what happy looks like.' },
  sent: { emoji: '✉️', title: 'Nothing sent yet', text: 'Mail you send from Magizhchi MailLook shows up here.' },
  archive: { emoji: '📦', title: 'The archive is empty', text: 'Press Done on a message and it rests here.' },
}

function EmptyState({ view, q, accountChosen }) {
  if (q) {
    return (
      <div className="px-6 py-14 text-center">
        <div className="text-3xl">🔍</div>
        <p className="mt-2 font-semibold">No mail matches “{q}”</p>
        <p className="mt-1 text-sm text-slate-500">Try a name, a word from the subject, or fewer words.</p>
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
  const [params] = useSearchParams()
  const view = VIEWS[params.get('view')] ? params.get('view') : 'needs_me'
  const cfg = VIEWS[view]
  const { accounts, accountId, refreshCounts, refreshAccounts, mailEpoch, notify, setMenuOpen, openComposer } = useMail()

  const [qInput, setQInput] = useState('')
  const [q, setQ] = useState('')
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

  useEffect(() => () => clearTimeout(syncTimer.current), [])

  const baseQuery = useCallback(
    (extra = {}) => qs({ lane: cfg.lane, folder: cfg.folder, accountId: accountId ?? '', q, limit: PAGE, ...extra }),
    [cfg.lane, cfg.folder, accountId, q],
  )

  const load = useCallback(
    async ({ silent = false } = {}) => {
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
    [baseQuery],
  )

  useEffect(() => {
    load()
  }, [load, mailEpoch])

  // a new view or account starts with nothing open
  useEffect(() => {
    setSelectedId(null)
  }, [view, accountId])

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
            <button className="icon-btn" onClick={refresh} disabled={syncing} title="Check for new mail">
              <IconRefresh size={17} className={syncing ? 'animate-spin' : ''} />
            </button>
          </div>
          <div className="relative mt-2">
            <IconSearch size={15} className="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-slate-400" />
            <input className="input !py-1.5 !pl-8 !pr-8" type="search" placeholder="Search people, subjects, words…"
              value={qInput} onChange={(e) => setQInput(e.target.value)} aria-label="Search mail" />
            {qInput && (
              <button className="absolute right-2 top-1/2 -translate-y-1/2 text-slate-400 hover:text-brand-ink" onClick={() => setQInput('')} aria-label="Clear search">
                <IconX size={14} />
              </button>
            )}
          </div>
        </div>
        <div className="scroll-thin min-h-0 flex-1 overflow-y-auto max-md:pb-24">
          <MessageList
            items={items}
            loading={loading}
            error={error}
            selectedId={selectedId}
            sentView={view === 'sent'}
            nextCursor={nextCursor}
            loadingMore={loadingMore}
            onLoadMore={loadMore}
            onRetry={() => load()}
            onSelect={setSelectedId}
            onDone={onArchive}
            onTrash={onTrash}
            onMoveLane={onMoveLane}
            empty={<EmptyState view={view} q={q} accountChosen={accountId != null} />}
          />
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
          />
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
