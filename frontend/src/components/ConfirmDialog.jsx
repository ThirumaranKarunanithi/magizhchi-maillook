import { useEffect } from 'react'
import { createPortal } from 'react-dom'

export default function ConfirmDialog({ title, children, confirmLabel = 'Confirm', danger = false, busy = false, onConfirm, onCancel }) {
  useEffect(() => {
    const onKey = (e) => e.key === 'Escape' && onCancel?.()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onCancel])
  return createPortal(
    <div className="modal-back fixed inset-0 z-50 flex items-center justify-center p-4" onClick={onCancel}>
      <div className="card w-full max-w-md p-5" role="alertdialog" aria-modal="true" aria-label={title} onClick={(e) => e.stopPropagation()}>
        <h2 className="text-base font-bold text-brand-ink">{title}</h2>
        <div className="mt-2 text-sm text-slate-600">{children}</div>
        <div className="mt-5 flex justify-end gap-2">
          <button className="btn-secondary" onClick={onCancel} disabled={busy}>Cancel</button>
          <button className={danger ? 'btn-danger' : 'btn-primary'} onClick={onConfirm} disabled={busy} autoFocus>
            {busy && <span className="spinner" />} {confirmLabel}
          </button>
        </div>
      </div>
    </div>,
    document.body,
  )
}
