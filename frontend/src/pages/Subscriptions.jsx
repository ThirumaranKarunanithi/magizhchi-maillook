import { useCallback, useEffect, useState } from 'react'
import { get, post } from '../api.js'
import { useMail } from '../state.jsx'
import { safeHttpUrl, shortTime } from '../util.js'
import PageHeader from '../components/PageHeader.jsx'
import { IconCheck, IconRefresh } from '../components/Icons.jsx'

export default function Subscriptions() {
  const { accounts, accountId } = useMail()
  const [rows, setRows] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [results, setResults] = useState({}) // latestMessageId -> { busy } | { status, message, url }

  const load = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const list = await get('/api/subscriptions')
      setRows(Array.isArray(list) ? list : [])
    } catch (e) {
      setError(e.message)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  async function unsubscribe(row) {
    const id = row.latestMessageId
    setResults((r) => ({ ...r, [id]: { busy: true } }))
    try {
      const res = await post(`/api/messages/${id}/unsubscribe`)
      let url = null
      if (res?.status === 'open_url') {
        url = safeHttpUrl(res.url)
        if (url) window.open(url, '_blank', 'noopener,noreferrer')
      }
      setResults((r) => ({ ...r, [id]: { ...(res || { status: 'done' }), url } }))
    } catch (e) {
      setResults((r) => ({ ...r, [id]: { status: 'error', message: e.message } }))
    }
  }

  const shown = accountId == null ? rows : rows.filter((r) => r.accountId === accountId)
  const account = accountId != null ? accounts.find((a) => a.id === accountId) : null
  const colorOf = (id) => accounts.find((a) => a.id === id)?.color || '#0e7c86'

  return (
    <div className="scroll-thin flex min-h-0 flex-1 flex-col overflow-y-auto">
      <PageHeader title="Subscriptions" subtitle={`Newsletters and mailing lists${account ? ` in ${account.email}` : ''}, busiest first`}>
        <button className="btn-secondary" onClick={load} disabled={loading}>
          <IconRefresh size={15} className={loading ? 'animate-spin' : ''} /> Refresh
        </button>
      </PageHeader>

      <div className="mx-auto w-full max-w-5xl px-4 py-6 sm:px-6">
        {error && <p className="mb-4 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p>}
        {loading && !rows.length ? (
          <div className="card p-8 text-center text-slate-400"><span className="spinner" /></div>
        ) : !shown.length ? (
          <div className="card px-6 py-12 text-center">
            <div className="text-4xl">🕊️</div>
            <p className="mt-2 text-lg font-bold">No subscriptions found</p>
            <p className="mt-1 text-sm text-slate-500">Nobody is filling your inbox with newsletters. Peaceful.</p>
          </div>
        ) : (
          <div className="card overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="border-b border-slate-200 text-left text-xs text-slate-500">
                <tr>
                  <th className="px-4 py-2.5 font-semibold">Sender</th>
                  <th className="hidden px-3 py-2.5 font-semibold md:table-cell">Account</th>
                  <th className="px-3 py-2.5 text-right font-semibold">Mails</th>
                  <th className="px-3 py-2.5 text-right font-semibold">Unread</th>
                  <th className="hidden px-3 py-2.5 font-semibold sm:table-cell">Last</th>
                  <th className="px-4 py-2.5 text-right font-semibold"><span className="sr-only">Action</span></th>
                </tr>
              </thead>
              <tbody>
                {shown.map((r) => {
                  const res = results[r.latestMessageId]
                  return (
                    <tr key={`${r.accountId}-${r.fromEmail}`} className="border-b border-slate-100 last:border-0">
                      <td className="max-w-[260px] px-4 py-2.5">
                        <div className="truncate font-semibold">{r.fromName || r.fromEmail}</div>
                        {r.fromName && <div className="truncate text-xs text-slate-500">{r.fromEmail}</div>}
                      </td>
                      <td className="hidden max-w-[200px] px-3 py-2.5 md:table-cell">
                        <span className="flex items-center gap-1.5 truncate text-xs text-slate-600">
                          <span className="h-2 w-2 shrink-0 rounded-full" style={{ background: colorOf(r.accountId) }} />
                          <span className="truncate">{r.accountEmail}</span>
                        </span>
                      </td>
                      <td className="px-3 py-2.5 text-right num">{r.count}</td>
                      <td className="px-3 py-2.5 text-right num">{r.unread > 0 ? <span className="font-semibold text-brand-primary">{r.unread}</span> : <span className="text-slate-400">0</span>}</td>
                      <td className="hidden whitespace-nowrap px-3 py-2.5 text-xs text-slate-500 sm:table-cell">{shortTime(r.lastAt)}</td>
                      <td className="px-4 py-2.5 text-right">
                        {res?.busy ? (
                          <span className="spinner text-slate-400" />
                        ) : res?.status === 'done' ? (
                          <span className="inline-flex items-center gap-1 text-xs font-semibold text-emerald-700" title={res.message}>
                            <IconCheck size={14} /> {res.message || 'Unsubscribed'}
                          </span>
                        ) : res?.status === 'open_url' ? (
                          <span className="text-xs text-slate-600">
                            {res.message || 'Finish in the page that opened.'}{' '}
                            {res.url && <a className="font-semibold text-peacock-600 underline" href={res.url} target="_blank" rel="noopener noreferrer">Open page</a>}
                          </span>
                        ) : (
                          <div className="flex flex-col items-end gap-1">
                            <button className="btn-secondary !py-1 text-xs" onClick={() => unsubscribe(r)}>Unsubscribe</button>
                            {res?.status === 'error' && <span className="text-xs text-red-700">{res.message}</span>}
                          </div>
                        )}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  )
}
