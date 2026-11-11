// Small fetch wrapper for the Magizhchi MailLook API (see docs/API.md).
// Every call sends the bearer token; a 401 clears it and tells the app to go back to sign-in.

const TOKEN_KEY = 'mlook_token'

// Empty in dev (Vite proxies /api to :8097). Set VITE_API_URL for a build served elsewhere.
export const API_BASE = (import.meta.env.VITE_API_URL || '').replace(/\/+$/, '')

// Magizhchi ID's hosted sign-in page (same as Forms/CRM). Local dev: http://localhost:5180.
export const ID_LOGIN_URL = (import.meta.env.VITE_ID_LOGIN_URL || 'https://accounts.magizhchi.software').replace(/\/+$/, '')

/** Where to send the browser to sign in with Magizhchi ID; it comes back to /sso/callback#token=… */
export function idLoginUrl() {
  const back = `${window.location.origin}/sso/callback`
  return `${ID_LOGIN_URL}/?return=${encodeURIComponent(back)}&app=${encodeURIComponent('Magizhchi MailLook')}`
}

/** Fired on window when any signed-in request comes back 401. */
export const UNAUTHORIZED_EVENT = 'mlook:unauthorized'

export function getToken() {
  return localStorage.getItem(TOKEN_KEY)
}

export function setToken(token) {
  if (token) localStorage.setItem(TOKEN_KEY, token)
  else localStorage.removeItem(TOKEN_KEY)
}

export class ApiError extends Error {
  constructor(status, message, body) {
    super(message)
    this.status = status
    this.body = body
  }
}

async function request(path, { method = 'GET', body, form, signal } = {}) {
  const headers = {}
  const token = getToken()
  if (token) headers.Authorization = `Bearer ${token}`
  let payload
  if (form) {
    payload = form // the browser sets the multipart boundary itself
  } else if (body !== undefined) {
    headers['Content-Type'] = 'application/json'
    payload = JSON.stringify(body)
  }
  let res
  try {
    res = await fetch(API_BASE + path, { method, headers, body: payload, signal })
  } catch (e) {
    if (e?.name === 'AbortError') throw e
    throw new ApiError(0, "Can't reach Magizhchi MailLook right now. Is the server running?")
  }
  if (res.status === 401 && path !== '/api/auth/sso') {
    setToken(null)
    window.dispatchEvent(new Event(UNAUTHORIZED_EVENT))
  }
  return res
}

async function readJson(res) {
  const text = await res.text()
  if (!text) return null
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
}

/** JSON request. Resolves to the parsed body (null for 202/204); throws ApiError with the server's `error` text. */
export async function api(path, opts = {}) {
  const res = await request(path, opts)
  const data = await readJson(res)
  if (!res.ok) {
    throw new ApiError(res.status, (data && data.error) || `Something went wrong (${res.status})`, data)
  }
  return data
}

export const get = (path, opts) => api(path, opts)
export const post = (path, body) => api(path, { method: 'POST', body })
export const patch = (path, body) => api(path, { method: 'PATCH', body })
export const put = (path, body) => api(path, { method: 'PUT', body })
export const del = (path) => api(path, { method: 'DELETE' })
export const postForm = (path, form) => api(path, { method: 'POST', form })

function filenameFrom(disposition) {
  if (!disposition) return null
  const star = /filename\*\s*=\s*[^']*'[^']*'([^;]+)/i.exec(disposition)
  if (star) {
    try {
      return decodeURIComponent(star[1].trim().replace(/^"|"$/g, ''))
    } catch {
      /* fall through to the plain filename */
    }
  }
  const plain = /filename\s*=\s*"?([^";]+)"?/i.exec(disposition)
  return plain ? plain[1].trim() : null
}

/** Inside the Android app (mobile/), where a browser download does nothing. */
const inApp = () => typeof window !== 'undefined' && !!window.Capacitor?.isNativePlatform?.()

/** Downloads a protected file: a plain link can't carry the token, so fetch it and save the blob. */
export async function downloadFile(path, fallbackName = 'attachment') {
  const res = await request(path)
  if (!res.ok) {
    const data = await readJson(res)
    throw new ApiError(res.status, (data && data.error) || `Download failed (${res.status})`, data)
  }
  const blob = await res.blob()
  const name = fallbackName || filenameFrom(res.headers.get('Content-Disposition')) || 'attachment'
  if (inApp()) {
    const { nativeFile } = await import('./native.js')
    return nativeFile(blob, name)
  }
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = name
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 10000)
}

/** Fetches a protected file's bytes, for a caller that shows or saves them itself. */
export async function fileBlob(path, contentType) {
  const res = await request(path)
  if (!res.ok) {
    const data = await readJson(res)
    throw new ApiError(res.status, (data && data.error) || `Could not open the file (${res.status})`, data)
  }
  const raw = await res.blob()
  return contentType ? new Blob([raw], { type: contentType }) : raw
}

/**
 * Opens a file in a new tab (PDFs, pictures, text). The tab is opened first, inside the click, so pop-up
 * blockers allow it; the file is fetched with the sign-in token and then shown there.
 */
export async function openFile(path, contentType) {
  if (inApp()) {
    // The phone app: the file opens in the phone's own viewer (PDF, photos, Office…).
    const res = await request(path)
    if (!res.ok) {
      const data = await readJson(res)
      throw new ApiError(res.status, (data && data.error) || `Could not open the file (${res.status})`, data)
    }
    const { nativeFile } = await import('./native.js')
    return nativeFile(await res.blob(), filenameFrom(res.headers.get('Content-Disposition')) || 'file', { open: true, contentType })
  }
  const tab = window.open('', '_blank')
  try {
    const res = await request(path)
    if (!res.ok) {
      const data = await readJson(res)
      throw new ApiError(res.status, (data && data.error) || `Could not open the file (${res.status})`, data)
    }
    const raw = await res.blob()
    const blob = contentType ? new Blob([raw], { type: contentType }) : raw
    const url = URL.createObjectURL(blob)
    if (tab) tab.location.href = url
    else window.location.assign(url)
    setTimeout(() => URL.revokeObjectURL(url), 5 * 60 * 1000)
  } catch (e) {
    tab?.close()
    throw e
  }
}

/** Builds a query string, skipping empty values. */
export function qs(params) {
  const sp = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null && v !== '') sp.set(k, String(v))
  }
  const s = sp.toString()
  return s ? `?${s}` : ''
}
