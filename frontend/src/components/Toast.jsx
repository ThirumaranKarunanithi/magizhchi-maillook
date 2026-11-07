import { useEffect, useState } from 'react'
import { useMail } from '../state.jsx'

/** Seconds left on a toast with a countdown (Undo send). */
function Countdown({ until }) {
  const [now, setNow] = useState(Date.now())
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 250)
    return () => clearInterval(t)
  }, [])
  const left = Math.max(0, Math.ceil((until - now) / 1000))
  return <span className="num tabular-nums text-slate-500">{left}s</span>
}

export default function Toast() {
  const { toast, dismissToast } = useMail()
  if (!toast) return null
  const tone =
    toast.kind === 'error'
      ? 'border-red-200 bg-red-50 text-red-800'
      : toast.kind === 'success'
        ? 'border-emerald-200 bg-emerald-50 text-emerald-800'
        : 'border-slate-200 bg-white text-brand-ink'
  return (
    <div className="pointer-events-none fixed inset-x-0 bottom-5 z-[60] flex justify-center px-4 max-md:bottom-20" role="status" aria-live="polite">
      <div key={toast.at} className={`pointer-events-auto flex max-w-md items-center gap-3 rounded-xl border px-4 py-2.5 text-sm font-medium shadow-lg ${tone}`}
        style={{ animation: 'modal-in .3s var(--ease-out) both' }}>
        <span className="min-w-0">{toast.text}</span>
        {toast.countdown && <Countdown until={toast.countdown} />}
        {toast.action && (
          <button className="shrink-0 rounded-lg px-2 py-1 text-sm font-bold text-peacock-700 hover:bg-peacock-50"
            onClick={() => { dismissToast(); toast.action.run() }}>
            {toast.action.label}
          </button>
        )}
      </div>
    </div>
  )
}
