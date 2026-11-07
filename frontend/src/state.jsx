import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react'
import { get, post } from './api.js'

// App-wide mail state shared by the sidebar, pages and the composer.
const MailCtx = createContext(null)

export function useMail() {
  return useContext(MailCtx)
}

const COUNTS_POLL_MS = 60_000

export function MailProvider({ children }) {
  const [accounts, setAccounts] = useState([])
  const [accountsLoaded, setAccountsLoaded] = useState(false)
  const [counts, setCounts] = useState(null)
  const [aiEnabled, setAiEnabled] = useState(null) // null = still asking
  const [aiInfo, setAiInfo] = useState(null) // { provider, message }
  const [pa, setPa] = useState(null) // Magizhchi PA connection: { connected, aiEnabled, message, webUrl }
  const [accountId, setAccountId] = useState(null) // null = all accounts
  const [composer, setComposer] = useState(null) // { mode, message?, draftBody? } or null
  const [menuOpen, setMenuOpen] = useState(false)
  const [mailEpoch, setMailEpoch] = useState(0) // bump to make the list reload
  const [toast, setToast] = useState(null)
  const [drafts, setDrafts] = useState([]) // unsent mail, newest first
  const [folders, setFolders] = useState(null) // per mailbox: { accountId, email, folders: [{ code, name, path, opened }], error? }
  const toastTimer = useRef(null)

  const refreshAccounts = useCallback(async () => {
    try {
      const list = await get('/api/accounts')
      setAccounts(Array.isArray(list) ? list : [])
    } catch {
      /* keep what we have */
    } finally {
      setAccountsLoaded(true)
    }
  }, [])

  const refreshDrafts = useCallback(async () => {
    try {
      const list = await get('/api/drafts')
      setDrafts(Array.isArray(list) ? list : [])
    } catch {
      /* keep what we have */
    }
  }, [])

  // Listing folders asks each mail server, so it happens once, when someone opens the folder list.
  const refreshFolders = useCallback(async (refresh = false) => {
    try {
      const list = await get(`/api/folders${refresh ? '?refresh=true' : ''}`)
      setFolders(Array.isArray(list) ? list : [])
    } catch (e) {
      setFolders((prev) => prev || [])
      throw e
    }
  }, [])

  /** Starts syncing one of a mailbox's own folders; returns its code. */
  const openFolder = useCallback(async (accountId, path) => {
    const res = await post('/api/folders/open', { accountId, path })
    setFolders((prev) => (prev || []).map((a) => (a.accountId !== accountId ? a : {
      ...a, folders: a.folders.map((f) => (f.path === path ? { ...f, opened: true } : f)),
    })))
    return res
  }, [])

  const refreshCounts = useCallback(async () => {
    try {
      setCounts(await get('/api/counts'))
    } catch {
      /* the gauge just keeps its last value */
    }
  }, [])

  useEffect(() => {
    refreshAccounts()
    refreshCounts()
    refreshDrafts()
    get('/api/ai/status')
      .then((s) => {
        setAiEnabled(!!s?.enabled)
        setAiInfo(s || null)
      })
      .catch(() => setAiEnabled(false))
    get('/api/pa/status').then(setPa).catch(() => setPa({ connected: false }))
    const t = setInterval(refreshCounts, COUNTS_POLL_MS)
    return () => clearInterval(t)
  }, [refreshAccounts, refreshCounts, refreshDrafts])

  // If the selected account goes away, fall back to all accounts.
  useEffect(() => {
    if (accountId != null && accountsLoaded && !accounts.some((a) => a.id === accountId)) setAccountId(null)
  }, [accounts, accountsLoaded, accountId])

  const notify = useCallback((text, kind = 'info') => {
    clearTimeout(toastTimer.current)
    setToast({ text, kind, at: Date.now() })
    toastTimer.current = setTimeout(() => setToast(null), 4000)
  }, [])

  const openComposer = useCallback((opts = { mode: 'new' }) => setComposer({ ...opts, key: Date.now() }), [])
  const closeComposer = useCallback(() => setComposer(null), [])
  const bumpMail = useCallback(() => setMailEpoch((n) => n + 1), [])

  const value = {
    accounts, setAccounts, accountsLoaded, refreshAccounts,
    counts, refreshCounts,
    aiEnabled, setAiEnabled, aiInfo,
    pa,
    accountId, setAccountId,
    composer, openComposer, closeComposer,
    drafts, refreshDrafts,
    folders, refreshFolders, openFolder,
    menuOpen, setMenuOpen,
    mailEpoch, bumpMail,
    toast, notify,
  }
  return <MailCtx.Provider value={value}>{children}</MailCtx.Provider>
}
