import { useEffect, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { api } from '../api.js'
import { BANNER_H, BANNER_W, LINE_COLORS, TEMPLATES, bannerBlob, loadFonts, renderBanner, templateById } from '../signatures/royal.js'
import { chime, seal, setSoundOn, soundOn, tick } from '../signatures/sound.js'
import { IconCheck, IconX } from './Icons.jsx'

const FIELDS = [
  ['name', 'Name', 'text', 'name'],
  ['title', 'Title', 'text', 'organization-title'],
  ['company', 'Company', 'text', 'organization'],
  ['phone', 'Phone', 'tel', 'tel'],
  ['email', 'Email', 'email', 'email'],
  ['website', 'Website', 'text', 'url'],
  ['address', 'Address', 'text', 'street-address'],
  ['tagline', 'Tagline', 'text', 'off'],
]

function useReducedMotion() {
  const [reduce, setReduce] = useState(() => typeof window !== 'undefined' && window.matchMedia('(prefers-reduced-motion: reduce)').matches)
  useEffect(() => {
    const m = window.matchMedia('(prefers-reduced-motion: reduce)')
    const on = () => setReduce(m.matches)
    m.addEventListener?.('change', on)
    return () => m.removeEventListener?.('change', on)
  }, [])
  return reduce
}

/** One design in the gallery: it leans toward the pointer, a glare follows it, and it stands on a reflection. */
function DesignCard({ t, src, picked, onPick, reduce }) {
  const ref = useRef(null)
  function move(e) {
    if (reduce || e.pointerType === 'touch') return
    const el = ref.current
    const r = el.getBoundingClientRect()
    const x = (e.clientX - r.left) / r.width
    const y = (e.clientY - r.top) / r.height
    el.style.setProperty('--ry', `${((x - 0.5) * 18).toFixed(2)}deg`)
    el.style.setProperty('--rx', `${((0.5 - y) * 14).toFixed(2)}deg`)
    el.style.setProperty('--gx', `${(x * 100).toFixed(1)}%`)
    el.style.setProperty('--gy', `${(y * 100).toFixed(1)}%`)
    el.classList.add('is-tilting')
  }
  function leave() {
    const el = ref.current
    el.classList.remove('is-tilting')
    el.style.removeProperty('--rx')
    el.style.removeProperty('--ry')
  }
  return (
    <button type="button" ref={ref} className={`royal-card ${picked ? 'is-picked' : ''}`} aria-pressed={picked}
      onPointerMove={move} onPointerLeave={leave} onPointerEnter={() => tick()} onFocus={() => tick()}
      onClick={() => { onPick(t.id); chime(t.note) }}>
      <span className="royal-card-face">
        {src ? <img src={src} alt="" width={BANNER_W} height={BANNER_H} draggable="false" /> : <span className="royal-card-wait" />}
        <span className="royal-card-glare" aria-hidden="true" />
      </span>
      <span className="royal-card-label">
        <span className="font-semibold">{t.name}</span>
        <span className="text-[11px] opacity-70">{t.blurb}</span>
      </span>
      {picked && <span className="royal-card-tick" aria-hidden="true"><IconCheck size={13} /></span>}
    </button>
  )
}

function SoundIcon({ on }) {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M11 5 6 9H3v6h3l5 4z" />
      {on ? <><path d="M15.5 8.5a5 5 0 0 1 0 7" /><path d="M18.5 5.5a9 9 0 0 1 0 13" /></> : <path d="m16 9 5 6m0-6-5 6" />}
    </svg>
  )
}

