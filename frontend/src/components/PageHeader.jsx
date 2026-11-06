import { useMail } from '../state.jsx'
import { IconMenu } from './Icons.jsx'

/** Top bar for the full-width pages (accounts, subscriptions); carries the menu button on small screens. */
export default function PageHeader({ title, subtitle, children }) {
  const { setMenuOpen } = useMail()
  return (
    <header className="flex flex-wrap items-center gap-3 border-b border-slate-200/70 bg-white/70 px-4 py-3 sm:px-6">
      <button className="icon-btn lg:hidden" onClick={() => setMenuOpen(true)} aria-label="Open menu">
        <IconMenu />
      </button>
      <div className="min-w-0 flex-1">
        <h1 className="truncate text-lg font-bold text-brand-ink">{title}</h1>
        {subtitle && <p className="truncate text-xs text-slate-500">{subtitle}</p>}
      </div>
      {children}
    </header>
  )
}
