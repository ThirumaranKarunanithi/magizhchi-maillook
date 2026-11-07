import { createContext, useCallback, useContext, useEffect, useState } from 'react'
import { get, getToken, post, setToken, UNAUTHORIZED_EVENT } from './api.js'
import { forgetPhone } from './native.js'

const AuthCtx = createContext(null)

export function useAuth() {
  return useContext(AuthCtx)
}

export function AuthProvider({ children }) {
  const [token, setTokenState] = useState(() => getToken())
  const [user, setUser] = useState(null)

  // Any 401 anywhere: forget the session; the router sends us to /login.
  useEffect(() => {
    const onUnauthorized = () => {
      setTokenState(null)
      setUser(null)
    }
    window.addEventListener(UNAUTHORIZED_EVENT, onUnauthorized)
    return () => window.removeEventListener(UNAUTHORIZED_EVENT, onUnauthorized)
  }, [])

  useEffect(() => {
    if (!token || user) return
    get('/api/auth/me').then(setUser).catch(() => {})
  }, [token, user])

  // Trade the Magizhchi ID token (from the hosted sign-in redirect) for a MailLook session.
  const ssoLogin = useCallback(async (idToken) => {
    const res = await post('/api/auth/sso', { idToken })
    setToken(res.token)
    setTokenState(res.token)
    setUser(res.user || null)
    return res
  }, [])

  const logout = useCallback(async () => {
    await forgetPhone() // the phone app stops getting this person's alerts
    setToken(null)
    setTokenState(null)
    setUser(null)
  }, [])

  return <AuthCtx.Provider value={{ token, user, ssoLogin, logout }}>{children}</AuthCtx.Provider>
}
