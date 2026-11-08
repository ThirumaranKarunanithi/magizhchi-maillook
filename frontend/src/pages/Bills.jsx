import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { get, patch, post, put } from '../api.js'
import { useMail } from '../state.jsx'
import { DATA_WEB, TONE_CLASS, daysLeft, dueLabel, dueTone, groupBills, inrNote, money, shortDay, totalInr, unsure, upiLink } from '../bills.js'
import { safeHttpUrl } from '../util.js'
import { BillEditor, BillFacts, PayNow } from '../components/BillCard.jsx'
import { IconAlert, IconCheck, IconChevron, IconMenu, IconReceipt } from '../components/Icons.jsx'

const GROUPS = [
  { key: 'overdue', title: 'Overdue' },
  { key: 'week', title: 'Due in the next 7 days' },
  { key: 'later', title: 'Later' },
  { key: 'undated', title: 'No due date' },
  { key: 'paid', title: 'Paid' },
]

/** Bills: every bill Magizhchi Data found in your mail, by when it's due. */
export default function Bills() {
  const { setMenuOpen, notify } = useMail()
  const [params] = useSearchParams()
  const [setup, setSetup] = useState(null)
  const [data, setData] = useState(null)
  const [error, setError] = useState('')
  const openId = Number(params.get('open')) || null

  const loadSetup = useCallback(() => get('/api/bills/setup').then(setSetup).catch((e) => setError(e.message)), [])
  const loadBills = useCallback(() => get('/api/bills').then(setData).catch((e) => setError(e.message)), [])

  useEffect(() => {
    loadSetup()
    loadBills()
  }, [loadSetup, loadBills])

  // While Data is reading, look again every 15 seconds.
  const queued = setup?.waiting?.queued || 0
  useEffect(() => {
    if (!queued) return undefined
    const t = setInterval(() => { loadSetup(); loadBills() }, 15000)
    return () => clearInterval(t)
  }, [queued, loadSetup, loadBills])

  // A bill saved by the editor comes back without its "above usual" mark: keep it while the amount is the same.
  const replace = (b) => setData((d) => ({ ...d, bills: d.bills.map((x) => (x.id === b.id ? { ...b, jump: b.jump ?? (Number(b.amount) === Number(x.amount) ? x.jump : undefined) } : x)) }))
  const remove = (id) => setData((d) => ({ ...d, bills: d.bills.filter((x) => x.id !== id) }))

  return (
    <section className="slab rise-3d d1 flex min-h-0 flex-1 flex-col overflow-hidden max-lg:rounded-none">
      <header className="flex items-center gap-2 border-b border-slate-200/70 px-3 py-2.5 sm:px-4">
        <button className="icon-btn lg:hidden" onClick={() => setMenuOpen(true)} aria-label="Open menu"><IconMenu /></button>
        <IconReceipt size={20} className="hidden text-peacock-600 sm:block" />
        <div className="min-w-0 flex-1">
          <h1 className="truncate text-base font-bold leading-tight">Bills</h1>
          <p className="truncate text-xs text-slate-500">Found in your mail and read by Magizhchi Data</p>
        </div>
      </header>

      <div className="scroll-thin min-h-0 flex-1 overflow-y-auto px-3 py-4 max-md:pb-24 sm:px-5">
        <div className="mx-auto max-w-3xl space-y-4">
          {error && <p className="rounded-xl bg-red-50 px-3 py-2 text-sm text-red-800">{error}</p>}
          {!setup ? <p className="py-10 text-center text-sm text-slate-500"><span className="spinner" /> Loading…</p>
            : !setup.configured ? <p className="card p-4 text-sm text-slate-600">Bills aren’t set up on this server yet.</p>
              : !setup.on ? <TurnOn setup={setup} onOn={(s) => { setSetup(s); loadBills() }} />
                : (
                  <>
                    <Waiting setup={setup} onRetry={async () => {
                      try {
                        const r = await post('/api/bills/retry')
                        notify(r.queued ? `Reading ${r.queued} waiting mail${r.queued === 1 ? '' : 's'}…` : 'Nothing is waiting', 'info')
                        loadSetup()
                      } catch (e) { notify(e.message, 'error') }
                    }} />
                    {data && <BillList data={data} openId={openId} onChange={replace} onRemove={remove} />}
                    <Settings setup={setup} onChange={(s) => setSetup(s)} onScanned={loadSetup} />
                  </>
                )}
        </div>
      </div>
    </section>
  )
}