/** The mail as it will arrive: their words, then the banner and the contact lines (what the server sends). */
function MailPreview({ src, details, templateId }) {
  const c = LINE_COLORS[templateId] || LINE_COLORS['ivory-classic']
  const reach = [
    details.phone && <a key="p" href={`tel:${details.phone.replace(/[^0-9+]/g, '')}`} style={{ color: c.link }}>{details.phone}</a>,
    details.email && <a key="e" href={`mailto:${details.email}`} style={{ color: c.link }}>{details.email}</a>,
    details.website && <a key="w" href="#" onClick={(e) => e.preventDefault()} style={{ color: c.link }}>{details.website.replace(/^https?:\/\//i, '')}</a>,
  ].filter(Boolean)
  return (
    <div className="rounded-xl border border-slate-200 bg-white p-4 text-[15px] leading-normal text-[#1f2d33] shadow-sm" style={{ fontFamily: 'Arial, Helvetica, sans-serif' }}>
      <p>Hello there,</p>
      <p className="mt-2">Thank you for your time today. I'll send the details by Friday.</p>
      <div className="mt-4" style={{ fontFamily: "Georgia, 'Times New Roman', serif" }}>
        {src && <img src={src} alt={[details.name, details.title].filter(Boolean).join(' · ')} width={BANNER_W} height={BANNER_H} className="block h-auto w-full max-w-[520px]" />}
        {(details.company || details.tagline || reach.length > 0 || details.address) && (
          <div className="mt-1.5 max-w-[520px] pt-2 text-[13px] leading-[1.65]" style={{ borderTop: `2px solid ${c.accent}`, color: c.ink }}>
            {(details.company || details.tagline) && (
              <div>
                {details.company && <b style={{ letterSpacing: '.04em' }}>{details.company}</b>}
                {details.company && details.tagline && <span style={{ color: c.accent }}> ◆ </span>}
                {details.tagline && <i style={{ color: c.muted }}>{details.tagline}</i>}
              </div>
            )}
            {reach.length > 0 && <div>{reach.flatMap((r, i) => (i ? [<span key={`s${i}`} style={{ color: c.accent }}> | </span>, r] : [r]))}</div>}
            {details.address && <div style={{ color: c.muted }}>{details.address}</div>}
          </div>
        )}
      </div>
    </div>
  )
}

export default function SignatureStudio({ account, onClose, onSaved, notify }) {
  const start = account.signatureDesign || {}
  const [templateId, setTemplateId] = useState(start.template || 'gold-crest')
  const [details, setDetails] = useState(() => {
    const d = {}
    for (const [k] of FIELDS) d[k] = start[k] || ''
    if (!start.template) {
      d.name = account.displayName || ''
      d.email = account.email || ''
    }
    return d
  })
  const [ready, setReady] = useState(false)
  const [thumbs, setThumbs] = useState({})
  const [big, setBig] = useState(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [sound, setSound] = useState(soundOn())
  const reduce = useReducedMotion()
  const t = templateById(templateId)

  useEffect(() => {
    let live = true
    loadFonts().then(() => live && setReady(true))
    return () => { live = false }
  }, [])

  useEffect(() => {
    const onKey = (e) => e.key === 'Escape' && !busy && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [busy, onClose])

  // Redraw while they type, a moment after they stop. The gallery first, at card size; the preview larger.
  const key = `${details.name}|${details.title}|${details.company}`
  useEffect(() => {
    if (!ready) return
    const id = setTimeout(() => {
      const next = {}
      for (const tpl of TEMPLATES) next[tpl.id] = renderBanner(tpl, details, 1.5).toDataURL('image/jpeg', 0.9)
      setThumbs(next)
    }, 260)
    return () => clearTimeout(id)
  }, [ready, key]) // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!ready) return
    const id = setTimeout(() => setBig(renderBanner(templateId, details, 2).toDataURL('image/jpeg', 0.92)), 160)
    return () => clearTimeout(id)
  }, [ready, key, templateId]) // eslint-disable-line react-hooks/exhaustive-deps

  const missingName = !details.name.trim()
  const changed = useMemo(() => {
    if (!account.signatureDesign) return true
    if (start.template !== templateId) return true
    return FIELDS.some(([k]) => (start[k] || '') !== details[k].trim())
  }, [account.signatureDesign, templateId, details]) // eslint-disable-line react-hooks/exhaustive-deps

  async function save() {
    setBusy(true)
    setError('')
    try {
      const clean = {}
      for (const [k] of FIELDS) clean[k] = details[k].trim()
      const blob = await bannerBlob(templateId, clean)
      const form = new FormData()
      form.append('design', JSON.stringify({ template: templateId, ...clean }))
      form.append('image', blob, 'signature.jpg')
      const updated = await api(`/api/accounts/${account.id}/signature-design`, { method: 'PUT', form })
      seal(t.note)
      onSaved(updated)
      notify?.('Royal signature saved ✓', 'success')
      onClose()
    } catch (e) {
      setError(e.message || "Couldn't save the signature.")
    } finally {
      setBusy(false)
    }
  }

  return createPortal(
    <div className="modal-back fixed inset-0 z-50 flex items-stretch justify-center p-0 sm:items-center sm:p-4" onClick={() => !busy && onClose()}>
      <div className="royal-studio card flex max-h-full w-full max-w-6xl flex-col overflow-hidden sm:max-h-[92vh]" role="dialog" aria-modal="true"
        aria-label="Royal signatures" onClick={(e) => e.stopPropagation()}>
        <div className="royal-studio-head flex items-center gap-3 px-5 py-3.5">
          <div className="min-w-0 flex-1">
            <h2 className="text-base font-bold">Royal signatures</h2>
            <p className="truncate text-xs opacity-75">For {account.email}. The 3D, light and colour are baked into a sharp picture, so they look the same in every mail app.</p>
          </div>
          <button type="button" className="royal-icon-btn" onClick={() => { setSoundOn(!sound); setSound(!sound); if (!sound) setTimeout(() => chime(t.note), 0) }}
            aria-pressed={sound} title={sound ? 'Sound on' : 'Sound off'} aria-label={sound ? 'Turn sound off' : 'Turn sound on'}>
            <SoundIcon on={sound} />
          </button>
          <button type="button" className="royal-icon-btn" onClick={onClose} disabled={busy} aria-label="Close"><IconX size={16} /></button>
        </div>

        <div className="grid min-h-0 flex-1 grid-cols-1 overflow-y-auto lg:grid-cols-[minmax(0,1.15fr)_minmax(0,1fr)] lg:overflow-hidden">
          <section className="royal-stage scroll-thin min-h-0 p-4 sm:p-6 lg:overflow-y-auto" aria-label="Designs">
            <div className="grid grid-cols-1 gap-x-5 gap-y-9 sm:grid-cols-2">
              {TEMPLATES.map((tpl) => (
                <DesignCard key={tpl.id} t={tpl} src={thumbs[tpl.id]} picked={tpl.id === templateId} reduce={reduce}
                  onPick={setTemplateId} />
              ))}
            </div>
          </section>

          <section className="scroll-thin min-h-0 border-t border-slate-200 bg-slate-50/60 p-4 sm:p-6 lg:overflow-y-auto lg:border-l lg:border-t-0" aria-label="Your details">
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              {FIELDS.map(([k, label, type, auto]) => (
                <label key={k} className={k === 'address' || k === 'tagline' || k === 'name' ? 'sm:col-span-2' : ''}>
                  <span className="label">{label}{k === 'name' && <span className="text-red-600"> *</span>}</span>
                  <input className="input" type={type} autoComplete={auto} maxLength={120} value={details[k]}
                    placeholder={k === 'tagline' ? 'A line you live by (optional)' : ''}
                    onChange={(e) => setDetails((d) => ({ ...d, [k]: e.target.value }))} />
                </label>
              ))}
            </div>

            <h3 className="label mt-5">How it arrives</h3>
            {big ? <MailPreview src={big} details={details} templateId={templateId} /> : <div className="h-48 animate-pulse rounded-xl bg-slate-200/70" />}
            <p className="mt-2 text-[11px] leading-relaxed text-slate-500">
              The name, title and crest travel as a picture at 3× size. Phone, email and website stay real text, so people can tap them.
              Plain-text mail gets the same details as text. Mail apps don't play sound or motion, so those stay here in the designer.
            </p>

            {error && <p className="mt-3 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-700" role="alert">{error}</p>}
            <div className="mt-4 flex flex-wrap items-center gap-2">
              <button type="button" className="btn-primary" onClick={save} disabled={busy || !ready || missingName || !changed}>
                {busy ? <span className="spinner" /> : <IconCheck size={14} />} Use {t.name}
              </button>
              <button type="button" className="btn-ghost" onClick={onClose} disabled={busy}>Cancel</button>
              {missingName && <span className="text-xs text-slate-500">Add your name first.</span>}
            </div>
          </section>
        </div>
      </div>
    </div>,
    document.body,
  )
}
