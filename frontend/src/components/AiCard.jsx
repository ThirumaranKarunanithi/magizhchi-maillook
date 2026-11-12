import { useState } from 'react'
import { post } from '../api.js'
import { useMail } from '../state.jsx'
import { IconSparkle } from './Icons.jsx'

const INTENT_STYLE = {
  accept: 'border-emerald-200 bg-emerald-50 text-emerald-800 hover:border-emerald-400',
  decline: 'border-red-200 bg-red-50 text-red-800 hover:border-red-400',
  ask: 'border-amber-200 bg-amber-50 text-amber-800 hover:border-amber-400',
}

/** Summarize / Draft reply, powered by Magizhchi PA's AI. Action items can go straight to PA as tasks. */
export default function AiCard({ messageId, onDraft }) {
  const { aiEnabled, setAiEnabled, aiInfo, refreshAi, pa, notify } = useMail()
  const [added, setAdded] = useState({})

  async function addAction(i, what) {
    setAdded((m) => ({ ...m, [i]: 'busy' }))
    try {
      const r = await post(`/api/messages/${messageId}/pa/task`, { title: what })
      setAdded((m) => ({ ...m, [i]: 'done' }))
      notify(r?.message || 'Added to Magizhchi PA', 'success')
    } catch (e) {
      setAdded((m) => ({ ...m, [i]: undefined }))
      notify(e.message, 'error')
    }
  }
  const [summary, setSummary] = useState(null)
  const [drafts, setDrafts] = useState(null)
  const [busy, setBusy] = useState(null)
  const [error, setError] = useState('')
  const disabled = aiEnabled === false

  async function recheck() {
    setBusy('check')
    const on = await refreshAi()
    setBusy(null)
    notify(on ? 'AI is on. Suggestions are back.' : 'PA’s AI is still off.', on ? 'success' : 'info')
  }

  async function run(kind) {
    setBusy(kind)
    setError('')
    try {
      if (kind === 'summary') setSummary(await post(`/api/messages/${messageId}/summary`))
      else setDrafts((await post(`/api/messages/${messageId}/drafts`))?.drafts || [])
    } catch (e) {
      if (e.status === 503) setAiEnabled(false)
      else setError(e.message)
    } finally {
      setBusy(null)
    }
  }

  return (
    <section data-tilt="2" className={`relative rounded-2xl border p-3.5 ${disabled ? 'border-slate-200 bg-slate-50/80' : 'ai-panel border-peacock-300/60'}`}
      aria-label="AI helper">
      <div className="flex flex-wrap items-center gap-2">
        <span className={`flex items-center gap-1.5 text-sm font-semibold ${disabled ? 'text-slate-500' : 'text-peacock-700'}`}>
          <IconSparkle size={16} className={disabled ? "" : "drop-shadow-[0_0_6px_rgba(82,198,201,.9)]"} /> AI helper
        </span>
        {aiInfo?.provider === 'pa' && <span className="rounded-full bg-peacock-50 px-2 py-0.5 text-[10.5px] font-semibold text-peacock-700 ring-1 ring-peacock-300/50">by Magizhchi PA</span>}
        <div className="ml-auto flex gap-2">
          <button className="btn-secondary !py-1 text-xs" disabled={disabled || !!busy} onClick={() => run('summary')}>
            {busy === 'summary' && <span className="spinner !h-3 !w-3" />} Summarize
          </button>
          <button className="btn-secondary !py-1 text-xs" disabled={disabled || !!busy} onClick={() => run('drafts')}>
            {busy === 'drafts' && <span className="spinner !h-3 !w-3" />} Draft reply
          </button>
        </div>
      </div>

      {disabled && (
        <p className="mt-1.5 text-xs text-slate-500">
          AI comes from Magizhchi PA. {pa?.connected === false ? pa.message : 'Switch on PA’s AI (ANTHROPIC_API_KEY on the PA server).'}{' '}
          <button className="font-semibold text-peacock-700 hover:underline disabled:opacity-60" disabled={busy === 'check'} onClick={recheck}>
            {busy === 'check' ? 'Checking…' : 'Check again'}
          </button>
        </p>
      )}
      {error && <p className="mt-2 text-xs text-red-700">{error}</p>}

      {summary && !disabled && (
        <div className="mt-3 space-y-1.5 text-sm">
          <p className="font-medium text-brand-ink">{summary.summary}</p>
          {summary.decision && <p><span className="font-semibold text-slate-600">Decision: </span>{summary.decision}</p>}
          {summary.actions?.length > 0 && (
            <div>
              <span className="font-semibold text-slate-600">Actions:</span>
              <ul className="ml-5 list-disc">
                {summary.actions.map((a, i) => (
                  <li key={i}>
                    <span className="font-medium">{a.who}</span>: {a.what}
                    {pa?.connected && (
                      <button className="ml-2 rounded-full border border-peacock-200 bg-white px-2 py-0.5 text-[11px] font-semibold text-peacock-700 hover:border-peacock-400 disabled:opacity-60"
                        disabled={!!added[i]} onClick={() => addAction(i, a.what)}>
                        {added[i] === 'done' ? '✓ In PA' : added[i] === 'busy' ? 'Adding…' : '+ PA task'}
                      </button>
                    )}
                  </li>
                ))}
              </ul>
            </div>
          )}
          {summary.nextStep && <p><span className="font-semibold text-slate-600">Next step: </span>{summary.nextStep}</p>}
        </div>
      )}

      {drafts && !disabled && (
        <div className="mt-3">
          {drafts.length ? (
            <>
              <p className="mb-1.5 text-xs text-slate-500">Pick one to open it in the composer, then make it yours.</p>
              <div className="flex flex-wrap gap-2">
                {drafts.map((d, i) => (
                  <button key={i} title={d.body}
                    className={`rounded-full border px-3 py-1 text-sm font-medium transition-colors ${INTENT_STYLE[d.intent] || INTENT_STYLE.ask}`}
                    onClick={() => onDraft(d.body)}>
                    {d.label || d.intent}
                  </button>
                ))}
              </div>
            </>
          ) : (
            <p className="text-xs text-slate-500">No drafts this time.</p>
          )}
        </div>
      )}
    </section>
  )
}