/** Before Bills is on: what it does, what it costs, and one button. */
function TurnOn({ setup, onOn }) {
  const { notify } = useMail()
  const [busy, setBusy] = useState(false)
  const [step, setStep] = useState('intro') // intro → scan
  const [est, setEst] = useState(null)

  async function turnOn() {
    setBusy(true)
    try {
      await put('/api/bills/setup', { on: true })
      setEst(await get('/api/bills/scan?days=90'))
      setStep('scan')
    } catch (e) {
      notify(e.message, 'error')
    } finally {
      setBusy(false)
    }
  }

  async function scan(go) {
    setBusy(true)
    try {
      if (go) {
        const r = await post('/api/bills/scan', { days: 90 })
        notify(r.queued ? `Magizhchi Data is reading ${r.queued} mail${r.queued === 1 ? '' : 's'}. Bills appear here as they’re read.` : 'No bills in the last 90 days.', 'success')
      }
      onOn(await get('/api/bills/setup'))
    } catch (e) {
      notify(e.message, 'error')
    } finally {
      setBusy(false)
    }
  }

  if (step === 'scan' && est) {
    return (
      <div className="card space-y-3 p-5">
        <h2 className="text-lg font-bold">Bills is on ✓</h2>
        <p className="text-sm text-slate-700">New bills are found as they arrive. Look back at the last 90 days too?</p>
        <p className="rounded-xl bg-peacock-50 px-3 py-2.5 text-sm text-peacock-900">
          {est.bills === 0 ? 'No mail in the last 90 days looks like a bill.'
            : <>About <b className="num">{est.bills}</b> mail{est.bills === 1 ? '' : 's'} look like bills.{' '}
              {est.paid === 0 ? 'All within this month’s free bills.' : <><span className="num">{est.free}</span> free, then about <b className="num">{est.paid * (setup.costPerBill || 1)}</b> Magizhchi Data credit{est.paid * (setup.costPerBill || 1) === 1 ? '' : 's'}.</>}</>}
        </p>
        <div className="flex flex-wrap gap-2">
          {est.bills > 0 && <button className="btn-primary" onClick={() => scan(true)} disabled={busy}>{busy && <span className="spinner" />} Find them</button>}
          <button className="btn-secondary" onClick={() => scan(false)} disabled={busy}>{est.bills > 0 ? 'Only new bills' : 'Done'}</button>
        </div>
      </div>
    )
  }

  return (
    <div className="card space-y-4 p-5">
      <div className="flex items-start gap-3">
        <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-peacock-50 text-peacock-700"><IconReceipt size={22} /></span>
        <div>
          <h2 className="text-lg font-bold leading-tight">Every bill in your mail, found and remembered</h2>
          <p className="mt-1 text-sm text-slate-600">Magizhchi Data reads bills from your mail — the mail itself, PDFs, even scanned or photographed bills in Tamil or English.</p>
        </div>
      </div>
      <ul className="space-y-2 text-sm text-slate-700">
        <li className="flex gap-2"><IconCheck size={16} className="mt-0.5 shrink-0 text-emerald-600" /> Who it’s from, how much, the due date, the invoice number and the GST.</li>
        <li className="flex gap-2"><IconCheck size={16} className="mt-0.5 shrink-0 text-emerald-600" /> An alert a few days before it’s due, and on the day.</li>
        <li className="flex gap-2"><IconCheck size={16} className="mt-0.5 shrink-0 text-emerald-600" /> All your bills on one page: overdue, this week, later, paid.</li>
      </ul>
      <p className="text-xs text-slate-500">
        Only mail that looks like a bill is sent to Magizhchi Data, never the rest of your mail. The first {setup.freePerMonth} bills each month are free;
        after that each bill uses {setup.costPerBill || 1} credit from your Magizhchi Data account{setup.linked ? <> (you have <b className="num">{setup.balance}</b>)</> : ''}.
      </p>
      <button className="btn-primary" onClick={turnOn} disabled={busy}>{busy && <span className="spinner" />} Turn on Bills</button>
    </div>
  )
}

