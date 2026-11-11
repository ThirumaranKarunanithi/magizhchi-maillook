import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { del, get, post, put } from '../api.js'
import { useLanes, useMail } from '../state.jsx'
import { laneName } from '../util.js'
import PageHeader from '../components/PageHeader.jsx'
import { SectionDialog } from '../components/Sections.jsx'
import { IconPlus, IconRefresh, IconSparkle, IconTrash } from '../components/Icons.jsx'

const EMPTY_RULE = { from: '', subject: '', to: '', words: '', lane: '', markRead: false, archive: false }

/** "Mail from …, with … in the subject → goes to …, marked read." */
function describe(r) {
  const when = []
  if (r.from) when.push(<>from <b>{r.from}</b></>)
  if (r.subject) when.push(<>with <b>{r.subject}</b> in the subject</>)
  if (r.to) when.push(<>sent to <b>{r.to}</b></>)
  if (r.words) when.push(<>mentioning <b>{r.words}</b></>)
  const what = []
  if (r.lane) what.push(<>goes to <b>{laneName(r.lane)}</b></>)
  if (r.markRead) what.push('is marked read')
  if (r.archive) what.push('is archived')
  const join = (parts, last) => parts.map((p, i) => <span key={i}>{i > 0 && (i === parts.length - 1 ? ` ${last} ` : ', ')}{p}</span>)
  return <>Mail {join(when, 'and')} {join(what, 'and')}.</>
}

