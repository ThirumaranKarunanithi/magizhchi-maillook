import { useMail } from '../state.jsx'

export default function Toast() {
  const { toast } = useMail()
  if (!toast) return null
  const tone =
    toast.kind === 'error'
      ? 'border-red-200 bg-red-50 text-red-800'
      : toast.kind === 'success'
        ? 'border-emerald-200 bg-emerald-50 text-emerald-800'
        : 'border-slate-200 bg-white text-brand-ink'
  return (
    <div className="pointer-events-none fixed inset-x-0 bottom-5 z-[60] flex justify-center px-4" role="status" aria-live="polite">
      <div key={toast.at} className={`pointer-events-auto max-w-md rounded-xl border px-4 py-2.5 text-sm font-medium shadow-lg ${tone}`}
        style={{ animation: 'modal-in .3s var(--ease-out) both' }}>
        {toast.text}
      </div>
    </div>
  )
}