/** Mail waiting to be read: by Data now, or for credits, an account or a retry. */
function Waiting({ setup, onRetry }) {
  const w = setup.waiting || {}
  const stuck = (w.no_credits || 0) + (w.no_account || 0) + (w.failed || 0)
  if (!w.queued && !stuck) return null
  return (
    <div className="space-y-2">
      {w.queued > 0 && (
        <p className="flex items-center gap-2 rounded-xl bg-peacock-50 px-3 py-2 text-sm text-peacock-900">
          <span className="spinner" /> Magizhchi Data is reading {w.queued} mail{w.queued === 1 ? '' : 's'}…
        </p>
      )}
      {stuck > 0 && (
        <div className="flex flex-wrap items-center gap-2 rounded-xl bg-amber-50 px-3 py-2 text-sm text-amber-900">
          <span className="flex min-w-[14rem] flex-1 items-start gap-2">
          <IconAlert size={16} className="mt-0.5 shrink-0" />
          <span className="min-w-0">
            {w.no_credits > 0 || w.no_account > 0
              ? `${w.no_credits + w.no_account} bill${w.no_credits + w.no_account === 1 ? '' : 's'} wait${w.no_credits + w.no_account === 1 ? 's' : ''}: your free bills for this month are used up and your Magizhchi Data credits have run out.`
              : `Magizhchi Data couldn’t read ${w.failed} mail${w.failed === 1 ? '' : 's'}.`}
          </span>
          </span>
          {(w.no_credits > 0 || w.no_account > 0) && <a className="btn-secondary !px-2.5 !py-1 text-xs" href={DATA_WEB} target="_blank" rel="noreferrer">Add credits ↗</a>}
          <button className="btn-secondary !px-2.5 !py-1 text-xs" onClick={onRetry}>Read them now</button>
        </div>
      )}
    </div>
  )
}

function BillList({ data, openId, onChange, onRemove }) {
  const today = data.today
  const groups = useMemo(() => groupBills(data.bills, today), [data.bills, today])
  const thisMonth = today.slice(0, 7)
  const monthBills = data.bills.filter((b) => b.kind === 'bill' && (b.dueDate || b.invoiceDate || '').startsWith(thisMonth))

  if (!data.bills.length) {
    return (
      <div className="card p-6 text-center text-sm text-slate-600">
        <IconReceipt size={28} className="mx-auto mb-2 text-slate-300" />
        No bills yet. They appear here as Magizhchi Data finds them in your mail. On any mail, you can also tap <b>Read as a bill</b>.
      </div>
    )
  }

  return (
    <>
      <div className="grid grid-cols-3 gap-2">
        <Stat label="Overdue" n={groups.overdue.length} total={totalInr(groups.overdue)} tone={groups.overdue.length ? 'text-red-700' : 'text-slate-400'} />
        <Stat label="Next 7 days" n={groups.week.length} total={totalInr(groups.week)} tone={groups.week.length ? 'text-amber-700' : 'text-slate-400'} />
        <Stat label={new Date(`${thisMonth}-01T00:00:00`).toLocaleDateString([], { month: 'long' })} n={monthBills.length} total={totalInr(monthBills)} tone="text-brand-ink" />
      </div>
      {(data.regular || []).filter((r) => r.late).map((r) => (
        <p key={r.key} className="flex items-start gap-2 rounded-xl bg-amber-50 px-3 py-2 text-sm text-amber-900 ring-1 ring-amber-200">
          <IconAlert size={15} className="mt-0.5 shrink-0" />
          <span><b>No {r.vendor}{r.account ? ` · ${r.account}` : ''} bill yet.</b> {usually(r)} Check it wasn’t missed, or look on {r.vendor}’s site.</span>
        </p>
      ))}
      {GROUPS.map(({ key, title }) => groups[key].length > 0 && (
        <section key={key} aria-label={title}>
          <h2 className="mb-1.5 flex items-baseline gap-2 px-1 text-xs font-bold uppercase tracking-wide text-slate-500">
            {title} <span className="num font-semibold normal-case tracking-normal">{groups[key].length}{key !== 'paid' && totalInr(groups[key]) > 0 ? ` · ${money(totalInr(groups[key]))}` : ''}</span>
          </h2>
          <div className="card divide-y divide-slate-200/70 overflow-hidden">
            {groups[key].map((b) => <BillRow key={b.id} bill={b} today={today} open={b.id === openId} onChange={onChange} onRemove={onRemove} />)}
          </div>
        </section>
      ))}
      {(data.regular || []).length > 0 && <RegularBills list={data.regular} today={today} />}
    </>
  )
}

const EVERY = { month: 'Every month', quarter: 'Every 3 months', year: 'Every year' }

function ordinal(d) {
  return d + (d >= 11 && d <= 13 ? 'th' : { 1: 'st', 2: 'nd', 3: 'rd' }[d % 10] || 'th')
}

/** "It usually comes around the 5th." */
function usually(r) {
  return r.every === 'month' ? `It usually comes around the ${ordinal(r.usualDay)}.` : `It usually comes by ${shortDay(r.nextBy)}.`
}

