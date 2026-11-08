import { useEffect, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { API_BASE, api } from '../api.js'
import { BANNER_H, BANNER_W, LAYOUTS, LINE_COLORS, TEMPLATES, bannerBlob, loadFonts, renderBanner, templateById } from '../signatures/royal.js'
import { chime, seal, setSoundOn, soundOn, tick } from '../signatures/sound.js'
import { IconCheck, IconTrash, IconX } from './Icons.jsx'

// [key, label, input type, autocomplete, placeholder]
const ABOUT = [
  ['name', 'Name', 'text', 'name', ''],
  ['title', 'Designation', 'text', 'organization-title', 'Founder & CEO'],
  ['company', 'Company', 'text', 'organization', 'Magizhchi Tech Academy'],
  ['tagline', 'Tagline', 'text', 'off', 'A line you live by (optional)'],
]
const REACH = [
  ['phone', 'Phone', 'tel', 'tel', '+91 98400 12345'],
  ['whatsapp', 'WhatsApp', 'tel', 'tel', 'Same as phone, or another number'],
  ['email', 'Email', 'email', 'email', ''],
  ['website', 'Website', 'text', 'url', 'magizhchi.academy'],
  ['address', 'Address', 'text', 'street-address', 'Street, city'],
]
const LINKS = [
  ['booking', 'Book a meeting link', 'text', 'url', 'Your Magizhchi Meet or calendar booking page'],
  ['linkedin', 'LinkedIn', 'text', 'off', 'Profile link or handle'],
  ['instagram', 'Instagram', 'text', 'off', '@handle'],
  ['youtube', 'YouTube', 'text', 'off', '@channel'],
  ['facebook', 'Facebook', 'text', 'off', 'Page link or handle'],
  ['x', 'X', 'text', 'off', '@handle'],
]
const FIELDS = [...ABOUT, ...REACH, ...LINKS]
const WIDE = new Set(['name', 'tagline', 'address', 'booking'])
const SOCIALS = [['whatsapp', 'WhatsApp'], ['linkedin', 'LinkedIn'], ['instagram', 'Instagram'], ['youtube', 'YouTube'], ['facebook', 'Facebook'], ['x', 'X']]

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

const loadImage = (src) => new Promise((resolve, reject) => {
  const img = new Image()
  img.onload = () => resolve(img)
  img.onerror = () => reject(new Error("That picture couldn't be opened."))
  img.src = src
})

/** A chosen picture, made smaller (at most 800px) for storing: JPEG for a photo, PNG for a logo (keeps transparency). */
async function prepare(file, kind) {
  if (!/^image\/(png|jpe?g|webp|gif|svg\+xml)$/.test(file.type)) throw new Error('Choose a PNG, JPEG, WebP or SVG picture.')
  if (file.size > 15 * 1024 * 1024) throw new Error('That picture is over 15 MB. Choose a smaller one.')
  const url = URL.createObjectURL(file)
  try {
    const src = await loadImage(url)
    const w0 = src.naturalWidth || 800, h0 = src.naturalHeight || 800
    const k = Math.min(1, 800 / Math.max(w0, h0))
    const c = document.createElement('canvas')
    c.width = Math.max(16, Math.round(w0 * k))
    c.height = Math.max(16, Math.round(h0 * k))
    const ctx = c.getContext('2d')
    if (kind === 'photo') { ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, c.width, c.height) }
    ctx.drawImage(src, 0, 0, c.width, c.height)
    const blob = await new Promise((r) => c.toBlob(r, kind === 'photo' ? 'image/jpeg' : 'image/png', 0.9))
    if (!blob) throw new Error("That picture couldn't be read.")
    const img = await loadImage(URL.createObjectURL(blob))
    return { img, blob }
  } finally {
    URL.revokeObjectURL(url)
  }
}

