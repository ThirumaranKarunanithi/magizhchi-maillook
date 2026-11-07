import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { get, patch, post } from '../api.js'
import { useMail } from '../state.jsx'
import { safeHttpUrl } from '../util.js'
import { DATA_WEB, TONE_CLASS, dueLabel, dueTone, money, unsure } from '../bills.js'
import { IconAlert, IconCheck, IconReceipt } from './Icons.jsx'

const todayIso = () => new Date().toLocaleDateString('en-CA')

/** Fix what Data read: who, how much, when, which invoice. */
export function BillEditor({ bill, onSaved, onCancel }) {
  const { notify } = useMail()
  const [f, setF] = useState({
    vendor: bill.vendor || '',
    amount: bill.amount ?? '',
    dueDate: bill.dueDate || '',
    invoiceNo: bill.invoiceNo || '',
    autopay: !!bill.autopay,
  })
  const [busy, setBusy] = useState(false)
  const set = (k) => (e) => setF((v) => ({ ...v, [k]: e.target.type === 'checkbox' ? e.target.checked : e.target.value }))
  const flag = (k) => (unsure(bill, k) ? 'border-amber-400 bg-amber-50/60' : 'border-slate-200')

  async function save(e) {
    e.preventDefault()
    setBusy(true)
    try {
      onSaved(await patch(`/api/bills/${bill.id}`, { ...f, dueDate: f.dueDate || null }))
      notify('Bill updated ✓', 'success')
    } catch (err) {
      notify(err.message, 'error')
    } finally {
      setBusy(false)
    }
  }

  const input = 'mt-0.5 w-full rounded-lg border bg-white/80 px-2 py-1.5 text-sm outline-none focus:border-peacock-400'
  return (
    <form onSubmit={save} className="mt-3 grid grid-cols-2 gap-2 text-xs">
      <label className="col-span-2 font-semibold text-slate-600">From
        <input className={`${input} ${flag('vendor')}`} value={f.vendor} onChange={set('vendor')} required maxLength={200} />
      </label>
      <label className="font-semibold text-slate-600">Amount ({!bill.currency || bill.currency === 'INR' ? '₹' : bill.currency})
        <input className={`${input} ${flag('amount')} num`} value={f.amount} onChange={set('amount')} inputMode="decimal" required />
      </label>
      <label className="font-semibold text-slate-600">Due date
        <input type="date" className={`${input} ${flag('dueDate')}`} value={f.dueDate} onChange={set('dueDate')} />
      </label>
      <label className="col-span-2 font-semibold text-slate-600">Invoice number
        <input className={`${input} ${flag('invoiceNo')}`} value={f.invoiceNo} onChange={set('invoiceNo')} maxLength={100} />
      </label>
      <label className="col-span-2 flex items-center gap-2 text-sm text-slate-700">
        <input type="checkbox" checked={f.autopay} onChange={set('autopay')} /> Paid automatically (auto-debit) — no reminders
      </label>
      <div className="col-span-2 flex justify-end gap-2">
        <button type="button" className="btn-secondary !py-1 text-xs" onClick={onCancel}>Cancel</button>
        <button className="btn-primary !py-1 text-xs" disabled={busy}>{busy && <span className="spinner" />} Save</button>
      </div>
    </form>
  )
}

/** The facts of one bill, with the shaky ones marked. Shared by the card on a mail and the Bills page. */
export function BillFacts({ bill }) {
  const tax = [['CGST', bill.cgst], ['SGST', bill.sgst], ['IGST', bill.igst]].filter(([, v]) => v != null && Number(v) > 0)
  const items = [
    bill.invoiceNo && ['Invoice', bill.invoiceNo, 'invoiceNo'],
    bill.customerRef && ['Account', bill.customerRef, 'customerRef'],
    bill.vendorGstin && ['GSTIN', bill.vendorGstin, 'vendorGstin'],
    tax.length > 0 && ['Tax', tax.map(([k, v]) => `${k} ${money(v, bill.currency)}`).join(' · '), 'tax'],
    bill.payeeAccount && ['Pay to', `${bill.payeeAccount}${bill.payeeIfsc ? ` · ${bill.payeeIfsc}` : ''}`, 'payeeAccount'],
  ].filter(Boolean)
  if (!items.length) return null
  return (
    <dl className="mt-2 grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5 text-xs">
      {items.map(([k, v, field]) => (
        <div key={k} className="contents">
          <dt className="text-slate-500">{k}</dt>
          <dd className={`min-w-0 truncate num ${unsure(bill, field) ? 'font-semibold text-amber-800' : 'text-slate-700'}`}>{v}</dd>
        </div>
      ))}
    </dl>
  )
}