/** The bills that come again and again: when the next one should come, and what it usually costs. */
function RegularBills({ list, today }) {
  return (
    <section aria-label="Regular bills">
      <h2 className="mb-1.5 flex items-baseline gap-2 px-1 text-xs font-bold uppercase tracking-wide text-slate-500">
        Regular bills <span className="num font-semibold normal-case tracking-normal">{list.length}</span>
      </h2>
      <div className="card divide-y divide-slate-200/70 overflow-hidden">
        {list.map((r) => {
          const d = daysLeft(r.nextBy, today)
          return (
            <div key={r.key} className="flex items-center gap-3 px-3 py-2.5">
              <div className="min-w-0 flex-1">
                <div className="truncate text-[15px] font-semibold text-brand-ink sm:text-sm">
                  {r.vendor}{r.account && <span className="font-normal text-slate-500"> · {r.account}</span>}
                </div>
                <div className="truncate text-sm text-slate-500 sm:text-xs">
                  {EVERY[r.every]}{r.every === 'month' ? `, around the ${ordinal(r.usualDay)}` : ''} · usually {money(r.usualAmount, r.currency)} · {r.count} bills seen
                </div>
              </div>
              <span className={`shrink-0 rounded-md px-1.5 py-0.5 text-xs font-semibold ring-1 ${r.late ? TONE_CLASS.soon : TONE_CLASS.calm}`}>
                {r.late ? 'Late' : d != null && d <= 0 ? 'Coming any day' : `Next by ${shortDay(r.nextBy)}`}
              </span>
            </div>
          )
        })}
      </div>
    </section>
  )
}

function Stat({ label, n, total, tone }) {
  return (
    <div className="card px-3 py-2.5">
      <div className="text-xs font-semibold text-slate-500">{label}</div>
      <div className={`text-lg font-bold leading-tight num ${tone}`}>{total > 0 ? money(total) : n}</div>
      <div className="text-xs text-slate-500 num">{n} bill{n === 1 ? '' : 's'}</div>
    </div>
  )
}

function BillRow({ bill, today, open, onChange, onRemove }) {
  const { notify } = useMail()
  const navigate = useNavigate()
  const [expanded, setExpanded] = useState(open)
  const [editing, setEditing] = useState(false)
  const [busy, setBusy] = useState(null)
  const ref = useRef(null)

  useEffect(() => {
    if (open) ref.current?.scrollIntoView({ block: 'center', behavior: 'smooth' })
  }, [open])

  async function setStatus(status) {
    setBusy(status)
    try {
      onChange(await patch(`/api/bills/${bill.id}`, { status }))
      notify(status === 'paid' ? `${bill.vendor}: marked paid ✓` : 'Marked as still to pay', 'success')
    } catch (e) {
      notify(e.message, 'error')
    } finally {
      setBusy(null)
    }
  }

  async function notABill() {
    setBusy('not')
    try {
      await post(`/api/bills/${bill.id}/not-a-bill`, { never: false })
      onRemove(bill.id)
      notify('Removed from Bills', 'info')
    } catch (e) {
      notify(e.message, 'error')
      setBusy(null)
    }
  }

  const tone = dueTone(bill, today)
  const shaky = ['amount', 'dueDate', 'vendor'].some((k) => unsure(bill, k))
  return (
    <div ref={ref} className={open ? 'bg-amber-50/40' : ''}>
      <div className="flex items-center gap-3 px-3 py-3">
        <button className="flex min-w-0 flex-1 items-center gap-3 text-left" onClick={() => setExpanded((v) => !v)} aria-expanded={expanded}>
          <div className="min-w-0 flex-1">
            <div className="flex items-center gap-1.5">
              <span className="truncate text-[15px] font-semibold text-brand-ink sm:text-sm">{bill.vendor}</span>
              {bill.accountName && <span className="shrink-0 truncate text-sm text-slate-500 sm:text-xs">· {bill.accountName}</span>}
              {shaky && <IconAlert size={13} className="shrink-0 text-amber-600" aria-label="Check this bill" />}
            </div>
            <div className="truncate text-sm text-slate-500 sm:text-xs">{bill.invoiceNo ? `Invoice ${bill.invoiceNo}` : bill.mailSubject || ''}</div>
          </div>
          <div className="shrink-0 text-right">
            <div className="text-[15px] font-bold num text-brand-ink sm:text-sm">{money(bill.amount, bill.currency)}</div>
            {inrNote(bill) && <div className="text-xs font-semibold text-slate-500 num">{inrNote(bill)}</div>}
            {bill.jump && <div className="text-xs font-semibold text-amber-700 num" title={`Usually ${money(bill.jump.usual, bill.currency)}`}>↑ {bill.jump.percent}% more than usual</div>}
            <span className={`mt-0.5 inline-block rounded-md px-1.5 py-0.5 text-xs font-semibold ring-1 ${TONE_CLASS[tone]}`}>{dueLabel(bill, today)}</span>
          </div>
          <IconChevron size={15} className={`shrink-0 text-slate-400 transition-transform ${expanded ? 'rotate-90' : ''}`} />
        </button>
        {bill.kind === 'bill' && bill.status === 'due' && !bill.autopay && (safeHttpUrl(bill.payLink) || upiLink(bill) || bill.messageId) && !expanded && (
          <button className="btn-primary shrink-0 !px-2.5 !py-1 text-xs" onClick={() => setExpanded(true)} title="Pay this bill">Pay</button>
        )}
        {bill.kind === 'bill' && bill.status === 'due' && (
          <button className="btn-secondary shrink-0 !px-2 !py-1 text-xs" onClick={() => setStatus('paid')} disabled={!!busy} title="Mark paid">
            {busy === 'paid' ? <span className="spinner" /> : <IconCheck size={14} />}<span className="max-sm:hidden"> Paid</span>
          </button>
        )}
      </div>
      {expanded && (
        <div className="border-t border-slate-200/60 px-3 pb-3">
          {editing
            ? <BillEditor bill={bill} onCancel={() => setEditing(false)} onSaved={(b) => { onChange(b); setEditing(false) }} />
            : (
              <>
                <BillFacts bill={bill} />
                <div className="mt-2.5"><PayNow bill={bill} compact onPaid={() => setStatus('paid')} busy={!!busy} onSetUp={() => setEditing(true)} /></div>
                {bill.mailFrom && <p className="mt-1.5 truncate text-xs text-slate-500">From the mail “{bill.mailSubject}” · {bill.mailFrom}</p>}
                <div className="mt-2.5 flex flex-wrap gap-1.5">
                  {bill.messageId && <button className="btn-secondary !px-2.5 !py-1 text-xs" onClick={() => navigate(`/?open=${bill.messageId}`)}>Open the mail</button>}
                  {bill.paidMessageId && <button className="btn-secondary !px-2.5 !py-1 text-xs" onClick={() => navigate(`/?open=${bill.paidMessageId}`)}>Open the bank alert</button>}
                  <button className="btn-secondary !px-2.5 !py-1 text-xs" onClick={() => setEditing(true)}>Edit</button>
                  {bill.kind === 'bill' && bill.status === 'paid' && <button className="btn-secondary !px-2.5 !py-1 text-xs" onClick={() => setStatus('due')} disabled={!!busy}>Still to pay</button>}
                  <button className="ml-auto text-xs text-slate-500 hover:text-brand-ink hover:underline" onClick={notABill} disabled={!!busy}>Not a bill</button>
                </div>
              </>
            )}
        </div>
      )}
    </div>
  )
}

