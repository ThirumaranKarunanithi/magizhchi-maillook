import { useEffect, useState } from 'react'
import { get } from '../api.js'

/** "Plus trial, 12 days left · 38 quick asks left today · 58 points left". */
export function planLine(p) {
  if (!p?.available) return ''
  const parts = []
  if (p.trial && p.trialEnds) {
    const days = Math.max(0, Math.ceil((new Date(p.trialEnds) - Date.now()) / 86400000))
    parts.push(`${p.label} trial, ${days} day${days === 1 ? '' : 's'} left`)
  } else {
    parts.push(`${p.label} plan`)
  }
  parts.push(p.quickLeft == null ? 'quick asks not counted' : `${p.quickLeft} quick ask${p.quickLeft === 1 ? '' : 's'} left today`)
  if (p.pointsPerMonth > 0) parts.push(`${p.pointsLeft} point${p.pointsLeft === 1 ? '' : 's'} left`)
  return parts.join(' · ')
}

/**
 * The person's Magizhchi PA plan under the chat header, with an Upgrade link to PA's pricing page.
 * Fetched again whenever `refresh` changes (the panel opening, a reply arriving). Hidden if PA can't say.
 */
export default function PaPlan({ refresh }) {
  const [plan, setPlan] = useState(null)
  useEffect(() => {
    let live = true
    get('/api/assistant/plan').then((p) => { if (live) setPlan(p) }).catch(() => {})
    return () => { live = false }
  }, [refresh])
  if (!plan?.available) return null
  const low = (plan.quickLeft != null && plan.quickLeft <= 2) || (plan.pointsPerMonth > 0 && plan.pointsLeft <= 3)
  const top = plan.plan === 'ELITE' || plan.plan === 'TEAM_SCALE'
  return (
    <div className={`pa-plan flex items-center gap-2 px-4 py-1.5 text-[11px] ${low ? 'pa-plan-low' : ''}`}>
      <span className="min-w-0 flex-1 truncate" title={planLine(plan)}>{planLine(plan)}</span>
      {!top && plan.upgradeUrl && (
        <a className="pa-chip on shrink-0" href={plan.upgradeUrl} target="_blank" rel="noopener noreferrer">
          {plan.trial || plan.plan === 'FREE' ? 'Choose a plan' : 'Upgrade'}
        </a>
      )}
    </div>
  )
}
