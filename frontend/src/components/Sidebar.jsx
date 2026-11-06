import { useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { useAuth } from '../auth.jsx'
import { useMail } from '../state.jsx'
import HappinessGauge from './HappinessGauge.jsx'
import Logo from './Logo.jsx'
import Badge3D from './Badge3D.jsx'
import {
  IconAlert, IconArchive, IconClock, IconCompose, IconHand, IconInbox, IconInfo, IconLogout, IconNews, IconSend, IconSettings,
} from './Icons.jsx'

const LANE_ITEMS = [
  { view: 'needs_me', label: 'Needs me', Icon: IconHand, hue: 'marigold' },
  { view: 'fyi', label: 'For my info', Icon: IconInfo, hue: 'teal' },
  { view: 'can_wait', label: 'Can wait', Icon: IconClock, hue: 'dusk' },
]

const FOLDER_ITEMS = [
  { view: 'all', label: 'All mail', Icon: IconInbox },
  { view: 'sent', label: 'Sent', Icon: IconSend },
  { view: 'archive', label: 'Archive', Icon: IconArchive },
]

function Count({ unread, total }) {
  if (!unread && !total) return null
  return (
    <span className="ml-auto flex items-center gap-1 text-xs num">
      {unread > 0 && <span className="rounded-full bg-brand-gold/90 px-1.5 py-px font-bold text-[#4a2a04]">{unread}</span>}
      {total != null && <span className="text-slate-400">{total}</span>}
    </span>
  )
}

export function AccountDot({ color, size = 10 }) {
  return (
    <span
      className="inline-block shrink-0 rounded-full"
      style={{ width: size, height: size, background: color || '#0e7c86', boxShadow: `0 0 0 2px #0b2025, 0 0 0 3.5px ${color || '#0e7c86'}88, 0 0 10px ${color || '#0e7c86'}99` }}
      aria-hidden="true"
    />
  )
}

export default function Sidebar({ onNavigate }) {
  const navigate = useNavigate()
  const location = useLocation()
  const [params] = useSearchParams()
  const { user, logout } = useAuth()
  const { accounts, counts, accountId, setAccountId, openComposer, pa } = useMail()

  const onMail = location.pathname === '/'
  const currentView = params.get('view') || 'needs_me'

  function go(path) {
    navigate(path)
    onNavigate?.()
  }

  function pickAccount(id) {
    setAccountId(id)
    if (!onMail && location.pathname !== '/subscriptions') navigate(`/?view=${currentView}`)
    onNavigate?.()
  }

  const lanes = counts?.lanes || {}

  return (
    <nav className="scroll-thin flex h-full flex-col gap-4 overflow-y-auto px-3 py-4" aria-label="Mailboxes">
      <button className="px-1 text-left" onClick={() => go('/?view=needs_me')} aria-label="Magizhchi MailLook home">
        <Logo />
      </button>

      <button className="btn-primary w-full" onClick={() => { openComposer({ mode: 'new' }); onNavigate?.() }}>
        <IconCompose size={16} /> Compose
      </button>

      <HappinessGauge happiness={counts?.happiness} />

      <div>
        <div className="mb-1 px-2.5 text-[11px] font-semibold uppercase tracking-wider text-slate-500">Lanes</div>
        {LANE_ITEMS.map(({ view, label, Icon, hue }) => (
          <button key={view} className={`nav-item ${onMail && currentView === view ? 'active' : ''}`} onClick={() => go(`/?view=${view}`)}>
            <Badge3D hue={hue} Icon={Icon} size={24} />
            <span className="truncate">{label}</span>
            <Count unread={lanes[view]?.unread} total={lanes[view]?.total} />
          </button>
        ))}
      </div>

      <div>
        {FOLDER_ITEMS.map(({ view, label, Icon }) => (
          <button key={view} className={`nav-item ${onMail && currentView === view ? 'active' : ''}`} onClick={() => go(`/?view=${view}`)}>
            <Icon size={17} className="text-slate-500" />
            <span>{label}</span>
          </button>
        ))}
        <button className={`nav-item ${location.pathname === '/subscriptions' ? 'active' : ''}`} onClick={() => go('/subscriptions')}>
          <IconNews size={17} className="text-slate-500" />
          <span>Subscriptions</span>
        </button>
      </div>

      <div>
        <div className="mb-1 px-2.5 text-[11px] font-semibold uppercase tracking-wider text-slate-500">Accounts</div>
        <button className={`nav-item ${accountId == null ? 'active' : ''}`} onClick={() => pickAccount(null)}>
          <span className="flex w-[17px] justify-center">
            <span className="h-2.5 w-2.5 rounded-full" style={{ background: 'conic-gradient(#0e7c86, #f5b320, #be185d, #0e7c86)' }} />
          </span>
          <span>All accounts</span>
        </button>
        {accounts.map((a) => {
          const unread = counts?.accounts?.[a.id]?.unread ?? a.unread
          return (
            <button key={a.id} className={`nav-item ${accountId === a.id ? 'active' : ''}`} onClick={() => pickAccount(a.id)}
              title={a.lastError ? `${a.email}: ${a.lastError}` : a.email}>
              <span className="flex w-[17px] justify-center"><AccountDot color={a.color} /></span>
              <span className="min-w-0 flex-1 truncate">{a.displayName && a.displayName !== a.email ? a.displayName : a.email}</span>
              {a.status === 'error' && <IconAlert size={14} className="text-red-600" />}
              {unread > 0 && <span className="text-xs font-bold text-brand-primary num">{unread}</span>}
            </button>
          )
        })}
        <button className={`nav-item mt-1 ${location.pathname === '/accounts' ? 'active' : ''}`} onClick={() => go('/accounts')}>
          <IconSettings size={17} className="text-slate-500" />
          <span>Manage accounts</span>
        </button>
      </div>

      {pa && (
        <div className="mt-auto rounded-xl border border-slate-200/70 bg-slate-50/70 px-2.5 py-2 text-xs" title={pa.message || ''}>
          <div className="flex items-center gap-1.5 font-semibold text-slate-600">
            <span className={`h-2 w-2 rounded-full ${pa.connected ? 'bg-emerald-500 shadow-[0_0_8px_rgba(67,201,154,.9)]' : 'bg-slate-300'}`} />
            Magizhchi PA
            <span className="ml-auto font-normal text-slate-500">{pa.connected ? (pa.aiEnabled ? 'Connected · AI on' : 'Connected') : 'Not connected'}</span>
          </div>
          {pa.connected && pa.webUrl && (
            <a className="mt-0.5 block text-[11px] font-semibold text-peacock-700 hover:underline" href={pa.webUrl} target="_blank" rel="noreferrer">Open PA ↗</a>
          )}
          {!pa.connected && pa.message && <p className="mt-0.5 text-[11px] leading-snug text-slate-500">{pa.message}</p>}
        </div>
      )}

      <div className={`${pa ? '' : 'mt-auto '}flex items-center gap-2 border-t border-slate-200 px-1 pt-3 text-xs text-slate-500`}>
        <span className="min-w-0 flex-1 truncate" title={user?.email}>{user?.email || ''}</span>
        <button className="btn-ghost !px-1.5 !py-1 text-xs" onClick={logout} title="Sign out">
          <IconLogout size={15} /> Sign out
        </button>
      </div>
    </nav>
  )
}