/** Credits, free bills left, look further back, turn off. */
function Settings({ setup, onChange, onScanned }) {
  const { notify } = useMail()
  const [busy, setBusy] = useState(null)

  async function further() {
    setBusy('scan')
    try {
      const est = await get('/api/bills/scan?days=365')
      if (!est.bills) {
        notify('No other mail from the last year looks like a bill.', 'info')
      } else {
        const r = await post('/api/bills/scan', { days: 365 })
        notify(`Magizhchi Data is reading ${r.queued} more mail${r.queued === 1 ? '' : 's'}.`, 'success')
        onScanned()
      }
    } catch (e) {
      notify(e.message, 'error')
    } finally {
      setBusy(null)
    }
  }

  async function off() {
    setBusy('off')
    try {
      onChange(await put('/api/bills/setup', { on: false }))
      notify('Bills is off. Your bills stay here; no new mail is read.', 'info')
    } catch (e) {
      notify(e.message, 'error')
    } finally {
      setBusy(null)
    }
  }

  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-2 px-1 pt-2 text-xs text-slate-500">
      <span><b className="num text-slate-700">{setup.freeLeft}</b> of {setup.freePerMonth} free bills left this month</span>
      {setup.linked && <span>Magizhchi Data credits: <b className="num text-slate-700">{setup.balance}</b> · <a className="font-semibold text-peacock-700 hover:underline" href={DATA_WEB} target="_blank" rel="noreferrer">Add ↗</a></span>}
      <span className="ml-auto flex gap-3">
        <button className="font-semibold text-peacock-700 hover:underline" onClick={further} disabled={!!busy}>{busy === 'scan' && <span className="spinner" />} Look back a year</button>
        <button className="hover:text-brand-ink hover:underline" onClick={off} disabled={!!busy}>Turn off</button>
      </span>
    </div>
  )
}
