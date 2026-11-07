import { IconSparkle, IconX } from './Icons.jsx'

/** "hall, advance · from Priya · 1 Nov – 30 Nov · with files": what the question was turned into. */
function planText(p) {
  if (!p) return ''
  const bits = []
  if (p.keywords?.length) bits.push(p.keywords.join(', '))
  if (p.from) bits.push(`from ${p.from}`)
  if (p.to) bits.push(`to ${p.to}`)
  const day = (d) => new Date(`${d}T00:00:00`).toLocaleDateString([], { day: 'numeric', month: 'short', year: 'numeric' })
  if (p.after && p.before) bits.push(`${day(p.after)} – ${day(p.before)}`)
  else if (p.after) bits.push(`since ${day(p.after)}`)
  else if (p.before) bits.push(`before ${day(p.before)}`)
  if (p.hasAttachment) bits.push('with files')
  if (p.folder === 'SENT') bits.push('mail you sent')
  return bits.join(' · ')
}

/** A question about your mail, as asked of AI: the answer, the mails it came from, and what was searched. */
export default function AskCard({ question, state, items, onOpen, onClose, onRetry }) {
  const byId = Object.fromEntries((items || []).map((m) => [m.id, m]))
  return (
    <section className="mx-3 my-2 rounded-xl border border-peacock-200 bg-peacock-50/60 p-3 text-sm" aria-live="polite" aria-label="AI answer">
      <div className="flex items-start gap-2">
        <IconSparkle size={16} className="mt-0.5 shrink-0 text-peacock-600" />
        <div className="min-w-0 flex-1">
          <p className="truncate text-[11px] font-semibold uppercase tracking-wide text-peacock-800">Asked AI: “{question}”</p>
          {state.loading && <p className="mt-1 flex items-center gap-2 text-slate-600"><span className="spinner" /> Reading your mail…</p>}
          {state.error && (
            <p className="mt-1 text-red-700">
              {state.error} <button className="font-semibold underline" onClick={onRetry}>Try again</button>
            </p>
          )}
          {state.result && (
            <>
              {state.result.answer && <p className="mt-1 font-medium leading-relaxed text-brand-ink">{state.result.answer}</p>}
              {state.result.sources?.length > 0 && (
                <div className="mt-1.5 flex flex-wrap gap-1">
                  {state.result.sources.map((id) => byId[id] && (
                    <button key={id} type="button" className="chip max-w-full !bg-white hover:!bg-peacock-100" onClick={() => onOpen(id)} title="Open this mail">
                      <span className="truncate">{byId[id].subject || '(no subject)'}</span>
                    </button>
                  ))}
                </div>
              )}
              <p className="mt-1.5 text-[11px] text-slate-500">
                {items?.length ? `${items.length} mail${items.length === 1 ? '' : 's'} found` : 'Nothing found'}
                {planText(state.result.plan) && <> · searched for {planText(state.result.plan)}</>}
                {state.result.plan?.loosened && ' (nothing matched every detail, so some were left out)'}
              </p>
            </>
          )}
        </div>
        <button className="icon-btn !h-6 !w-6" onClick={onClose} aria-label="Back to normal search results" title="Back to normal results"><IconX size={13} /></button>
      </div>
    </section>
  )
}
