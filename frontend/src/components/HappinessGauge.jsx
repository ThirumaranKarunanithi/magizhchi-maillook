import { happinessTone } from '../util.js'

const TONES = {
  good: { stroke: '#15803d', track: '#dcfce7', text: 'text-emerald-700', bg: 'bg-emerald-50/70 border-emerald-200/70' },
  busy: { stroke: '#e08a0c', track: '#fdecc8', text: 'text-amber-700', bg: 'bg-amber-50/70 border-amber-200/70' },
  over: { stroke: '#b3261e', track: '#fde2e0', text: 'text-red-700', bg: 'bg-red-50/70 border-red-200/70' },
  muted: { stroke: '#a9b8b9', track: '#ebe2d2', text: 'text-slate-500', bg: 'bg-white/60 border-slate-200' },
}

/** Happiness gauge: a half-ring 0–100 with the server's label and message. */
export default function HappinessGauge({ happiness }) {
  const score = happiness?.score
  const tone = TONES[happinessTone(score)]
  const r = 34
  const half = Math.PI * r
  const pct = score == null ? 0 : Math.max(0, Math.min(100, score)) / 100
  return (
    <div className={`rounded-xl border px-3 py-2.5 ${tone.bg}`} title="How calm your inbox is right now">
      <div className="flex items-center gap-3">
        <svg width="84" height="48" viewBox="0 0 84 48" aria-hidden="true" className="shrink-0">
          <path d="M8 42 A34 34 0 0 1 76 42" fill="none" stroke={tone.track} strokeWidth="8" strokeLinecap="round" />
          <path d="M8 42 A34 34 0 0 1 76 42" fill="none" stroke={tone.stroke} strokeWidth="8" strokeLinecap="round"
            strokeDasharray={`${half * pct} ${half}`} style={{ transition: 'stroke-dasharray .8s var(--ease-out)' }} />
          <text x="42" y="41" textAnchor="middle" fontSize="17" fontWeight="700" fill="#0b3a42" className="num">
            {score == null ? '–' : Math.round(score)}
          </text>
        </svg>
        <div className="min-w-0">
          <div className="text-[11px] font-semibold uppercase tracking-wider text-slate-500">Happiness</div>
          <div className={`text-sm font-bold ${tone.text}`}>{happiness?.label || 'Checking…'}</div>
        </div>
      </div>
      {happiness?.message && <p className="mt-1.5 text-xs leading-snug text-slate-600">{happiness.message}</p>}
    </div>
  )
}
