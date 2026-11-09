import { useEffect, useRef, useState } from 'react'
import { get, qs } from '../api.js'

/** A round picture of the person: their photo when there is one, else their first letter. */
export function Avatar({ photo, label, size = 28, className = '' }) {
  const box = { width: size, height: size, fontSize: Math.max(9, Math.round(size * 0.4)) }
  if (photo) return <img src={photo} alt="" className={`shrink-0 rounded-full object-cover ${className}`} style={box} />
  return (
    <span className={`flex shrink-0 items-center justify-center rounded-full bg-peacock-50 font-bold text-peacock-800 ${className}`} style={box}>
      {(label || '?')[0].toUpperCase()}
    </span>
  )
}

/** Photos of the people already on the mail (the chips): email to picture, asked for once per address. */
export function usePhotos(emails) {
  const [photos, setPhotos] = useState({})
  const asked = useRef(new Set())
  const key = emails.join(',')
  useEffect(() => {
    const fresh = emails.map((e) => e.toLowerCase()).filter((e) => e.includes('@') && !asked.current.has(e)).slice(0, 40)
    if (!fresh.length) return undefined
    fresh.forEach((e) => asked.current.add(e))
    let cancelled = false
    const params = new URLSearchParams()
    fresh.forEach((e) => params.append('emails', e))
    get(`/api/contacts/photos?${params}`)
      .then((res) => { if (!cancelled && res?.photos) setPhotos((cur) => ({ ...cur, ...res.photos })) })
      .catch(() => { fresh.forEach((e) => asked.current.delete(e)) }) // try again next time
    return () => { cancelled = true }
  }, [key]) // eslint-disable-line react-hooks/exhaustive-deps
  return photos
}

/** Suggestions for the half-typed address: people from your mail and your Magizhchi Box contacts. */
export function useSuggestions(text, accountId, others) {
  const [items, setItems] = useState([])
  const seq = useRef(0)
  const key = others.join(',')
  useEffect(() => {
    const q = text.trim()
    if (!q || /[,;]/.test(q)) { setItems([]); return undefined }
    const n = ++seq.current
    const t = setTimeout(async () => {
      try {
        const params = new URLSearchParams(qs({ q, accountId: accountId || '', limit: 6 }).slice(1))
        others.slice(0, 20).forEach((o) => params.append('with', o))
        const res = await get(`/api/contacts/suggest?${params}`)
        if (n === seq.current) setItems(res?.items || [])
      } catch {
        if (n === seq.current) setItems([])
      }
    }, 140)
    return () => clearTimeout(t)
  }, [text, accountId, key]) // eslint-disable-line react-hooks/exhaustive-deps
  return items
}

/** The dropdown under a To/Cc/Bcc field. The parent handles keys; mousedown keeps the field focused. */
export function SuggestList({ items, active, onPick, onHover }) {
  if (!items.length) return null
  return (
    <ul className="menu left-12 right-3 top-full z-40 mt-0.5 max-h-72 overflow-y-auto !p-1" role="listbox" aria-label="Suggested addresses">
      {items.map((s, i) => (
        <li key={s.email} role="option" aria-selected={i === active}>
          <button type="button" className={`menu-item !items-start !py-1.5 ${i === active ? '!bg-[var(--hover-strong)]' : ''}`}
            onMouseDown={(e) => { e.preventDefault(); onPick(s) }} onMouseEnter={() => onHover(i)}>
            <Avatar photo={s.photo} label={s.name || s.email} size={28} className="mt-0.5" />
            <span className="min-w-0 flex-1">
              <span className="block truncate text-sm">
                <span className="font-semibold">{s.name || s.email}</span>
                {s.name && <span className="text-slate-500"> {s.email}</span>}
              </span>
              <span className="block truncate text-[11px] text-slate-500">
                {s.why || s.hint}{s.phone ? ` · ${s.phone}` : ''}{s.source === 'box' || s.source === 'both' ? ' · Box' : ''}
              </span>
            </span>
          </button>
        </li>
      ))}
    </ul>
  )
}

/** "Often with them": people who usually get mail together with the ones already on it. */
export function useOftenWith(recipients, accountId) {
  const [items, setItems] = useState([])
  const key = recipients.join(',')
  useEffect(() => {
    if (!recipients.length) { setItems([]); return undefined }
    let cancelled = false
    const t = setTimeout(async () => {
      try {
        const params = new URLSearchParams(qs({ q: '', accountId: accountId || '', limit: 3 }).slice(1))
        recipients.slice(0, 20).forEach((o) => params.append('with', o))
        const res = await get(`/api/contacts/suggest?${params}`)
        if (!cancelled) setItems(res?.items || [])
      } catch {
        if (!cancelled) setItems([])
      }
    }, 400)
    return () => { cancelled = true; clearTimeout(t) }
  }, [key, accountId]) // eslint-disable-line react-hooks/exhaustive-deps
  return items
}

/** Addresses that look mistyped, and people you've never written to, checked as chips are added. */
export function useAddressCheck(recipients) {
  const [items, setItems] = useState([])
  const key = recipients.join(',')
  useEffect(() => {
    if (!recipients.length) { setItems([]); return undefined }
    let cancelled = false
    const t = setTimeout(async () => {
      try {
        const params = new URLSearchParams()
        recipients.slice(0, 100).forEach((r) => params.append('emails', r))
        const res = await get(`/api/contacts/check?${params}`)
        if (!cancelled) setItems(res?.items || [])
      } catch {
        if (!cancelled) setItems([])
      }
    }, 500)
    return () => { cancelled = true; clearTimeout(t) }
  }, [key]) // eslint-disable-line react-hooks/exhaustive-deps
  return items
}