function RuleForm({ initial, onDone, onCancel }) {
  const { notify, refreshCounts, bumpMail } = useMail()
  const lanes = useLanes()
  const [r, setR] = useState(initial || EMPTY_RULE)
  const [applyNow, setApplyNow] = useState(true)
  const [preview, setPreview] = useState(null)
  const [busy, setBusy] = useState(false)
  const [newSection, setNewSection] = useState(false)
  const set = (k, v) => setR((x) => ({ ...x, [k]: v }))
  const hasWhen = [r.from, r.subject, r.to, r.words].some((v) => v && v.trim())
  const hasWhat = !!r.lane || r.markRead || r.archive

  // How many mails already here it would catch, as they type.
  useEffect(() => {
    if (!hasWhen) {
      setPreview(null)
      return undefined
    }
    const t = setTimeout(() => {
      post('/api/rules/preview', { ...r, lane: r.lane || null }).then(setPreview).catch(() => setPreview(null))
    }, 400)
    return () => clearTimeout(t)
  }, [r, hasWhen])

  async function save(e) {
    e.preventDefault()
    setBusy(true)
    try {
      const body = { ...r, lane: r.lane || null }
      if (initial?.id) {
        await put(`/api/rules/${initial.id}`, body)
        notify('Automation saved', 'success')
      } else {
        const res = await post('/api/rules', { ...body, applyNow })
        notify(res.applied ? `Automation on. ${res.applied} ${res.applied === 1 ? 'mail' : 'mails'} already here moved.` : 'Automation on. New mail that fits is sorted by itself.', 'success')
      }
      await refreshCounts()
      bumpMail()
      onDone()
    } catch (err) {
      notify(err.message, 'error')
    } finally {
      setBusy(false)
    }
  }

  const field = (k, label, placeholder, hint) => (
    <label className="block">
      <span className="text-xs font-semibold text-slate-600">{label}</span>
      <input className="input mt-1 w-full" value={r[k] || ''} placeholder={placeholder} onChange={(e) => set(k, e.target.value)} />
      {hint && <span className="mt-0.5 block text-[11px] text-slate-500">{hint}</span>}
    </label>
  )

  return (
    <form className="card p-4 sm:p-5" onSubmit={save}>
      <h2 className="text-sm font-bold text-brand-ink">{initial?.id ? 'Edit automation' : 'New automation'}</h2>
      <p className="mt-0.5 text-xs text-slate-500">When a mail fits everything you fill in, MailLook does the rest by itself. Use commas for “any of”.</p>
      <div className="mt-3 grid gap-3 sm:grid-cols-2">
        {field('from', 'From', 'no-reply@magizhchi.software or @bank.com', 'An address, a whole domain, or a name')}
        {field('subject', 'Subject has', 'is starting, class reminder')}
        {field('to', 'Sent to', 'team@school.edu')}
        {field('words', 'Mail mentions', 'invoice, receipt')}
      </div>
      <div className="mt-4 text-xs font-semibold text-slate-600">Then</div>
      <div className="mt-1 flex flex-wrap items-center gap-2">
        <select className="input min-w-[12rem] flex-1 sm:flex-none" value={r.lane || ''} onChange={(e) => (e.target.value === '+' ? setNewSection(true) : set('lane', e.target.value))}
          aria-label="Put it in">
          <option value="">Keep it where it lands</option>
          {lanes.map((l) => <option key={l.key} value={l.key}>Put in {l.label}</option>)}
          <option value="+">+ New section…</option>
        </select>
        <label className="flex items-center gap-1.5 text-sm"><input type="checkbox" className="accent-[#b45309]" checked={r.markRead} onChange={(e) => set('markRead', e.target.checked)} /> Mark as read</label>
        <label className="flex items-center gap-1.5 text-sm"><input type="checkbox" className="accent-[#b45309]" checked={r.archive} onChange={(e) => set('archive', e.target.checked)} /> Archive</label>
      </div>
      {preview && (
        <div className="mt-3 rounded-lg bg-slate-50 px-3 py-2 text-xs text-slate-600">
          {preview.count ? <>Fits <b>{preview.count}</b> {preview.count === 1 ? 'mail' : 'mails'} already in your inbox, like {preview.sample.slice(0, 3).map((x) => `“${x.subject || '(no subject)'}”`).join(', ')}.</>
            : 'Nothing in your inbox fits yet. New mail that fits will be sorted.'}
        </div>
      )}
      {!initial?.id && (
        <label className="mt-3 flex items-center gap-1.5 text-sm"><input type="checkbox" className="accent-[#b45309]" checked={applyNow} onChange={(e) => setApplyNow(e.target.checked)} /> Also sort the mail already here</label>
      )}
      <div className="mt-4 flex justify-end gap-2">
        <button type="button" className="btn-secondary" onClick={onCancel} disabled={busy}>Cancel</button>
        <button type="submit" className="btn-primary" disabled={busy || !hasWhen || !hasWhat}>{busy && <span className="spinner" />} {initial?.id ? 'Save' : 'Turn on'}</button>
      </div>
      {newSection && <SectionDialog onClose={() => setNewSection(false)} onSaved={(s) => s && set('lane', s.lane)} />}
    </form>
  )
}

