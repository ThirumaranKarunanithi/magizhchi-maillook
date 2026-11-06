import { useMemo, useState } from 'react'
import { post } from '../api.js'
import { EMAIL_RE, splitAddresses } from '../util.js'
import { IconCheck, IconX } from './Icons.jsx'

/**
 * Paste many addresses, give each a password, add them all at once.
 * Passwords live only in component state and are cleared once a row is added; nothing is logged or stored.
 */
export default function BulkAdd({ existingEmails, onDone }) {
  const [text, setText] = useState('')
  const [passwords, setPasswords] = useState({}) // lowercased email -> password
  const [shared, setShared] = useState('')
  const [results, setResults] = useState({}) // lowercased email -> { ok, error }
  const [busy, setBusy] = useState(false)

  const rows = useMemo(
    () =>
      splitAddresses(text).map((email) => {
        const key = email.toLowerCase()
        return { email, key, valid: EMAIL_RE.test(email), exists: existingEmails.has(key) }
      }),
    [text, existingEmails],
  )

  const ready = rows.filter((r) => r.valid && !r.exists && !results[r.key]?.ok)
  const missing = ready.filter((r) => !passwords[r.key])

  function fillEmpty() {
    if (!shared) return
    setPasswords((p) => {
      const next = { ...p }
      for (const r of ready) if (!next[r.key]) next[r.key] = shared
      return next
    })
  }

  async function addAll() {
    const batch = ready.filter((r) => passwords[r.key])
    if (!batch.length) return
    setBusy(true)
    try {
      const res = await post('/api/accounts/bulk', {
        accounts: batch.map((r) => ({ email: r.email, password: passwords[r.key] })),
      })
      const next = {}
      for (const r of res?.results || []) next[String(r.email).toLowerCase()] = r.ok ? { ok: true } : { ok: false, error: r.error || 'Could not add' }
      setResults((old) => ({ ...old, ...next }))
      // forget passwords of rows that made it
      setPasswords((p) => {
        const keep = { ...p }
        for (const [k, v] of Object.entries(next)) if (v.ok) delete keep[k]
        return keep
      })
      if (Object.values(next).some((v) => v.ok)) onDone?.()
    } catch (e) {
      const next = {}
      for (const r of batch) next[r.key] = { ok: false, error: e.message }
      setResults((old) => ({ ...old, ...next }))
    } finally {
      setBusy(false)
    }
  }

  function clearAll() {
    setText('')
    setPasswords({})
    setShared('')
    setResults({})
  }

  const added = rows.filter((r) => results[r.key]?.ok).length

  return (
    <div className="space-y-3">
      <div>
        <label className="label" htmlFor="bulk-text">Paste your addresses: one per line, or separated by commas</label>
        <textarea id="bulk-text" className="input min-h-[110px] font-mono text-[13px]" value={text} onChange={(e) => setText(e.target.value)}
          placeholder={'info@magizhchi.academy\nadmissions@magizhchi.academy\naccounts@magizhchi.academy'} spellCheck={false} />
      </div>

      {rows.length > 0 && (
        <>
          <div className="flex flex-wrap items-end gap-2 rounded-lg border border-amber-200 bg-amber-50/60 p-3">
            <div className="min-w-[200px] flex-1">
              <label className="label" htmlFor="bulk-shared">Same password for all</label>
              <input id="bulk-shared" className="input" type="password" autoComplete="new-password" value={shared}
                onChange={(e) => setShared(e.target.value)} placeholder="Fills the rows you left empty" />
            </div>
            <button type="button" className="btn-secondary" onClick={fillEmpty} disabled={!shared || !missing.length}>
              Fill {missing.length || ''} empty
            </button>
          </div>

          <div className="overflow-x-auto rounded-lg border border-slate-200">
            <table className="w-full text-sm">
              <thead className="bg-slate-50 text-left text-xs text-slate-500">
                <tr>
                  <th className="px-3 py-2 font-semibold">Mailbox</th>
                  <th className="px-3 py-2 font-semibold">Password</th>
                  <th className="w-[40%] px-3 py-2 font-semibold">Result</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => {
                  const res = results[r.key]
                  return (
                    <tr key={r.key} className="border-t border-slate-100 align-middle">
                      <td className="max-w-[240px] truncate px-3 py-1.5 font-medium" title={r.email}>{r.email}</td>
                      <td className="px-3 py-1.5">
                        {r.valid && !r.exists && !res?.ok ? (
                          <input className="input !py-1" type="password" autoComplete="new-password" aria-label={`Password for ${r.email}`}
                            value={passwords[r.key] || ''} onChange={(e) => setPasswords((p) => ({ ...p, [r.key]: e.target.value }))} />
                        ) : (
                          <span className="text-xs text-slate-400">—</span>
                        )}
                      </td>
                      <td className="px-3 py-1.5 text-xs">
                        {!r.valid ? (
                          <span className="text-red-700">Doesn't look like an email</span>
                        ) : res?.ok ? (
                          <span className="inline-flex items-center gap-1 font-semibold text-emerald-700"><IconCheck size={14} /> Added</span>
                        ) : r.exists ? (
                          <span className="text-slate-500">Already added</span>
                        ) : res ? (
                          <span className="inline-flex items-start gap-1 text-red-700"><IconX size={14} className="mt-px shrink-0" /> {res.error}</span>
                        ) : !passwords[r.key] ? (
                          <span className="text-amber-700">Needs a password</span>
                        ) : (
                          <span className="text-slate-500">Ready</span>
                        )}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>

          <div className="flex flex-wrap items-center gap-3">
            <button type="button" className="btn-primary" onClick={addAll} disabled={busy || !ready.some((r) => passwords[r.key])}>
              {busy && <span className="spinner" />} {busy ? 'Checking each mailbox…' : `Add all (${ready.filter((r) => passwords[r.key]).length})`}
            </button>
            <button type="button" className="btn-ghost" onClick={clearAll} disabled={busy}>Clear</button>
            {added > 0 && <span className="text-sm font-medium text-emerald-700">{added} added. Welcome aboard! 🎉</span>}
          </div>
        </>
      )}
    </div>
  )
}
