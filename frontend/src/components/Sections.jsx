import { useEffect, useState } from 'react'
import { createPortal } from 'react-dom'
import { del, post, put } from '../api.js'
import { useMail } from '../state.jsx'
import { IconMore, IconPlus, IconX } from './Icons.jsx'

/** The icons and colours a section can wear (the server keeps the same lists). */
export const SECTION_ICONS = ['📁', '⭐', '💼', '🎓', '🏫', '📚', '💰', '🧾', '🏦', '🛒', '📦', '✈️',
  '🎥', '📅', '🔔', '🤖', '👪', '❤️', '🏠', '⚙️', '📰', '🎯', '🛠️', '🩺']
export const SECTION_COLORS = {
  teal: '#139099', marigold: '#f29a12', dusk: '#5865c9', rose: '#d6325f',
  leaf: '#3f9a4b', sky: '#2f86d6', plum: '#8b4bb8', slate: '#5b6b78',
}

/** A section's icon on a soft tile in its colour, the same size as the lanes' badges. */
export function SectionBadge({ section, size = 24 }) {
  const c = SECTION_COLORS[section?.color] || SECTION_COLORS.teal
  return (
    <span className="inline-flex shrink-0 items-center justify-center rounded-[7px]" aria-hidden="true"
      style={{ width: size, height: size, fontSize: size * 0.58, background: `color-mix(in srgb, ${c} 22%, transparent)`, boxShadow: `inset 0 0 0 1px color-mix(in srgb, ${c} 45%, transparent)` }}>
      {section?.icon || '📁'}
    </span>
  )
}

