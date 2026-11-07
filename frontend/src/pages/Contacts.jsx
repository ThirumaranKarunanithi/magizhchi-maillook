import { useCallback, useEffect, useState } from 'react'
import { get, post, qs } from '../api.js'
import { useMail } from '../state.jsx'
import { EMAIL_RE } from '../util.js'
import { whatsApp } from '../components/PersonCard.jsx'
import { IconBook, IconCompose, IconMenu, IconPhone, IconPlus, IconSearch, IconUserPlus, IconX } from '../components/Icons.jsx'

const SOURCE = { box: 'Box', both: 'Box · mail', mail: 'From mail' }

function initials(name, email) {
  const s = (name || email || '?').replace(/[^\p{L}\p{N} ]/gu, ' ').trim()
  const p = s.split(/\s+/)
  return ((p[0]?.[0] || '') + (p.length > 1 ? p[p.length - 1][0] : '')).toUpperCase() || '?'
}

/** A steady colour per person, from the account palette. */
function hue(s) {
  const colors = ['#0e7c86', '#b45309', '#be185d', '#7c3aed', '#2563eb', '#15803d', '#c2410c', '#0b5563']
  let h = 0
  for (const ch of String(s || '')) h = (h * 31 + ch.charCodeAt(0)) >>> 0
  return colors[h % colors.length]
}

