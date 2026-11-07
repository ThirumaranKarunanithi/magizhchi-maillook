import { useId } from 'react'
import { happinessTone } from '../util.js'

// Each tone is graded on its own: a lit arc (bright core, darker ends), a recessed track and a soft glow.
const TONES = {
  good: { hi: '#a6f5d3', lo: '#1f9e70', glow: 'rgba(67,201,154,.55)', text: 'text-emerald-700', tint: 'rgba(67,201,154,.10)' },
  busy: { hi: '#ffe08a', lo: '#d9790a', glow: 'rgba(245,170,40,.55)', text: 'text-amber-700', tint: 'rgba(245,179,32,.10)' },
  over: { hi: '#ffb1a8', lo: '#c4302a', glow: 'rgba(240,100,90,.55)', text: 'text-red-700', tint: 'rgba(240,100,90,.10)' },
  muted: { hi: '#b9cbcb', lo: '#4f6d71', glow: 'rgba(150,180,180,.25)', text: 'text-slate-500', tint: 'rgba(255,255,255,.03)' },
}

/** Happiness gauge: a lit half-ring 0–100 with the server's label and message. */
export default function HappinessGauge({ happiness, onOpen }) {
  const id = useId().replace(/:/g, '')
  const score = happiness?.score
  const tone = TONES[happinessTone(score)]
  const r = 34
  const half = Math.PI * r
  const pct = score == null ? 0 : Math.max(0, Math.min(100, score)) / 100
  return (
    <div data-tilt="4" className="slab px-3 py-2.5" title="How calm your inbox is right now"
      style={{ background: `linear-gradient(160deg, ${tone.tint}, transparent 60%), var(--slab-bg)` }}>
      <div className="flex items-center gap-3">
        <svg width="84" height="50" viewBox="0 0 84 50" aria-hidden="true" className="shrink-0 overflow-visible">
          <defs>
            <linearGradient id={`arc${id}`} x1="0" y1="0" x2="1" y2="0">
              <stop offset="0" stopColor={tone.lo} />
              <stop offset="0.6" stopColor={tone.hi} />
              <stop offset="1" stopColor={tone.lo} />
            </linearGradient>
            <filter id={`glow${id}`} x="-30%" y="-60%" width="160%" height="220%">
              <feGaussianBlur stdDeviation="3" />
            </filter>
          </defs>
          {/* recessed track */}
          <path d="M8 42 A34 34 0 0 1 76 42" fill="none" style={{ stroke: 'var(--gauge-track)' }} strokeWidth="10" strokeLinecap="round" />
          <path d="M8 43 A34 34 0 0 1 76 43" fill="none" stroke="rgba(255,255,255,.06)" strokeWidth="9" strokeLinecap="round" />
          {/* glow under the lit arc */}
          <path d="M8 42 A34 34 0 0 1 76 42" fill="none" stroke={tone.glow} strokeWidth="9" strokeLinecap="round"
            strokeDasharray={`${half * pct} ${half}`} filter={`url(#glow${id})`} />
          <path d="M8 42 A34 34 0 0 1 76 42" fill="none" stroke={`url(#arc${id})`} strokeWidth="7" strokeLinecap="round"
            strokeDasharray={`${half * pct} ${half}`} style={{ transition: 'stroke-dasharray .8s var(--ease-out)' }} />
          {/* specular line along the arc */}
          <path d="M8 40.5 A34 34 0 0 1 76 40.5" fill="none" stroke="rgba(255,255,255,.45)" strokeWidth="1.2" strokeLinecap="round"
            strokeDasharray={`${half * pct} ${half}`} />
          <text x="42" y="42" textAnchor="middle" fontSize="18" fontWeight="800" className="num"
            style={{ fill: 'var(--gauge-ink)', filter: `drop-shadow(0 0 6px ${tone.glow})` }}>
            {score == null ? '–' : Math.round(score)}
          </text>
        </svg>
        <div className="min-w-0">
          <div className="text-[11px] font-semibold uppercase tracking-wider text-slate-500">Happiness</div>
          <div className={`text-sm font-bold ${tone.text}`}>{happiness?.label || 'Checking…'}</div>
        </div>
      </div>
      {happiness?.message && <p className="mt-1.5 text-xs leading-snug text-slate-600">{happiness.message}</p>}
      {happiness?.waitingOnOthers > 0 && (
        <p className="mt-0.5 text-xs leading-snug text-slate-600">
          You're waiting on {happiness.waitingOnOthers === 1 ? '1 reply' : `${happiness.waitingOnOthers} replies`}
        </p>
      )}
      {onOpen && (
        <button type="button" className="mt-1.5 text-xs font-semibold text-peacock-700 hover:underline" onClick={onOpen}>
          Today's briefing →
        </button>
      )}
    </div>
  )
}
