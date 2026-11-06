import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react'
import { get } from './api.js'

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
    get('/api/ai/status')
      .then((s) => {
        setAiEnabled(!!s?.enabled)
        setAiInfo(s || null)
      })
      .catch(() => setAiEnabled(false))
    get('/api/pa/status').then(setPa).catch(() => setPa({ connected: false }))
    const t = setInterval(refreshCounts, COUNTS_POLL_MS)
    return () => clearInterval(t)
  }, [refreshAccounts, refreshCounts])

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
    menuOpen, setMenuOpen,
    mailEpoch, bumpMail,
    toast, notify,
  }
  return <MailCtx.Provider value={value}>{children}</MailCtx.Provider>
}
