import { Navigate, Outlet, Route, Routes, useLocation } from 'react-router-dom'
import { useAuth } from './auth.jsx'
import { MailProvider, useMail } from './state.jsx'
import Sidebar from './components/Sidebar.jsx'
import Composer from './components/Composer.jsx'
import Toast from './components/Toast.jsx'
import PaChat from './components/PaChat.jsx'
import Login, { SsoCallback } from './pages/Login.jsx'
import Mail from './pages/Mail.jsx'
import Accounts from './pages/Accounts.jsx'
import Subscriptions from './pages/Subscriptions.jsx'
import Automations from './pages/Automations.jsx'
import Calendar from './pages/Calendar.jsx'
import Contacts from './pages/Contacts.jsx'
import Today from './pages/Today.jsx'
import Scheduled from './pages/Scheduled.jsx'
import Files from './pages/Files.jsx'
import Bills from './pages/Bills.jsx'
import Forms from './pages/Forms.jsx'
import { DesktopBridge } from './desktop.js'
import AlertsPrompt from './components/AlertsPrompt.jsx'
import { NativeBridge } from './native.js'

function RequireAuth({ children }) {
  const { token } = useAuth()
  const location = useLocation()
  if (!token) return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />
  return children
}

function Shell() {
  const { composer, menuOpen, setMenuOpen } = useMail()
  return (
    <div className="flex h-full min-h-0 gap-2.5 overflow-hidden p-0 lg:p-2.5">
      {/* sidebar: fixed column on large screens, a drawer below that */}
      <aside className="slab rise-3d hidden w-64 shrink-0 overflow-hidden lg:block">
        <Sidebar />
      </aside>
      {menuOpen && (
        <div className="fixed inset-0 z-40 lg:hidden" role="dialog" aria-label="Navigation">
          <div className="modal-back absolute inset-0" onClick={() => setMenuOpen(false)} />
          <aside className="slide-in absolute inset-y-0 left-0 w-72 max-w-[85vw] border-r border-slate-200 bg-slate-50 shadow-[0_0_60px_rgba(0,0,0,.8)]">
            <Sidebar onNavigate={() => setMenuOpen(false)} />
          </aside>
        </div>
      )}
      <main className="flex min-w-0 flex-1 flex-col">
        <Outlet />
      </main>
      {composer && <Composer key={composer.key} />}
      <PaChat />
      <Toast />
      <DesktopBridge />
      <AlertsPrompt />
      <NativeBridge />
    </div>
  )
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route path="/sso/callback" element={<SsoCallback />} />
      <Route
        element={
          <RequireAuth>
            <MailProvider>
              <Shell />
            </MailProvider>
          </RequireAuth>
        }
      >
        <Route path="/" element={<Mail />} />
        <Route path="/accounts" element={<Accounts />} />
        <Route path="/subscriptions" element={<Subscriptions />} />
        <Route path="/automations" element={<Automations />} />
        <Route path="/calendar" element={<Calendar />} />
        <Route path="/contacts" element={<Contacts />} />
        <Route path="/today" element={<Today />} />
        <Route path="/scheduled" element={<Scheduled />} />
        <Route path="/files" element={<Files />} />
        <Route path="/bills" element={<Bills />} />
        <Route path="/forms" element={<Forms />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
