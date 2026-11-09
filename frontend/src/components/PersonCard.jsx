import { Avatar } from './AddressSuggest.jsx'
import { useEffect, useState } from 'react'
import { get, post, qs } from '../api.js'
import { useMail } from '../state.jsx'
import { IconCheck, IconPhone, IconUserPlus } from './Icons.jsx'

/** wa.me needs a country code: Indian 10-digit numbers get 91 (as in Magizhchi Box). */
export function whatsApp(tel) {
  let d = String(tel || '').replace(/\D/g, '')
  if (d.startsWith('0')) d = d.slice(1)
  if (d.length === 10) d = `91${d}`
  return d.length >= 11 ? `https://wa.me/${d}` : null
}

/**
 * Who sent this: what MailLook knows from your mail (how often you write, who they come with) and
 * their Magizhchi Box card (phone, company). One tap saves them to Box, where your phone and
 * Magizhchi PA see them too.
 */
export default function PersonCard({ email, name }) {
  const { notify, accounts } = useMail()
  const [card, setCard] = useState(null)
  const [busy, setBusy] = useState(false)
  const own = accounts.some((a) => a.email.toLowerCase() === String(email || '').toLowerCase())

  useEffect(() => {
    if (!email || own) return undefined
    let cancelled = false
    get(`/api/contacts/card${qs({ email })}`).then((c) => !cancelled && setCard(c)).catch(() => {})
    return () => { cancelled = true }
  }, [email, own])

  if (!card || own) return null
  const phone = card.phones?.[0]
  const wa = whatsApp(phone)
  const canSave = card.box?.available && !card.inBox

  async function save() {
    setBusy(true)
    try {
      const res = await post('/api/contacts', { email, name: card.name || name || null })
      setCard(res)
      notify(res?.message || 'Saved to your Magizhchi Box contacts.', 'success')
    } catch (e) {
      notify(e.message, 'error')
    } finally {
      setBusy(false)
    }
  }

  if (!card.hint && !card.inBox && !canSave) return null

  return (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5 rounded-xl border border-slate-200 bg-white/70 px-3 py-2 text-xs">
      {card.photo && <Avatar photo={card.photo} label={card.name || email} size={36} />}
      <span className="min-w-0 text-slate-600">
        {card.org && <span className="font-semibold text-brand-ink">{card.org} · </span>}
        {card.hint || 'First mail from them'}
        {card.oftenWith?.length > 0 && <span className="text-slate-500"> · often with {card.oftenWith.slice(0, 2).join(', ')}</span>}
      </span>
      <span className="ml-auto flex shrink-0 items-center gap-1.5">
        {phone && (
          <a className="btn-ghost !py-1 text-xs" href={`tel:${phone.replace(/[^\d+]/g, '')}`} title={`Call ${phone}`}>
            <IconPhone size={14} /><span className="num">{phone}</span>
          </a>
        )}
        {wa && <a className="btn-ghost !py-1 text-xs" href={wa} target="_blank" rel="noreferrer" title="Message on WhatsApp">WhatsApp</a>}
        {card.inBox ? (
          <span className="inline-flex items-center gap-1 font-semibold text-emerald-700" title="In your Magizhchi Box contacts"><IconCheck size={13} /> In contacts</span>
        ) : canSave && (
          <button className="btn-secondary !px-2 !py-1 text-xs" onClick={save} disabled={busy} title="Save to your Magizhchi Box contacts">
            {busy ? <span className="spinner" /> : <IconUserPlus size={14} />} Add to contacts
          </button>
        )}
      </span>
    </div>
  )
}
