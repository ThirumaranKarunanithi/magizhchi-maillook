import { useEffect, useState } from 'react'
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { useAuth } from '../auth.jsx'
import { useMail } from '../state.jsx'
import HappinessGauge from './HappinessGauge.jsx'
import Logo from './Logo.jsx'
import Badge3D from './Badge3D.jsx'
import ThemeToggle from './ThemeToggle.jsx'
import ConfirmDialog from './ConfirmDialog.jsx'
import { useInstall } from '../install.js'
import {
  IconAlert, IconArchive, IconBook, IconCalendar, IconChevron, IconClock, IconCompose, IconDraft, IconFolder, IconHand, IconInbox, IconInfo,
  IconInstall, IconLogout, IconNews, IconPaperclip, IconRefresh, IconSend, IconSettings, IconSnooze, IconSpam, IconSparkle, IconTrash,
} from './Icons.jsx'

/** "Install MailLook" on the home screen or desktop: the browser's own prompt, or Safari's steps on iPhone. */
function InstallButton() {
  const inst = useInstall()
  const { notify } = useMail()
  const [howto, setHowto] = useState(false)
  if (inst.installed || (!inst.canPrompt && !inst.ios)) return null
  return (
    <>
      <button className="nav-item" onClick={async () => {
        if (inst.canPrompt) {
          if (await inst.install()) notify('MailLook is installed ✓ Open it from your home screen or app list.', 'success')
        } else setHowto(true)
      }}>
        <IconInstall size={17} className="text-peacock-600" />
        <span>Install the app</span>
      </button>
      {howto && (
        <ConfirmDialog title="Add MailLook to your home screen" confirmLabel="Got it" onConfirm={() => setHowto(false)} onCancel={() => setHowto(false)}>
          <ol className="list-decimal space-y-1 pl-5">
            <li>In Safari, tap the <b>Share</b> button (the square with an arrow).</li>
            <li>Scroll down and tap <b>Add to Home Screen</b>.</li>
            <li>Tap <b>Add</b>. MailLook opens full screen from its icon, and alerts work there.</li>
          </ol>
        </ConfirmDialog>
      )}
    </>
  )
}

const LANE_ITEMS = [
  { view: 'needs_me', label: 'Needs me', Icon: IconHand, hue: 'marigold' },
  { view: 'fyi', label: 'For my info', Icon: IconInfo, hue: 'teal' },
  { view: 'can_wait', label: 'Can wait', Icon: IconClock, hue: 'dusk' },
]

const FOLDER_ITEMS = [
  { view: 'all', label: 'All mail', Icon: IconInbox },
  { view: 'drafts', label: 'Drafts', Icon: IconDraft },
  { path: '/scheduled', label: 'Scheduled', Icon: IconClock },
  { view: 'snoozed', label: 'Snoozed', Icon: IconSnooze },
  { view: 'waiting', label: 'Waiting on others', Icon: IconClock },
  { view: 'sent', label: 'Sent', Icon: IconSend },
  { view: 'archive', label: 'Archive', Icon: IconArchive },
  { view: 'spam', label: 'Spam', Icon: IconSpam },
  { view: 'trash', label: 'Trash', Icon: IconTrash },
]

const OWN_OPEN_KEY = 'mlook_own_folders_open'

function readOpen() {
  try {
    return localStorage.getItem(OWN_OPEN_KEY) === '1'
  } catch {
    return false
  }
}