/** On a mail: the bill Magizhchi Data read from it, or a way to have it read. */
export default function BillCard({ message }) {
  const { notify } = useMail()
  const navigate = useNavigate()
  const [state, setState] = useState(null) // { on, state, bill }
  const [editing, setEditing] = useState(false)
  const [busy, setBusy] = useState(null)
  const [problem, setProblem] = useState(null)

  useEffect(() => {
    if (message.folder === 'SENT') return undefined
    let live = true
    setState(null)
    setEditing(false)
    setProblem(null)
    get(`/api/messages/${message.id}/bill`).then((r) => live && setState(r)).catch(() => {})
    return () => { live = false }
  }, [message.id, message.folder])

  if (!state?.on) return null
  const bill = state.bill
  const today = todayIso()

  async function readNow() {
    setBusy('read')
    setProblem(null)
    try {
      setState(await post(`/api/messages/${message.id}/bill`))
    } catch (e) {
      setProblem(e)
    } finally {
      setBusy(null)
    }
  }

  async function setStatus(status) {
    setBusy(status)
    try {
      const b = await patch(`/api/bills/${bill.id}`, { status })
      setState((s) => ({ ...s, bill: b }))
      notify(status === 'paid' ? `${b.vendor}: marked paid ✓` : 'Marked as still to pay', 'success')
    } catch (e) {
      notify(e.message, 'error')
    } finally {
      setBusy(null)
    }
  }

  async function notABill(never) {
    setBusy('not')
    try {
      await post(`/api/bills/${bill.id}/not-a-bill`, { never })
      setState((s) => ({ ...s, bill: null, state: 'not_bill' }))
      notify(never ? `Bills will skip ${message.fromName || message.fromEmail} from now on` : 'Removed from Bills', 'info')
    } catch (e) {
      notify(e.message, 'error')
    } finally {
      setBusy(null)
    }
  }

  if (!bill) {
    const hasFile = (message.attachments || []).some((a) => /\.(pdf|jpe?g|png|webp)$/i.test(a.name || ''))
    const waiting = { queued: 'Magizhchi Data is reading this mail for a bill…', no_credits: 'This bill waits for Magizhchi Data credits.', no_account: 'This bill waits: this month’s free bills are used up.', failed: 'Magizhchi Data couldn’t read this mail.' }[state.state]
    if (!waiting && !hasFile && !problem) return null
    return (
      <section className="flex flex-wrap items-center gap-2 rounded-xl border border-dashed border-slate-300 px-3 py-2 text-xs text-slate-600" aria-label="Bill">
        <IconReceipt size={15} className="shrink-0 text-slate-400" />
        <span className="min-w-0 flex-1">
          {problem ? problem.message : waiting || 'Is this a bill? Magizhchi Data can read it and remind you before it’s due.'}
        </span>
        {problem?.status === 402 || state.state === 'no_credits' || state.state === 'no_account'
          ? <a className="font-semibold text-peacock-700 hover:underline" href={DATA_WEB} target="_blank" rel="noreferrer">Add credits ↗</a> : null}
        {state.state !== 'queued' && (
          <button className="btn-secondary !px-2.5 !py-1 text-xs" onClick={readNow} disabled={!!busy}>
            {busy === 'read' && <span className="spinner" />} {busy === 'read' ? 'Reading…' : state.state === 'failed' || problem ? 'Try again' : 'Read as a bill'}
          </button>
        )}
      </section>
    )
  }

  const tone = dueTone(bill, today)
  const pay = safeHttpUrl(bill.payLink)
  const shaky = ['amount', 'dueDate', 'vendor'].some((k) => unsure(bill, k))
  return (
    <section className="rounded-xl border border-slate-200 bg-white/80 p-3 text-sm" aria-label="Bill">
      <div className="flex items-start gap-3">
        <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-lg bg-peacock-50 text-peacock-700"><IconReceipt size={20} /></span>
        <div className="min-w-0 flex-1">
          <p className="truncate font-bold text-brand-ink">
            <span className={unsure(bill, 'vendor') ? 'text-amber-800' : ''}>{bill.vendor}</span>
          </p>
          <p className="flex flex-wrap items-baseline gap-x-2">
            <span className={`text-xl font-bold num ${unsure(bill, 'amount') ? 'text-amber-800' : 'text-brand-ink'}`}>{money(bill.amount, bill.currency)}</span>
            <span className={`rounded-md px-1.5 py-0.5 text-xs font-semibold ring-1 ${TONE_CLASS[tone]} ${unsure(bill, 'dueDate') ? '!ring-amber-400' : ''}`}>{dueLabel(bill, today)}</span>
          </p>
        </div>
        {pay && bill.status === 'due' && <a className="btn-secondary shrink-0 !px-2.5 !py-1 text-xs" href={pay} target="_blank" rel="noreferrer">Pay ↗</a>}
      </div>

      {shaky && !editing && (
        <p className="mt-2 flex items-start gap-1.5 rounded-lg bg-amber-50 px-2 py-1.5 text-xs text-amber-900">
          <IconAlert size={13} className="mt-0.5 shrink-0" />
          <span>Magizhchi Data wasn’t sure about the parts in amber. <button className="font-semibold underline" onClick={() => setEditing(true)}>Check them</button></span>
        </p>
      )}

      {editing ? (
        <BillEditor bill={bill} onCancel={() => setEditing(false)} onSaved={(b) => { setState((s) => ({ ...s, bill: b })); setEditing(false) }} />
      ) : (
        <>
          <BillFacts bill={bill} />
          <div className="mt-2.5 flex flex-wrap items-center gap-1.5">
            {bill.kind === 'bill' && (bill.status === 'due'
              ? <button className="btn-primary !px-2.5 !py-1 text-xs" onClick={() => setStatus('paid')} disabled={!!busy}>{busy === 'paid' ? <span className="spinner" /> : <IconCheck size={13} />} Mark paid</button>
              : <button className="btn-secondary !px-2.5 !py-1 text-xs" onClick={() => setStatus('due')} disabled={!!busy}>Still to pay</button>)}
            <button className="btn-secondary !px-2.5 !py-1 text-xs" onClick={() => setEditing(true)}>Edit</button>
            <button className="btn-secondary !px-2.5 !py-1 text-xs" onClick={() => navigate(`/bills?open=${bill.id}`)}>All bills</button>
            <span className="ml-auto flex items-center gap-2 text-xs">
              <button className="text-slate-500 hover:text-brand-ink hover:underline" onClick={() => notABill(false)} disabled={!!busy}>Not a bill</button>
              <button className="text-slate-500 hover:text-brand-ink hover:underline" onClick={() => notABill(true)} disabled={!!busy} title={`Never read mail from ${message.fromEmail} as a bill`}>Never from this sender</button>
            </span>
          </div>
        </>
      )}
    </section>
  )
}