export default function Automations() {
  const { notify, refreshCounts, bumpMail } = useMail()
  const navigate = useNavigate()
  const [rules, setRules] = useState(null)
  const [editing, setEditing] = useState(null) // null | 'new' | rule
  const [running, setRunning] = useState(null)

  const load = useCallback(() => {
    get('/api/rules').then((r) => setRules(r.rules || [])).catch((e) => {
      setRules([])
      notify(e.message, 'error')
    })
  }, [notify])
  useEffect(load, [load])

  async function toggle(r) {
    try {
      await put(`/api/rules/${r.id}`, { enabled: !r.enabled })
      load()
    } catch (e) {
      notify(e.message, 'error')
    }
  }
  async function remove(r) {
    try {
      await del(`/api/rules/${r.id}`)
      notify('Automation removed. Mail it already moved stays where it is.', 'success')
      load()
    } catch (e) {
      notify(e.message, 'error')
    }
  }
  async function runNow(r) {
    setRunning(r.id)
    try {
      const res = await post(`/api/rules/${r.id}/run`)
      notify(res.applied ? `Sorted ${res.applied} ${res.applied === 1 ? 'mail' : 'mails'}` : 'Nothing new to sort', 'success')
      await refreshCounts()
      bumpMail()
      load()
    } catch (e) {
      notify(e.message, 'error')
    } finally {
      setRunning(null)
    }
  }

  return (
    <div className="slab rise-3d d1 scroll-thin flex min-h-0 flex-1 flex-col overflow-y-auto max-lg:rounded-none">
      <PageHeader title="Automations" subtitle="Mail sorts itself: into your sections, read, or archived">
        {editing == null && (
          <button className="btn-primary" onClick={() => setEditing('new')}><IconPlus size={15} /> New</button>
        )}
      </PageHeader>
      <div className="mx-auto w-full max-w-3xl space-y-4 px-4 py-6 sm:px-6">
        <div className="flex items-start gap-3 rounded-xl border border-peacock-600/20 bg-peacock-600/5 px-4 py-3 text-sm">
          <IconSparkle size={18} className="mt-0.5 shrink-0 text-peacock-600" />
          <p className="text-slate-600">
            You can also just tell Magizhchi PA: <i>“always put mail from no-reply@magizhchi.software in a Meet notices section”</i> or
            <i> “mark bank alerts as read automatically”</i>. It makes the section and the automation for you.
          </p>
        </div>

        {editing && <RuleForm initial={editing === 'new' ? null : editing} onCancel={() => setEditing(null)} onDone={() => { setEditing(null); load() }} />}

        {rules == null ? (
          <div className="card p-8 text-center text-slate-400"><span className="spinner" /></div>
        ) : !rules.length ? (
          !editing && (
            <div className="card px-6 py-10 text-center">
              <div className="text-4xl" aria-hidden="true">🪄</div>
              <p className="mt-2 font-semibold">No automations yet</p>
              <p className="mx-auto mt-1 max-w-sm text-sm text-slate-500">Say who a mail is from or what it says, and where it should go. MailLook sorts every new one by itself.</p>
              <button className="btn-primary mt-4" onClick={() => setEditing('new')}><IconPlus size={15} /> New automation</button>
            </div>
          )
        ) : (
          <ul className="card divide-y divide-slate-100">
            {rules.map((r) => (
              <li key={r.id} className={`flex flex-wrap items-center gap-3 px-4 py-3 ${r.enabled ? '' : 'opacity-60'}`}>
                <button type="button" role="switch" aria-checked={r.enabled} onClick={() => toggle(r)} title={r.enabled ? 'On: tap to pause' : 'Paused: tap to turn on'}
                  aria-label={r.enabled ? 'Turn off' : 'Turn on'}
                  className={`relative h-6 w-10 shrink-0 rounded-full transition-colors ${r.enabled ? 'bg-peacock-600' : 'bg-slate-300'}`}>
                  <span className={`absolute top-0.5 h-5 w-5 rounded-full bg-white shadow transition-all ${r.enabled ? 'left-[18px]' : 'left-0.5'}`} />
                </button>
                <div className="min-w-0 flex-1 basis-60">
                  <p className="text-sm text-slate-700">{describe(r)}</p>
                  <p className="mt-0.5 text-[11px] text-slate-500">
                    {r.madeBy === 'pa' ? 'Made by Magizhchi PA · ' : ''}
                    {r.matched ? `Sorted ${r.matched} ${r.matched === 1 ? 'mail' : 'mails'}` : 'Nothing sorted yet'}
                    {r.lane && <> · <button className="underline" onClick={() => navigate(`/?view=${r.lane}`)}>Open {laneName(r.lane)}</button></>}
                  </p>
                </div>
                <div className="flex shrink-0 items-center gap-1">
                  <button className="btn-ghost !px-2 text-xs" onClick={() => runNow(r)} disabled={running === r.id} title="Sort the mail already here">
                    {running === r.id ? <span className="spinner" /> : <IconRefresh size={14} />} Run now
                  </button>
                  <button className="btn-ghost !px-2 text-xs" onClick={() => setEditing(r)}>Edit</button>
                  <button className="icon-btn" onClick={() => remove(r)} aria-label="Remove automation" title="Remove"><IconTrash size={15} /></button>
                </div>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  )
}
