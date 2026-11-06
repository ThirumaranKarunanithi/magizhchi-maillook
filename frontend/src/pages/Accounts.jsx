import { useMemo, useState } from 'react'
import { del, patch, post } from '../api.js'
import { useMail } from '../state.jsx'
import { PRESET_COLORS, relativeTime } from '../util.js'
import PageHeader from '../components/PageHeader.jsx'
import AddAccountForm from '../components/AddAccountForm.jsx'
import BulkAdd from '../components/BulkAdd.jsx'
import ConfirmDialog from '../components/ConfirmDialog.jsx'
import { AccountDot } from '../components/Sidebar.jsx'
import { IconCheck, IconRefresh, IconTrash } from '../components/Icons.jsx'

const STATUS = {
  ok: { label: 'Up to date', cls: 'bg-emerald-50 text-emerald-700 border-emerald-200' },
  syncing: { label: 'Syncing…', cls: 'bg-peacock-50 text-peacock-700 border-peacock-200' },
  error: { label: 'Needs attention', cls: 'bg-red-50 text-red-700 border-red-200' },
  new: { label: 'New', cls: 'bg-amber-50 text-amber-800 border-amber-200' },
}

function AccountCard({ account, onChanged, onRemove }) {
  const { notify } = useMail()
  const [name, setName] = useState(account.displayName || '')
  const [savingName, setSavingName] = useState(false)
  const [pwOpen, setPwOpen] = useState(false)
  const [password, setPassword] = useState('')
  const [pwBusy, setPwBusy] = useState(false)
  const [pwError, setPwError] = useState('')
  const [syncing, setSyncing] = useState(false)
  const status = STATUS[account.status] || STATUS.new

  async function save(body) {
    const updated = await patch(`/api/accounts/${account.id}`, body)
    onChanged(updated)
    return updated
  }

  async function saveName() {
    if ((name.trim() || '') === (account.displayName || '')) return
    setSavingName(true)
    try {
      await save({ displayName: name.trim() })
      notify('Renamed ✓', 'success')
    } catch (e) {
      notify(e.message, 'error')
      setName(account.displayName || '')
    } finally {
      setSavingName(false)
    }
  }

  async function saveColor(color) {
    try {
      await save({ color })
    } catch (e) {
      notify(e.message, 'error')
    }
  }

  async function savePassword(e) {
    e.preventDefault()
    setPwBusy(true)
    setPwError('')
    try {
      await save({ password })
      setPassword('')
      setPwOpen(false)
      notify('Password updated ✓', 'success')
    } catch (err) {
      setPwError(err.message)
    } finally {
      setPwBusy(false)
    }
  }

  async function syncNow() {
    setSyncing(true)
    try {
      await post(`/api/accounts/${account.id}/sync`)
      onChanged({ ...account, status: 'syncing' })
      notify(`Syncing ${account.email}…`)
    } catch (e) {
      notify(e.message, 'error')
    } finally {
      setSyncing(false)
    }
  }

  return (
    <li className="card p-4">
      <div className="flex flex-wrap items-start gap-3">
        <div className="pt-1.5"><AccountDot color={account.color} size={12} /></div>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <span className="truncate font-semibold text-brand-ink">{account.email}</span>
            <span className={`rounded-full border px-2 py-px text-[11px] font-semibold ${status.cls}`}>{status.label}</span>
            {account.unread > 0 && <span className="text-xs font-semibold text-brand-primary">{account.unread} unread</span>}
          </div>
          <p className="mt-0.5 text-xs text-slate-500">
            {account.provider || 'mail'} · {account.imapHost}:{account.imapPort} · last synced {relativeTime(account.lastSyncAt)}
          </p>
          {account.lastError && <p className="mt-1 rounded-md bg-red-50 px-2 py-1 text-xs text-red-700">{account.lastError}</p>}
        </div>
        <div className="flex flex-wrap gap-1.5">
          <button className="btn-secondary !py-1 text-xs" onClick={syncNow} disabled={syncing}>
            <IconRefresh size={14} className={syncing ? 'animate-spin' : ''} /> Sync now
          </button>
          <button className="btn-secondary !py-1 text-xs" onClick={() => { setPwOpen((o) => !o); setPassword(''); setPwError('') }}>
            Update password
          </button>
          <button className="btn-secondary !py-1 text-xs hover:!text-red-700" onClick={() => onRemove(account)}>
            <IconTrash size={14} /> Remove
          </button>
        </div>
      </div>

      <div className="mt-3 flex flex-wrap items-end gap-4 border-t border-slate-100 pt-3">
        <div className="min-w-[200px] flex-1">
          <label className="label" htmlFor={`name-${account.id}`}>Name</label>
          <div className="flex gap-2">
            <input id={`name-${account.id}`} className="input !py-1.5" value={name} placeholder={account.email}
              onChange={(e) => setName(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && saveName()} />
            {name.trim() !== (account.displayName || '') && (
              <button className="btn-secondary !py-1 text-xs" onClick={saveName} disabled={savingName}>
                {savingName ? <span className="spinner" /> : <IconCheck size={14} />} Save
              </button>
            )}
          </div>
        </div>
        <div>
          <span className="label">Colour</span>
          <div className="flex flex-wrap gap-1.5" role="radiogroup" aria-label={`Colour for ${account.email}`}>
            {PRESET_COLORS.map((c) => {
              const on = (account.color || '').toLowerCase() === c
              return (
                <button key={c} role="radio" aria-checked={on} title={c} onClick={() => !on && saveColor(c)}
                  className="h-6 w-6 rounded-full transition-transform hover:scale-110"
                  style={{ background: c, boxShadow: on ? `0 0 0 2px #0b2025, 0 0 0 4px ${c}` : 'inset 0 1px 0 rgba(255,255,255,.35), 0 2px 6px rgba(0,0,0,.4)' }} />
              )
            })}
          </div>
        </div>
      </div>

      {pwOpen && (
        <form onSubmit={savePassword} className="mt-3 flex flex-wrap items-end gap-2 rounded-lg border border-slate-200 bg-slate-50/70 p-3">
          <div className="min-w-[220px] flex-1">
            <label className="label" htmlFor={`pw-${account.id}`}>New password for {account.email}</label>
            <input id={`pw-${account.id}`} className="input" type="password" autoComplete="new-password" required autoFocus
              value={password} onChange={(e) => setPassword(e.target.value)} />
          </div>
          <button className="btn-primary" disabled={pwBusy || !password}>{pwBusy && <span className="spinner" />} Save password</button>
          <button type="button" className="btn-ghost" onClick={() => { setPwOpen(false); setPassword('') }}>Cancel</button>
          {pwError && <p className="w-full text-sm text-red-700">{pwError}</p>}
        </form>
      )}
    </li>
  )
}

export default function Accounts() {
  const { accounts, setAccounts, accountsLoaded, refreshAccounts, refreshCounts, notify } = useMail()
  const [tab, setTab] = useState('one')
  const [removing, setRemoving] = useState(null)
  const [removeBusy, setRemoveBusy] = useState(false)
  const existing = useMemo(() => new Set(accounts.map((a) => a.email.toLowerCase())), [accounts])

  function changed(updated) {
    if (updated?.id != null) setAccounts((list) => list.map((a) => (a.id === updated.id ? { ...a, ...updated } : a)))
  }

  async function confirmRemove() {
    setRemoveBusy(true)
    try {
      await del(`/api/accounts/${removing.id}`)
      notify(`${removing.email} removed from Magizhchi MailLook`, 'success')
      setRemoving(null)
      await refreshAccounts()
      refreshCounts()
    } catch (e) {
      notify(e.message, 'error')
    } finally {
      setRemoveBusy(false)
    }
  }

  return (
    <div className="scroll-thin flex min-h-0 flex-1 flex-col overflow-y-auto">
      <PageHeader title="Manage accounts" subtitle="Every mailbox you read in Magizhchi MailLook">
        <button className="btn-secondary" onClick={refreshAccounts}><IconRefresh size={15} /> Refresh</button>
      </PageHeader>

      <div className="mx-auto w-full max-w-4xl space-y-6 px-4 py-6 sm:px-6">
        <section>
          <h2 className="mb-2 text-sm font-bold uppercase tracking-wider text-slate-500">Your mailboxes ({accounts.length})</h2>
          {!accountsLoaded ? (
            <div className="card p-6 text-center text-slate-400"><span className="spinner" /></div>
          ) : accounts.length === 0 ? (
            <div className="card p-6 text-center text-sm text-slate-500">No mailboxes yet. Add your first one below.</div>
          ) : (
            <ul className="space-y-3">
              {accounts.map((a) => (
                <AccountCard key={a.id} account={a} onChanged={changed} onRemove={setRemoving} />
              ))}
            </ul>
          )}
        </section>

        <section className="card p-5">
          <div className="mb-4 flex flex-wrap items-center gap-2">
            <h2 className="mr-auto text-base font-bold">Add mailboxes</h2>
            <div className="flex rounded-lg border border-slate-200 bg-slate-50 p-0.5 text-sm" role="tablist">
              <button role="tab" aria-selected={tab === 'one'} onClick={() => setTab('one')}
                className={`rounded-md px-3 py-1 font-medium ${tab === 'one' ? 'bg-white shadow-sm' : 'text-slate-600'}`}>One account</button>
              <button role="tab" aria-selected={tab === 'bulk'} onClick={() => setTab('bulk')}
                className={`rounded-md px-3 py-1 font-medium ${tab === 'bulk' ? 'bg-white shadow-sm' : 'text-slate-600'}`}>Many at once</button>
            </div>
          </div>
          {tab === 'one' ? (
            <AddAccountForm onAdded={(a) => { notify(`${a?.email || 'Account'} added. Syncing your mail now 🎉`, 'success'); refreshAccounts(); refreshCounts() }} />
          ) : (
            <BulkAdd existingEmails={existing} onDone={() => { refreshAccounts(); refreshCounts() }} />
          )}
        </section>
      </div>

      {removing && (
        <ConfirmDialog
          title={`Remove ${removing.email}?`}
          confirmLabel="Remove"
          danger
          busy={removeBusy}
          onConfirm={confirmRemove}
          onCancel={() => !removeBusy && setRemoving(null)}
        >
          This removes it from Magizhchi MailLook only. The mailbox itself and all its mail stay exactly as they are, and you can add it back any time.
        </ConfirmDialog>
      )}
    </div>
  )
}