/** New section, or edit one: a name, an icon and a colour. Deleting hands its mail back to the usual lanes. */
export function SectionDialog({ section, onClose, onSaved }) {
  const { notify, refreshCounts, bumpMail } = useMail()
  const [name, setName] = useState(section?.name || '')
  const [icon, setIcon] = useState(section?.icon || '📁')
  const [color, setColor] = useState(section?.color || 'teal')
  const [busy, setBusy] = useState(false)
  const [askDelete, setAskDelete] = useState(false)

  useEffect(() => {
    const onKey = (e) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  async function save(e) {
    e?.preventDefault()
    // React events cross portals: without this, a dialog opened from inside another form submits that form too.
    e?.stopPropagation()
    if (!name.trim()) return
    setBusy(true)
    try {
      const saved = section
        ? await put(`/api/sections/${section.id}`, { name, icon, color })
        : await post('/api/sections', { name, icon, color })
      await refreshCounts()
      notify(section ? 'Section saved' : `Section “${saved.name}” added`, 'success')
      onSaved?.(saved)
      onClose()
    } catch (err) {
      notify(err.message, 'error')
    } finally {
      setBusy(false)
    }
  }

  async function remove() {
    setBusy(true)
    try {
      await del(`/api/sections/${section.id}`)
      await refreshCounts()
      bumpMail()
      notify(`“${section.name}” removed. Its mail is back in your lanes.`, 'success')
      onSaved?.(null)
      onClose()
    } catch (err) {
      notify(err.message, 'error')
    } finally {
      setBusy(false)
    }
  }

  return createPortal(
    <div className="modal-back fixed inset-0 z-50 flex items-center justify-center p-4" onClick={onClose}>
      <form className="card w-full max-w-md p-5" role="dialog" aria-modal="true" aria-label={section ? 'Edit section' : 'New section'}
        onClick={(e) => e.stopPropagation()} onSubmit={save}>
        <div className="flex items-center gap-3">
          <SectionBadge section={{ icon, color }} size={36} />
          <h2 className="flex-1 text-base font-bold text-brand-ink">{section ? 'Edit section' : 'New section'}</h2>
          <button type="button" className="icon-btn" onClick={onClose} aria-label="Close"><IconX size={16} /></button>
        </div>
        <label className="mt-4 block text-xs font-semibold text-slate-600" htmlFor="section-name">Name</label>
        <input id="section-name" className="input mt-1 w-full" value={name} maxLength={40} autoFocus
          placeholder="Meet notices, College, Bank…" onChange={(e) => setName(e.target.value)} />
        <div className="mt-4 text-xs font-semibold text-slate-600">Icon</div>
        <div className="mt-1.5 grid grid-cols-8 gap-1.5">
          {SECTION_ICONS.map((i) => (
            <button key={i} type="button" onClick={() => setIcon(i)} aria-label={`Icon ${i}`} aria-pressed={icon === i}
              className={`flex h-9 items-center justify-center rounded-lg text-lg transition ${icon === i ? 'bg-brand-gold/25 ring-2 ring-brand-gold' : 'hover:bg-slate-100'}`}>
              {i}
            </button>
          ))}
        </div>
        <div className="mt-4 text-xs font-semibold text-slate-600">Colour</div>
        <div className="mt-1.5 flex flex-wrap gap-2">
          {Object.entries(SECTION_COLORS).map(([k, c]) => (
            <button key={k} type="button" onClick={() => setColor(k)} aria-label={`Colour ${k}`} aria-pressed={color === k}
              className={`h-7 w-7 rounded-full transition ${color === k ? 'ring-2 ring-offset-2 ring-brand-ink' : ''}`} style={{ background: c }} />
          ))}
        </div>
        {!section && (
          <p className="mt-4 text-xs text-slate-500">
            Then fill it: move a mail here from its ⋯ menu, add an automation, or tell PA “put mail from … in {name.trim() || 'this section'}”.
          </p>
        )}
        <div className="mt-5 flex items-center gap-2">
          {section && !askDelete && <button type="button" className="btn-ghost text-red-600" onClick={() => setAskDelete(true)} disabled={busy}>Delete</button>}
          {section && askDelete && (
            <button type="button" className="btn-danger" onClick={remove} disabled={busy}>Delete “{section.name}”</button>
          )}
          <span className="flex-1" />
          <button type="button" className="btn-secondary" onClick={onClose} disabled={busy}>Cancel</button>
          <button type="submit" className="btn-primary" disabled={busy || !name.trim()}>{busy && <span className="spinner" />} {section ? 'Save' : 'Add section'}</button>
        </div>
        {askDelete && <p className="mt-2 text-xs text-slate-500">Its mail goes back to your lanes, and the automations that fill it stop.</p>}
      </form>
    </div>,
    document.body,
  )
}

/** The person's own sections under the three lanes, each with a ⋯ to edit, and "New section". */
export function SidebarSections({ go, active, Count }) {
  const { counts } = useMail()
  const [editing, setEditing] = useState(null) // null | 'new' | section
  const sections = counts?.sections || []
  return (
    <>
      {sections.map((s) => (
        <div key={s.id} className="group relative">
          <button className={`nav-item pr-9 ${active === s.lane ? 'active' : ''}`} onClick={() => go(`/?view=${s.lane}`)}>
            <SectionBadge section={s} />
            <span className="truncate">{s.name}</span>
            <Count unread={s.unread} total={s.total} />
          </button>
          <button type="button" className="icon-btn absolute right-1 top-1/2 -translate-y-1/2 opacity-60 hover:opacity-100 md:opacity-0 md:group-hover:opacity-100 md:focus:opacity-100"
            onClick={() => setEditing(s)} aria-label={`Edit ${s.name}`} title="Edit section">
            <IconMore size={15} />
          </button>
        </div>
      ))}
      <button className="nav-item text-slate-500" onClick={() => setEditing('new')}>
        <span className="inline-flex h-6 w-6 items-center justify-center rounded-[7px] border border-dashed border-slate-300"><IconPlus size={14} /></span>
        <span>New section</span>
      </button>
      {editing && (
        <SectionDialog section={editing === 'new' ? null : editing} onClose={() => setEditing(null)}
          onSaved={(s) => { if (s) go(`/?view=${s.lane}`); else if (editing !== 'new' && active === editing.lane) go('/?view=needs_me') }} />
      )}
    </>
  )
}