function AddForm({ onSaved, onClose }) {
  const { notify } = useMail()
  const [f, setF] = useState({ name: '', email: '', phone: '', org: '' })
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const set = (k) => (e) => setF((x) => ({ ...x, [k]: e.target.value }))

  async function save(e) {
    e.preventDefault()
    if (!EMAIL_RE.test(f.email.trim())) return setError('Add an email address, like priya@example.com.')
    setBusy(true)
    setError('')
    try {
      const res = await post('/api/contacts', { name: f.name.trim() || null, email: f.email.trim(), phone: f.phone.trim() || null, org: f.org.trim() || null })
      notify(res?.message || 'Saved to your Magizhchi Box contacts.', 'success')
      onSaved()
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <form onSubmit={save} className="rounded-xl border border-slate-200 bg-white/80 p-3">
      <div className="mb-2 flex items-center gap-2">
        <IconUserPlus size={16} className="text-brand-primary" />
        <h2 className="flex-1 text-sm font-bold">New contact</h2>
        <button type="button" className="icon-btn !h-7 !w-7" onClick={onClose} aria-label="Close"><IconX size={14} /></button>
      </div>
      <div className="grid gap-2 sm:grid-cols-2">
        <label className="block"><span className="label">Name</span><input className="input" value={f.name} onChange={set('name')} autoFocus maxLength={200} /></label>
        <label className="block"><span className="label">Email</span><input className="input" type="email" value={f.email} onChange={set('email')} required /></label>
        <label className="block"><span className="label">Phone</span><input className="input" type="tel" value={f.phone} onChange={set('phone')} maxLength={40} /></label>
        <label className="block"><span className="label">Company or school</span><input className="input" value={f.org} onChange={set('org')} maxLength={200} /></label>
      </div>
      {error && <p className="mt-2 text-sm text-red-700" role="alert">{error}</p>}
      <div className="mt-3 flex items-center gap-2">
        <button className="btn-primary" disabled={busy}>{busy && <span className="spinner" />} Save to Box</button>
        <span className="text-xs text-slate-500">Same name as someone in Box? The email is added to their card.</span>
      </div>
    </form>
  )
}

/**
 * The address book: your Magizhchi Box contacts (the same book as the Box app on your phone, which
 * Magizhchi PA reads too) together with the people you write to most.
 */
export default function Contacts() {
  const { setMenuOpen, openComposer, notify } = useMail()
  const [qInput, setQInput] = useState('')
  const [q, setQ] = useState('')
  const [data, setData] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [adding, setAdding] = useState(false)
  const [connecting, setConnecting] = useState(false)

  useEffect(() => {
    const t = setTimeout(() => setQ(qInput.trim()), 250)
    return () => clearTimeout(t)
  }, [qInput])

  const load = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      setData(await get(`/api/contacts${qs({ q })}`))
    } catch (e) {
      setError(e.message)
    } finally {
      setLoading(false)
    }
  }, [q])

  useEffect(() => { load() }, [load])

  async function connect() {
    setConnecting(true)
    try {
      await post('/api/contacts/connect')
      notify('Connected to your Magizhchi Box contacts ✓', 'success')
      load()
    } catch (e) {
      notify(e.message, 'error')
    } finally {
      setConnecting(false)
    }
  }

  async function save(p) {
    try {
      const res = await post('/api/contacts', { email: p.email, name: p.name || null })
      notify(res?.message || 'Saved to your Magizhchi Box contacts.', 'success')
      load()
    } catch (e) {
      notify(e.message, 'error')
    }
  }

  const box = data?.box
  const items = data?.items || []

  return (
    <section className="slab rise-3d d1 flex min-h-0 flex-1 flex-col overflow-hidden max-lg:rounded-none">
      <header className="border-b border-slate-200/70 px-3 py-2.5 sm:px-4">
        <div className="flex items-center gap-2">
          <button className="icon-btn lg:hidden" onClick={() => setMenuOpen(true)} aria-label="Open menu"><IconMenu /></button>
          <IconBook size={20} className="hidden text-brand-primary sm:block" />
          <div className="min-w-0 flex-1">
            <h1 className="truncate text-base font-bold leading-tight">Contacts</h1>
            <p className="truncate text-[11px] text-slate-500">
              {box?.connected ? `${box.contacts} in Magizhchi Box · plus the people you write to` : 'The people you write to, from your mail'}
            </p>
          </div>
          {box?.available && (
            <button className="btn-secondary !py-1.5 text-sm" onClick={() => setAdding(true)}><IconPlus size={15} /> <span className="hidden sm:inline">New contact</span></button>
          )}
        </div>
        <div className="relative mt-2">
          <IconSearch size={15} className="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-slate-400" />
          <input className="input !py-1.5 !pl-8" type="search" placeholder="Search names, addresses, numbers…" value={qInput}
            onChange={(e) => setQInput(e.target.value)} aria-label="Search contacts" />
        </div>
      </header>

      <div className="scroll-thin min-h-0 flex-1 overflow-y-auto px-3 py-3 sm:px-4">
        <div className="mx-auto max-w-3xl space-y-3">
          {box?.available && !box.connected && (
            <div className="flex flex-wrap items-center gap-3 rounded-xl border border-peacock-200 bg-peacock-50/70 px-3 py-2.5 text-sm">
              <IconBook size={18} className="text-peacock-700" />
              <span className="min-w-0 flex-1 text-peacock-900">
                <b>Bring in your phone's contacts.</b> Connect Magizhchi Box and the contacts your phone backs up there show here,
                with their numbers. New ones you save here go back to your phone.
              </span>
              <button className="btn-primary !py-1.5" onClick={connect} disabled={connecting}>{connecting && <span className="spinner" />} Connect Box</button>
            </div>
          )}
          {box?.error && <p className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-900">{box.error}</p>}
          {adding && <AddForm onClose={() => setAdding(false)} onSaved={() => { setAdding(false); load() }} />}

          {loading && !data && <p className="flex items-center gap-2 py-6 text-sm text-slate-500"><span className="spinner" /> Gathering your people…</p>}
          {error && <p className="py-6 text-center text-sm text-red-700">{error}</p>}
          {data && !items.length && (
            <p className="py-10 text-center text-sm text-slate-500">{q ? `Nobody matches “${q}”.` : 'Nobody yet. Send a mail and the people you write to appear here.'}</p>
          )}

          <ul className="divide-y divide-slate-200/70 overflow-hidden rounded-xl border border-slate-200 bg-white/70">
            {items.map((p, i) => {
              const wa = whatsApp(p.phone)
              return (
                <li key={`${p.email || p.name}-${i}`} className="flex items-center gap-3 px-3 py-2.5">
                  <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full text-xs font-bold text-white" style={{ background: hue(p.email || p.name) }} aria-hidden="true">
                    {initials(p.name, p.email)}
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="flex items-center gap-2">
                      <span className="truncate text-sm font-semibold text-brand-ink">{p.name || p.email || '(no name)'}</span>
                      <span className="shrink-0 rounded bg-slate-100 px-1.5 text-[10px] font-semibold uppercase tracking-wide text-slate-600">{SOURCE[p.source] || p.source}</span>
                    </span>
                    <span className="block truncate text-xs text-slate-500">
                      {[p.email, p.org].filter(Boolean).join(' · ')}{p.email ? '' : p.phone ? '' : 'No email yet'}
                    </span>
                    {p.hint && <span className="block truncate text-[11px] text-slate-400">{p.hint}</span>}
                  </span>
                  <span className="flex shrink-0 items-center gap-0.5">
                    {p.phone && <a className="icon-btn" href={`tel:${p.phone.replace(/[^\d+]/g, '')}`} title={`Call ${p.phone}`}><IconPhone size={15} /></a>}
                    {wa && <a className="btn-ghost hidden !px-1.5 !py-1 text-xs sm:inline-flex" href={wa} target="_blank" rel="noreferrer">WhatsApp</a>}
                    {p.email && (
                      <button className="icon-btn" title={`Write to ${p.name || p.email}`}
                        onClick={() => openComposer({ mode: 'new', to: [p.email] })}>
                        <IconCompose size={15} />
                      </button>
                    )}
                    {p.source === 'mail' && box?.available && (
                      <button className="icon-btn" title="Save to your Magizhchi Box contacts" onClick={() => save(p)}><IconUserPlus size={15} /></button>
                    )}
                  </span>
                </li>
              )
            })}
          </ul>
          {items.length > 0 && (
            <p className="pb-6 text-center text-[11px] text-slate-400">Magizhchi Box keeps the master copy, so your phone and Magizhchi PA see the same people.</p>
          )}
        </div>
      </div>
    </section>
  )
}
