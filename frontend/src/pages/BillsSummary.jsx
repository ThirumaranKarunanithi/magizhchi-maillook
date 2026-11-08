import { useEffect, useState } from 'react'
import { downloadFile, get } from '../api.js'
import { useMail } from '../state.jsx'
import { CATEGORY_LABEL, money } from '../bills.js'

const monthName = (ym, opts = { month: 'long', year: 'numeric' }) => new Date(`${ym}-01T00:00:00`).toLocaleDateString([], opts)

function shift(ym, n) {
  const [y, m] = ym.split('-').map(Number)
  const d = new Date(y, m - 1 + n, 1)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

/** One month of bills: how much, where it went, how it compares, and the GST pack for the accountant. */
export default function BillsSummary() {
  const { notify } = useMail()
  const thisMonth = new Date().toLocaleDateString('en-CA').slice(0, 7)
  const [month, setMonth] = useState(thisMonth)
  const [sum, setSum] = useState(null)
  const [error, setError] = useState('')
  const [packing, setPacking] = useState(false)

  useEffect(() => {
    let live = true
    setError('')
    get(`/api/bills/summary?month=${month}`).then((s) => live && setSum(s)).catch((e) => live && setError(e.message))
    return () => { live = false }
  }, [month])

  async function pack() {
    setPacking(true)
    try {
      await downloadFile(`/api/bills/gst-pack?month=${month}`, `bills-${month}.zip`)
    } catch (e) {
      notify(e.status === 404 ? `No bills in ${monthName(month)} to pack.` : e.message, 'error')
    } finally {
      setPacking(false)
    }
  }

  const prev = sum ? Number(sum.previousTotal) : 0
  const total = sum ? Number(sum.total) : 0
  const change = prev > 0 ? Math.round(((total - prev) / prev) * 100) : null
  const maxCat = sum ? Math.max(1, ...sum.byCategory.map((c) => Number(c.total))) : 1
  const maxMonth = sum ? Math.max(1, ...sum.trend.map((t) => Number(t.total))) : 1

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2">
        <button className="icon-btn" onClick={() => setMonth((m) => shift(m, -1))} aria-label="Previous month">‹</button>
        <h2 className="min-w-0 flex-1 text-center text-base font-bold text-brand-ink">{monthName(month)}</h2>
        <button className="icon-btn" onClick={() => setMonth((m) => shift(m, 1))} disabled={month >= thisMonth} aria-label="Next month">›</button>
      </div>

      {error && <p className="rounded-xl bg-red-50 px-3 py-2 text-sm text-red-800">{error}</p>}
      {!sum ? <p className="py-10 text-center text-sm text-slate-500"><span className="spinner" /> Loading…</p> : (
        <>
          <div className="card p-4">
            <div className="text-xs font-semibold text-slate-500">Bills this month</div>
            <div className="text-3xl font-bold leading-tight text-brand-ink num">{money(total)}</div>
            <div className="mt-0.5 text-sm text-slate-600">
              {sum.count} bill{sum.count === 1 ? '' : 's'}
              {change != null && (
                <> · <span className={change > 0 ? 'font-semibold text-amber-800' : 'font-semibold text-emerald-800'}>{change > 0 ? '↑' : change < 0 ? '↓' : ''} {Math.abs(change)}%</span> against {monthName(shift(month, -1), { month: 'long' })} ({money(prev)})</>
              )}
            </div>
            {sum.unpriced > 0 && <p className="mt-1 text-xs text-slate-500">{sum.unpriced} bill{sum.unpriced === 1 ? ' is' : 's are'} in another currency with no rupee rate today, so not counted.</p>}
          </div>

          {sum.byCategory.length > 0 && (
            <section className="card p-4" aria-label="Where it went">
              <h3 className="mb-3 text-sm font-bold text-brand-ink">Where it went</h3>
              <ul className="space-y-2.5">
                {sum.byCategory.map((c) => {
                  const v = Number(c.total)
                  const pct = total > 0 ? Math.round((v / total) * 100) : 0
                  return (
                    <li key={c.category} title={`${CATEGORY_LABEL[c.category] || c.category}: ${money(v)} · ${c.count} bill${c.count === 1 ? '' : 's'} · ${pct}% of the month`}>
                      <div className="flex items-baseline justify-between gap-2 text-sm">
                        <span className="text-slate-700">{CATEGORY_LABEL[c.category] || c.category} <span className="text-xs text-slate-400">{c.count}</span></span>
                        <span className="font-semibold text-brand-ink num">{money(v)}</span>
                      </div>
                      <div className="mt-1 h-2 rounded-full bg-slate-100">
                        <div className="h-2 rounded-full bg-peacock-600" style={{ width: `${Math.max(2, (v / maxCat) * 100)}%` }} />
                      </div>
                    </li>
                  )
                })}
              </ul>
            </section>
          )}

          <section className="card p-4" aria-label="Last six months">
            <h3 className="mb-3 text-sm font-bold text-brand-ink">Last six months</h3>
            <div className="flex h-36 items-end gap-2" role="list">
              {sum.trend.map((t) => {
                const v = Number(t.total)
                const here = t.month === month
                return (
                  <button key={t.month} type="button" role="listitem" className="group flex h-full min-w-0 flex-1 flex-col items-center justify-end gap-1"
                    onClick={() => setMonth(t.month)} title={`${monthName(t.month)}: ${money(v)} · ${t.count} bill${t.count === 1 ? '' : 's'}`}
                    aria-label={`${monthName(t.month)}: ${money(v)}`}>
                    {here && <span className="text-[11px] font-semibold text-brand-ink num">{money(Math.round(v))}</span>}
                    <span className={`w-full max-w-[2.5rem] rounded-t ${here ? 'bg-peacock-700' : 'bg-peacock-300 group-hover:bg-peacock-400'}`}
                      style={{ height: `${v > 0 ? Math.max(3, (v / maxMonth) * 100) : 0}%` }} />
                    <span className={`text-xs ${here ? 'font-bold text-brand-ink' : 'text-slate-500'}`}>{monthName(t.month, { month: 'short' })}</span>
                  </button>
                )
              })}
            </div>
          </section>

          <section className="card p-4" aria-label="GST pack">
            <h3 className="text-sm font-bold text-brand-ink">GST pack for your accountant</h3>
            <p className="mt-1 text-sm text-slate-600">
              {sum.gst.bills > 0
                ? <>{sum.gst.bills} bill{sum.gst.bills === 1 ? '' : 's'} with a GSTIN · input GST <b className="num text-brand-ink">{money(sum.gst.total)}</b>
                  <span className="text-slate-500 num"> (CGST {money(sum.gst.cgst)} · SGST {money(sum.gst.sgst)}{Number(sum.gst.igst) > 0 ? ` · IGST ${money(sum.gst.igst)}` : ''})</span></>
                : 'No bills with a GSTIN this month.'}
            </p>
            <p className="mt-1 text-xs text-slate-500">A spreadsheet of every bill in {monthName(month, { month: 'long' })} (opens in Excel), with the PDF invoices of the bills that have a GSTIN.</p>
            <button className="btn-primary mt-3 !px-3 !py-1.5 text-sm" onClick={pack} disabled={packing || sum.count === 0}>
              {packing && <span className="spinner" />} {packing ? 'Packing…' : 'Download GST pack (.zip)'}
            </button>
          </section>
        </>
      )}
    </div>
  )
}
