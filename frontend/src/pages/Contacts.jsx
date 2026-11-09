import { useCallback, useEffect, useRef, useState } from 'react'
import { get, post, qs } from '../api.js'
import { useMail } from '../state.jsx'
import { EMAIL_RE } from '../util.js'
import { whatsApp } from '../components/PersonCard.jsx'
import { Avatar } from '../components/AddressSuggest.jsx'
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

const PAGE = 200

/** Everything on one contact, opened under its row. */
function Details({ p, onWrite }) {
  const phones = p.phones?.length ? p.phones : p.phone ? [p.phone] : []
  const emails = p.emails?.length ? p.emails : p.email ? [p.email] : []
  const row = 'flex items-center gap-2 py-1'
  return (
    <div className="mt-2 grid gap-x-6 gap-y-1 rounded-lg bg-slate-50/80 px-3 py-2 text-sm sm:grid-cols-2">
      {phones.map((t) => {
        const wa = whatsApp(t)
        return (
          <div key={'t' + t} className={row}>
            <IconPhone size={14} className="shrink-0 text-slate-400" />
            <a className="min-w-0 flex-1 truncate font-medium text-brand-ink hover:underline" href={`tel:${t.replace(/[^\d+]/g, '')}`}>{t}</a>
            {wa && <a className="btn-ghost !px-1.5 !py-0.5 text-xs" href={wa} target="_blank" rel="noreferrer">WhatsApp</a>}
          </div>
        )
      })}
      {emails.map((e) => (
        <div key={'e' + e} className={row}>
          <IconCompose size={14} className="shrink-0 text-slate-400" />
          <button className="min-w-0 flex-1 truncate text-left font-medium text-brand-ink hover:underline" onClick={() => onWrite(e)} title={`Write to ${e}`}>{e}</button>
        </div>
      ))}
      {p.org && <div className={row}><span className="w-20 shrink-0 text-xs text-slate-500">Company</span><span className="min-w-0 truncate">{p.org}</span></div>}
      {p.birthday && <div className={row}><span className="w-20 shrink-0 text-xs text-slate-500">Birthday</span><span>{p.birthday}</span></div>}
      {p.address && <div className={`${row} sm:col-span-2`}><span className="w-20 shrink-0 text-xs text-slate-500">Address</span><span className="min-w-0 whitespace-pre-line">{p.address}</span></div>}
      {p.note && <div className={`${row} sm:col-span-2`}><span className="w-20 shrink-0 text-xs text-slate-500">Note</span><span className="min-w-0 whitespace-pre-line">{p.note}</span></div>}
      {p.hint && p.source !== 'box' && <div className={`${row} text-xs text-slate-500 sm:col-span-2`}>{p.hint}</div>}
      {!phones.length && !emails.length && !p.org && <div className="text-xs text-slate-500">Nothing more on this card yet.</div>}
    </div>
  )
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
  const [items, setItems] = useState([])
  const [next, setNext] = useState(null)
  const [more, setMore] = useState(false)
  const [open, setOpen] = useState(null)
  const shown = useRef(PAGE)
  const stamp = useRef(null)
  const end = useRef(null)

  useEffect(() => {
    const t = setTimeout(() => setQ(qInput.trim()), 250)
    return () => clearTimeout(t)
  }, [qInput])

  // Loads the list again from the top, keeping as many rows as were already showing.
  const load = useCallback(async (quiet) => {
    if (!quiet) setLoading(true)
    setError('')
    try {
      const res = await get(`/api/contacts${qs({ q, limit: Math.min(1000, Math.max(PAGE, shown.current)) })}`)
      setData(res)
      setItems(res.items || [])
      setNext(res.nextOffset ?? null)
      stamp.current = res.stamp
    } catch (e) {
      if (!quiet) setError(e.message)
    } finally {
      setLoading(false)
    }
  }, [q])

  useEffect(() => { shown.current = PAGE; setOpen(null); load() }, [load])

  const loadMore = useCallback(async () => {
    if (next == null || more) return
    setMore(true)
    try {
      const res = await get(`/api/contacts${qs({ q, limit: PAGE, offset: next })}`)
      setItems((x) => { const all = [...x, ...(res.items || [])]; shown.current = all.length; return all })
      setNext(res.nextOffset ?? null)
    } catch (e) {
      notify(e.message, 'error')
    } finally {
      setMore(false)
    }
  }, [q, next, more, notify])

  // More rows as the end of the list comes into view.
  const nearEnd = useCallback(() => {
    const sc = end.current
    if (sc && next != null && sc.scrollTop + sc.clientHeight > sc.scrollHeight - 800) loadMore()
  }, [next, loadMore])
  useEffect(() => { nearEnd() }, [items, nearEnd])   // a short list or a tall window: fill it

  // A contact added or changed on the phone (or in Box) shows here within seconds.
  useEffect(() => {
    if (!data?.box?.connected) return
    let busy = false
    const check = async () => {
      if (busy || document.hidden) return
      busy = true
      try {
        const s = await get('/api/contacts/stamp')
        if (s?.stamp && stamp.current && s.stamp !== stamp.current) await load(true)
      } catch { /* try again next time */ } finally { busy = false }
    }
    const t = setInterval(check, 15000)
    window.addEventListener('focus', check)
    return () => { clearInterval(t); window.removeEventListener('focus', check) }
  }, [data?.box?.connected, load])

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
  const write = (email) => openComposer({ mode: 'new', to: [email] })

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
              {q && data ? ` · ${data.total ?? items.length} match` : ''}
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

      <div ref={end} onScroll={nearEnd} className="scroll-thin min-h-0 flex-1 overflow-y-auto px-3 py-3 sm:px-4">
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
              const key = `${p.email || ''}|${p.name || ''}|${p.phone || ''}`
              const isOpen = open === key
              return (
                <li key={`${key}-${i}`} className={`px-3 py-2.5 ${isOpen ? 'bg-white' : ''}`}>
                <div className="flex items-center gap-3">
                  <button className="flex min-w-0 flex-1 items-center gap-3 text-left" onClick={() => setOpen(isOpen ? null : key)} aria-expanded={isOpen}>
                  {p.photo ? (
                    <Avatar photo={p.photo} label={p.name || p.email} size={36} />
                  ) : (
                    <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full text-xs font-bold text-white" style={{ background: hue(p.email || p.name) }} aria-hidden="true">
                      {initials(p.name, p.email)}
                    </span>
                  )}
                  <span className="min-w-0 flex-1">
                    <span className="flex items-center gap-2">
                      <span className="truncate text-sm font-semibold text-brand-ink">{p.name || p.email || '(no name)'}</span>
                      <span className="shrink-0 rounded bg-slate-100 px-1.5 text-[10px] font-semibold uppercase tracking-wide text-slate-600">{SOURCE[p.source] || p.source}</span>
                    </span>
                    <span className="block truncate text-xs text-slate-500">
                      {[p.phone, p.email, p.org].filter(Boolean).join(' · ') || 'Nothing but a name yet'}
                    </span>
                    {p.hint && p.source !== 'box' && <span className="block truncate text-[11px] text-slate-400">{p.hint}</span>}
                  </span>
                  </button>
                  <span className="flex shrink-0 items-center gap-0.5">
                    {p.phone && <a className="icon-btn" href={`tel:${p.phone.replace(/[^\d+]/g, '')}`} title={`Call ${p.phone}`}><IconPhone size={15} /></a>}
                    {wa && <a className="btn-ghost hidden !px-1.5 !py-1 text-xs sm:inline-flex" href={wa} target="_blank" rel="noreferrer">WhatsApp</a>}
                    {p.email && (
                      <button className="icon-btn" title={`Write to ${p.name || p.email}`}
                        onClick={() => write(p.email)}>
                        <IconCompose size={15} />
                      </button>
                    )}
                    {p.source === 'mail' && box?.available && (
                      <button className="icon-btn" title="Save to your Magizhchi Box contacts" onClick={() => save(p)}><IconUserPlus size={15} /></button>
                    )}
                  </span>
                </div>
                {isOpen && <Details p={p} onWrite={write} />}
                </li>
              )
            })}
          </ul>
          {more && <p className="flex items-center justify-center gap-2 py-3 text-sm text-slate-500"><span className="spinner" /> Loading more…</p>}
          {items.length > 0 && next == null && (
            <p className="pb-6 text-center text-[11px] text-slate-400">Magizhchi Box keeps the master copy, so your phone and Magizhchi PA see the same people.</p>
          )}
        </div>
      </div>
    </section>
  )
}