/** The mailbox's own folders (Gmail labels too). Listing asks the mail server, so it waits until opened. */
function OwnFolders({ go, currentFolder }) {
  const { folders, refreshFolders, openFolder, accounts, accountId, notify } = useMail()
  const [open, setOpen] = useState(readOpen)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  function load(refresh = false) {
    setBusy(true)
    setError('')
    refreshFolders(refresh).catch((e) => setError(e.message)).finally(() => setBusy(false))
  }

  useEffect(() => {
    if (open && folders == null && !busy) load()
  }, [open]) // eslint-disable-line react-hooks/exhaustive-deps

  function toggle() {
    const next = !open
    setOpen(next)
    try {
      localStorage.setItem(OWN_OPEN_KEY, next ? '1' : '0')
    } catch {
      /* fine */
    }
  }

  async function pick(acc, f) {
    const url = `/?view=folder&f=${encodeURIComponent(f.code)}&name=${encodeURIComponent(f.name)}`
    if (f.opened) return go(url)
    try {
      await openFolder(acc.accountId, f.path)
      notify(`Fetching ${f.name}… the newest mail shows up in a few seconds.`)
      go(url)
    } catch (e) {
      notify(e.message, 'error')
    }
  }

  const shown = (folders || []).filter((a) => accountId == null || a.accountId === accountId)
  const many = shown.length > 1

  return (
    <div>
      <div className="flex items-center">
        <button className="flex flex-1 items-center gap-1 px-2.5 py-1 text-[11px] font-semibold uppercase tracking-wider text-slate-500 hover:text-brand-ink"
          onClick={toggle} aria-expanded={open}>
          <IconChevron open={open} size={13} /> Your folders
        </button>
        {open && (
          <button className="icon-btn !p-1" onClick={() => load(true)} disabled={busy} title="Look for new folders">
            <IconRefresh size={13} className={busy ? 'animate-spin' : ''} />
          </button>
        )}
      </div>
      {open && (
        <div>
          {busy && folders == null && <p className="flex items-center gap-2 px-2.5 py-1.5 text-xs text-slate-500"><span className="spinner" /> Asking your mail server…</p>}
          {error && <p className="px-2.5 py-1 text-xs text-red-700">{error}</p>}
          {folders != null && !shown.some((a) => a.folders.length || a.error) && (
            <p className="px-2.5 py-1 text-xs text-slate-500">{accounts.length ? 'No folders of your own yet. Folders you make in other mail apps show up here.' : 'Add a mailbox first.'}</p>
          )}
          {shown.map((acc) => {
            const color = accounts.find((a) => a.id === acc.accountId)?.color
            return (
              <div key={acc.accountId}>
                {many && (acc.folders.length > 0 || acc.error) && (
                  <div className="mt-1 flex items-center gap-1.5 truncate px-2.5 text-[11px] text-slate-500"><AccountDot color={color} size={7} />{acc.email}</div>
                )}
                {acc.error && <p className="px-2.5 py-1 text-[11px] text-red-700">{acc.error}</p>}
                {acc.folders.map((f) => (
                  <button key={f.code} className={`nav-item !py-1.5 ${currentFolder === f.code ? 'active' : ''}`} onClick={() => pick(acc, f)} title={f.path}>
                    <IconFolder size={16} className="text-slate-500" />
                    <span className="min-w-0 flex-1 truncate">{f.name}</span>
                  </button>
                ))}
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}

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
      style={{ width: size, height: size, background: color || '#0e7c86', boxShadow: `0 0 0 2px var(--ring-bg), 0 0 0 3.5px ${color || '#0e7c86'}88, 0 0 10px ${color || '#0e7c86'}99` }}
      aria-hidden="true"
    />
  )
}

export default function Sidebar({ onNavigate }) {
  const navigate = useNavigate()
  const location = useLocation()
  const [params] = useSearchParams()
  const { user, logout } = useAuth()
  const { accounts, counts, accountId, setAccountId, openComposer, pa, drafts } = useMail()

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
  const draftCount = (drafts || []).filter((d) => accountId == null || d.accountId === accountId).length
  const currentFolder = currentView === 'folder' ? params.get('f') : null

  return (
    <nav className="scroll-thin flex h-full flex-col gap-4 overflow-y-auto px-3 py-4" aria-label="Mailboxes">
      <div className="flex shrink-0 items-start gap-2">
        <button className="min-w-0 flex-1 px-1 text-left" onClick={() => go('/?view=needs_me')} aria-label="Magizhchi MailLook home">
          <Logo tagline={false} />
        </button>
        <ThemeToggle className="mt-1" />
      </div>

      <button className="btn-primary btn-compose" onClick={() => { openComposer({ mode: 'new' }); onNavigate?.() }}>
        <span className="disc"><IconCompose size={14} strokeWidth={2.2} /></span> Compose
      </button>

      <HappinessGauge happiness={counts?.happiness} onOpen={() => go('/today')} />

      <button className={`nav-item -mt-2 ${location.pathname === '/today' ? 'active' : ''}`} onClick={() => go('/today')}>
        <IconSparkle size={17} className="text-peacock-600" />
        <span>Today</span>
      </button>

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
        {FOLDER_ITEMS.map(({ view, path, label, Icon }) => path ? (
          // Scheduled shows only while something waits there (or it's open).
          (counts?.scheduled > 0 || location.pathname === path) && (
            <button key={path} className={`nav-item ${location.pathname === path ? 'active' : ''}`} onClick={() => go(path)}>
              <Icon size={17} className="text-slate-500" />
              <span>{label}</span>
              {counts?.scheduled > 0 && <span className="ml-auto text-xs text-slate-500 num">{counts.scheduled}</span>}
            </button>
          )
        ) : (
          <button key={view} className={`nav-item ${onMail && currentView === view ? 'active' : ''}`} onClick={() => go(`/?view=${view}`)}>
            <Icon size={17} className="text-slate-500" />
            <span>{label}</span>
            {view === 'drafts' && draftCount > 0 && <span className="ml-auto text-xs text-slate-500 num">{draftCount}</span>}
            {view === 'waiting' && counts?.happiness?.waitingOnOthers > 0 && <span className="ml-auto text-xs text-slate-500 num">{counts.happiness.waitingOnOthers}</span>}
          </button>
        ))}
        <button className={`nav-item ${location.pathname === '/files' ? 'active' : ''}`} onClick={() => go('/files')}>
          <IconPaperclip size={17} className="text-slate-500" />
          <span>Files</span>
        </button>
        <button className={`nav-item ${location.pathname === '/subscriptions' ? 'active' : ''}`} onClick={() => go('/subscriptions')}>
          <IconNews size={17} className="text-slate-500" />
          <span>Subscriptions</span>
        </button>
      </div>

      <div>
        <button className={`nav-item ${location.pathname === '/calendar' ? 'active' : ''}`} onClick={() => go('/calendar')}>
          <IconCalendar size={17} className="text-slate-500" />
          <span>Calendar</span>
          <span className="ml-auto text-[10px] font-semibold uppercase tracking-wide text-slate-400">PA</span>
        </button>
        <button className={`nav-item ${location.pathname === '/contacts' ? 'active' : ''}`} onClick={() => go('/contacts')}>
          <IconBook size={17} className="text-slate-500" />
          <span>Contacts</span>
        </button>
      </div>

      <OwnFolders go={go} currentFolder={onMail ? currentFolder : null} />

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
        <InstallButton />
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
