import { useEffect, useRef, useState } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth.jsx'
import { idLoginUrl } from '../api.js'
import Logo from '../components/Logo.jsx'
import ThemeToggle from '../components/ThemeToggle.jsx'

const RETURN_KEY = 'mlook_after_login'

function Brand() {
  return (
    <div className="relative mb-8 flex flex-col items-center text-center">
      <div className="stage-light" aria-hidden="true" />
      <h1 className="relative">
        <Logo size={84} large />
      </h1>
      <p className="mt-10 text-base font-medium text-slate-700">The mail app that makes you happy again.</p>
      <p className="mt-0.5 text-xs text-slate-500" lang="ta">மகிழ்ச்சி: happiness</p>
    </div>
  )
}

/** Sign-in is Magizhchi ID: one account and password for the whole suite. */
export default function Login() {
  const { token } = useAuth()
  const location = useLocation()
  if (token) return <Navigate to={location.state?.from || '/'} replace />

  function go() {
    try {
      sessionStorage.setItem(RETURN_KEY, location.state?.from || '/')
    } catch {
      /* private mode: fine, we land on the inbox */
    }
    window.location.href = idLoginUrl()
  }

  return (
    <main className="relative flex min-h-full items-center justify-center overflow-hidden px-4 py-10">
      <div className="absolute right-4 top-4 z-10"><ThemeToggle /></div>
      <div className="rise-3d relative w-full max-w-md">
        <Brand />
        <div data-tilt="3" className="card space-y-4 p-6">
          <div>
            <h2 className="text-base font-bold">Sign in</h2>
            <p className="mt-0.5 text-xs text-slate-500">
              Use your Magizhchi ID, the same account as Magizhchi PA, Forms and CRM.
            </p>
          </div>
          <button className="btn-primary w-full !py-2.5 !text-[15px]" onClick={go}>
            <img src="/brand/id-mark-sm.png" alt="" width="24" height="18" className="object-contain" />
            Continue with Magizhchi ID
          </button>
          <p className="text-[11px] leading-relaxed text-slate-500">
            After signing in, add your mailboxes (for example every @magizhchi.academy address) under
            <span className="font-semibold"> Manage accounts</span>. AI, tasks and calendar come from Magizhchi PA.
          </p>
        </div>
      </div>
    </main>
  )
}

/** /sso/callback#token=… : Magizhchi ID sends the browser back here after sign-in. */
export function SsoCallback() {
  const { ssoLogin } = useAuth()
  const navigate = useNavigate()
  const [error, setError] = useState('')
  const started = useRef(false)

  useEffect(() => {
    if (started.current) return
    started.current = true
    const hash = new URLSearchParams(window.location.hash.slice(1))
    const idToken = hash.get('token')
    // Drop the token from the address bar and history right away.
    window.history.replaceState(null, '', window.location.pathname)
    if (!idToken) {
      setError('Magizhchi ID did not send a sign-in back. Please try again.')
      return
    }
    ssoLogin(idToken)
      .then(() => {
        let to = '/'
        try {
          to = sessionStorage.getItem(RETURN_KEY) || '/'
          sessionStorage.removeItem(RETURN_KEY)
        } catch {
          /* ignore */
        }
        navigate(to, { replace: true })
      })
      .catch((e) => setError(e.message || 'Sign-in failed'))
  }, [ssoLogin, navigate])

  return (
    <main className="relative flex min-h-full items-center justify-center overflow-hidden px-4 py-10">
      <div className="rise-3d relative w-full max-w-md">
        <Brand />
        <div className="card space-y-4 p-6 text-center">
          {error ? (
            <>
              <p className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700" role="alert">{error}</p>
              <button className="btn-primary w-full" onClick={() => (window.location.href = idLoginUrl())}>Try again</button>
            </>
          ) : (
            <p className="flex items-center justify-center gap-2 text-sm text-slate-600"><span className="spinner" /> Signing you in…</p>
          )}
        </div>
      </div>
    </main>
  )
}