/** A photo or logo the design already has, fetched from MailLook (so the canvas may use it). */
async function fetchArt(token) {
  const res = await fetch(`${API_BASE}/api/sig/${token}`)
  if (!res.ok) throw new Error('gone')
  return loadImage(URL.createObjectURL(await res.blob()))
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
        {src ? <img src={src.url} alt="" width={src.w} height={src.h} draggable="false" /> : <span className="royal-card-wait" />}
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

/** A small drawing of each layout, so the choice reads at a glance. */
function LayoutSketch({ id }) {
  const gold = 'currentColor'
  return (
    <svg width="64" height="26" viewBox="0 0 64 26" aria-hidden="true" fill="none" stroke={gold} strokeWidth="1.4">
      <rect x="0.7" y="0.7" width="62.6" height="24.6" rx="3" opacity="0.5" />
      {id === 'company' ? <rect x="5" y="6" width="14" height="14" rx="2" /> : <circle cx="12" cy="13" r="7" />}
      {id === 'card' && <line x1="23" y1="5" x2="23" y2="21" />}
      <line x1={id === 'card' ? 27 : 24} y1="9" x2={id === 'card' ? 46 : 56} y2="9" strokeWidth="3" />
      <line x1={id === 'card' ? 27 : 24} y1="15" x2={id === 'card' ? 42 : 48} y2="15" />
      <line x1={id === 'card' ? 27 : 24} y1="19.5" x2={id === 'card' ? 40 : 44} y2="19.5" opacity="0.7" />
      {id === 'card' && <rect x="50" y="8" width="9" height="10" rx="1.5" />}
    </svg>
  )
}

/** Where the photo sits in its circle: drag to move it, the slider to zoom. */
function PhotoFrame({ img, crop, setCrop }) {
  const D = 112
  const w = img.naturalWidth || img.width, h = img.naturalHeight || img.height
  const k = (D / Math.min(w, h)) * crop.zoom
  const W = w * k, H = h * k
  const left = D / 2 - W / 2 + crop.x * ((W - D) / 2)
  const top = D / 2 - H / 2 + crop.y * ((H - D) / 2)
  const drag = useRef(null)
  const clamp = (v) => Math.max(-1, Math.min(1, v))
  return (
    <div className="flex items-center gap-4">
      <div className="photo-frame" style={{ width: D, height: D, backgroundImage: `url(${img.src})`, backgroundSize: `${W}px ${H}px`, backgroundPosition: `${left}px ${top}px` }}
        role="img" aria-label="Photo position: drag to move it"
        onPointerDown={(e) => { e.currentTarget.setPointerCapture(e.pointerId); drag.current = { x: e.clientX, y: e.clientY, crop } }}
        onPointerMove={(e) => {
          const d = drag.current
          if (!d) return
          const sx = (W - D) / 2 || 1, sy = (H - D) / 2 || 1
          setCrop({ ...d.crop, x: clamp(d.crop.x + (e.clientX - d.x) / sx), y: clamp(d.crop.y + (e.clientY - d.y) / sy) })
        }}
        onPointerUp={() => { drag.current = null }} onPointerCancel={() => { drag.current = null }} />
      <div className="min-w-0 flex-1">
        <label className="label" htmlFor="photo-zoom">Zoom</label>
        <input id="photo-zoom" type="range" min="1" max="4" step="0.01" value={crop.zoom} className="w-full accent-amber-600"
          onChange={(e) => setCrop({ ...crop, zoom: Number(e.target.value) })} />
        <p className="mt-1 text-[11px] text-slate-500">Drag the photo to place your face in the circle.</p>
      </div>
    </div>
  )
}

function ArtPicker({ label, hint, art, onPick, onRemove, children }) {
  const input = useRef(null)
  return (
    <div className="rounded-xl border border-slate-200 bg-white p-3">
      <div className="flex items-center gap-2">
        <span className="text-sm font-semibold text-brand-ink">{label}</span>
        <span className="min-w-0 flex-1 truncate text-[11px] text-slate-500">{hint}</span>
        <input ref={input} type="file" accept="image/png,image/jpeg,image/webp,image/svg+xml" className="hidden"
          onChange={(e) => { const f = e.target.files?.[0]; e.target.value = ''; if (f) onPick(f) }} />
        <button type="button" className="btn-secondary !py-1 text-xs" onClick={() => input.current?.click()}>{art ? 'Change' : 'Upload'}</button>
        {art && <button type="button" className="btn-ghost !px-2 !py-1 text-xs" onClick={onRemove} aria-label={`Remove ${label.toLowerCase()}`}><IconTrash size={14} /></button>}
      </div>
      {art && children && <div className="mt-3">{children}</div>}
    </div>
  )
}

/** The mail as it will arrive: their words, then the banner, contact lines, icons and button (what the server sends). */
function MailPreview({ big, details, templateId }) {
  const c = LINE_COLORS[templateId] || LINE_COLORS['ivory-classic']
  const accent = c.accent.slice(1)
  const reach = [
    details.phone && <a key="p" href={`tel:${details.phone.replace(/[^0-9+]/g, '')}`} style={{ color: c.link }}>{details.phone}</a>,
    details.email && <a key="e" href={`mailto:${details.email}`} style={{ color: c.link }}>{details.email}</a>,
    details.website && <span key="w" style={{ color: c.link }}>{details.website.replace(/^https?:\/\//i, '')}</span>,
  ].filter(Boolean)
  const icons = SOCIALS.filter(([k]) => details[k]?.trim())
  return (
    <div className="rounded-xl border border-slate-200 bg-white p-4 text-[15px] leading-normal text-[#1f2d33] shadow-sm" style={{ fontFamily: 'Arial, Helvetica, sans-serif' }}>
      <p>Hello there,</p>
      <p className="mt-2">Thank you for your time today. I'll send the details by Friday.</p>
      <div className="mt-4" style={{ fontFamily: "Georgia, 'Times New Roman', serif" }}>
        {big && <img src={big.url} alt={[details.name, details.title].filter(Boolean).join(' · ')} width={big.w} height={big.h} className="block h-auto w-full max-w-[520px]" />}
        {(details.tagline || reach.length > 0 || details.address || icons.length > 0 || details.booking) && (
          <div className="mt-1.5 max-w-[520px] pt-2 text-[13px] leading-[1.65]" style={{ borderTop: `2px solid ${c.accent}`, color: c.ink }}>
            {details.tagline && <div><i style={{ color: c.muted }}>{details.tagline}</i></div>}
            {reach.length > 0 && <div>{reach.flatMap((r, i) => (i ? [<span key={`s${i}`} style={{ color: c.accent }}> | </span>, r] : [r]))}</div>}
            {details.address && <div style={{ color: c.muted }}>{details.address}</div>}
            {icons.length > 0 && (
              <div className="flex gap-1.5 pt-1.5">
                {icons.map(([k, label]) => <img key={k} src={`${API_BASE}/api/sig/icon/${k}/${accent}`} width="22" height="22" alt={label} title={label} />)}
              </div>
            )}
            {details.booking && (
              <span className="mt-2.5 inline-flex items-center gap-1.5 rounded-md px-3.5 py-1.5 text-[13px] font-bold text-white" style={{ background: c.accent, fontFamily: 'Arial, Helvetica, sans-serif' }}>
                <img src={`${API_BASE}/api/sig/icon/calendar/ffffff`} width="14" height="14" alt="" /> Book a meeting
              </span>
            )}
          </div>
        )}
      </div>
    </div>
  )
}

function Field({ f, value, onChange }) {
  const [k, label, type, auto, placeholder] = f
  return (
    <label className={WIDE.has(k) ? 'sm:col-span-2' : ''}>
      <span className="label">{label}{k === 'name' && <span className="text-red-600"> *</span>}</span>
      <input className="input" type={type} autoComplete={auto} maxLength={k === 'booking' || LINKS.some((l) => l[0] === k) ? 200 : 120}
        value={value} placeholder={placeholder} onChange={(e) => onChange(k, e.target.value)} />
    </label>
  )
}

export default function SignatureStudio({ account, onClose, onSaved, notify }) {
  const start = account.signatureDesign || {}
  const [templateId, setTemplateId] = useState(start.template || 'gold-crest')
  const [layout, setLayout] = useState(start.layout || 'person')
  const [details, setDetails] = useState(() => {
    const d = {}
    for (const [k] of FIELDS) d[k] = start[k] || ''
    if (!start.template) {
      d.name = account.displayName || ''
      d.email = account.email || ''
    }
    return d
  })
  // photo / logo: { img, blob (new upload) | token (already saved) }
  const [photo, setPhoto] = useState(null)
  const [logo, setLogo] = useState(null)
  const [crop, setCrop] = useState(() => ({ x: 0, y: 0, zoom: 1, ...(start.photoCrop || {}) }))
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
    const art = [
      start.photo ? fetchArt(start.photo).then((img) => live && setPhoto({ img, token: start.photo })).catch(() => {}) : null,
      start.logo ? fetchArt(start.logo).then((img) => live && setLogo({ img, token: start.logo })).catch(() => {}) : null,
    ]
    Promise.all([loadFonts(), ...art]).then(() => live && setReady(true))
    return () => { live = false }
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    const onKey = (e) => e.key === 'Escape' && !busy && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [busy, onClose])

  const drawn = { layout, name: details.name, title: details.title, company: details.company, photoImg: photo?.img, logoImg: logo?.img, photoCrop: crop }
  const key = [layout, details.name, details.title, details.company, photo?.img?.src, logo?.img?.src, crop.x, crop.y, crop.zoom].join('|')
  const snap = (c, scale) => ({ url: c.toDataURL('image/jpeg', 0.9), w: c.width / scale, h: c.height / scale })

  // Redraw while they type, a moment after they stop: the gallery at card size, the preview larger.
  useEffect(() => {
    if (!ready) return
    const id = setTimeout(() => {
      const next = {}
      for (const tpl of TEMPLATES) next[tpl.id] = snap(renderBanner(tpl, drawn, 1.5), 1.5)
      setThumbs(next)
    }, 320)
    return () => clearTimeout(id)
  }, [ready, key]) // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!ready) return
    const id = setTimeout(() => setBig(snap(renderBanner(templateId, drawn, 2), 2)), 120)
    return () => clearTimeout(id)
  }, [ready, key, templateId]) // eslint-disable-line react-hooks/exhaustive-deps

  async function pick(kind, file) {
    setError('')
    try {
      const p = await prepare(file, kind)
      if (kind === 'photo') { setPhoto(p); setCrop({ x: 0, y: 0, zoom: 1 }) } else setLogo(p)
      chime(t.note * 1.5)
    } catch (e) {
      setError(e.message)
    }
  }

  const missingName = !details.name.trim()
  const changed = useMemo(() => {
    if (!account.signatureDesign) return true
    if (start.template !== templateId || (start.layout || 'person') !== layout) return true
    if ((photo?.token || null) !== (start.photo || null) || photo?.blob) return true
    if ((logo?.token || null) !== (start.logo || null) || logo?.blob) return true
    const c0 = start.photoCrop || { x: 0, y: 0, zoom: 1 }
    if (photo && (c0.x !== crop.x || c0.y !== crop.y || c0.zoom !== crop.zoom)) return true
    return FIELDS.some(([k]) => (start[k] || '') !== details[k].trim())
  }, [account.signatureDesign, templateId, layout, details, photo, logo, crop]) // eslint-disable-line react-hooks/exhaustive-deps

  async function save() {
    setBusy(true)
    setError('')
    try {
      const clean = {}
      for (const [k] of FIELDS) clean[k] = details[k].trim()
      await loadFonts()
      const banner = await bannerBlob(templateId, { ...drawn, ...clean })
      const design = { template: templateId, layout, ...clean }
      if (photo) { design.photoCrop = crop; if (photo.token) design.photo = photo.token }
      if (logo?.token) design.logo = logo.token
      const form = new FormData()
      form.append('design', JSON.stringify(design))
      form.append('image', banner, 'signature.jpg')
      if (photo?.blob) form.append('photo', photo.blob, 'photo.jpg')
      if (logo?.blob) form.append('logo', logo.blob, 'logo.png')
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

  const set = (k, v) => setDetails((d) => ({ ...d, [k]: v }))

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
            <div className="royal-layouts mb-6" role="radiogroup" aria-label="Layout">
              {LAYOUTS.map((l) => (
                <button key={l.id} type="button" role="radio" aria-checked={layout === l.id} className={`royal-layout ${layout === l.id ? 'is-picked' : ''}`}
                  onClick={() => { setLayout(l.id); tick() }} title={l.blurb}>
                  <LayoutSketch id={l.id} />
                  <span>{l.name}</span>
                </button>
              ))}
            </div>
            <div className="grid grid-cols-1 gap-x-5 gap-y-9 sm:grid-cols-2">
              {TEMPLATES.map((tpl) => (
                <DesignCard key={tpl.id} t={tpl} src={thumbs[tpl.id]} picked={tpl.id === templateId} reduce={reduce} onPick={setTemplateId} />
              ))}
            </div>
          </section>

          <section className="scroll-thin min-h-0 border-t border-slate-200 bg-slate-50/60 p-4 sm:p-6 lg:overflow-y-auto lg:border-l lg:border-t-0" aria-label="Your details">
            <div className="grid gap-2.5">
              <ArtPicker label="Photo" hint={layout === 'company' ? 'Shown small beside the company name' : 'Goes inside the crest'} art={photo}
                onPick={(f) => pick('photo', f)} onRemove={() => setPhoto(null)}>
                {photo && <PhotoFrame img={photo.img} crop={crop} setCrop={setCrop} />}
              </ArtPicker>
              <ArtPicker label="Logo" hint={layout === 'company' ? 'Leads the banner' : layout === 'card' ? 'Sits on the right' : 'Sits beside the company name'} art={logo}
                onPick={(f) => pick('logo', f)} onRemove={() => setLogo(null)} />
            </div>

            <h3 className="label mt-5">About you</h3>
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              {ABOUT.map((f) => <Field key={f[0]} f={f} value={details[f[0]]} onChange={set} />)}
            </div>
            <h3 className="label mt-5">How to reach you</h3>
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              {REACH.map((f) => <Field key={f[0]} f={f} value={details[f[0]]} onChange={set} />)}
            </div>
            <h3 className="label mt-5">Booking and social</h3>
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              {LINKS.map((f) => <Field key={f[0]} f={f} value={details[f[0]]} onChange={set} />)}
            </div>

            <h3 className="label mt-5">How it arrives</h3>
            {big ? <MailPreview big={big} details={details} templateId={templateId} /> : <div className="h-48 animate-pulse rounded-xl bg-slate-200/70" style={{ maxWidth: BANNER_W, minHeight: BANNER_H }} />}
            <p className="mt-2 text-[11px] leading-relaxed text-slate-500">
              The crest or photo, logo, name, designation and company travel as one picture at 3× size. Phone, email, website, the icons
              and the booking button stay real links, so people can tap them. Plain-text mail gets the same details as text.
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
