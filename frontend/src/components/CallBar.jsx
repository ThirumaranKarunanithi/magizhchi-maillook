// Magizhchi Meet's call bar inside MailLook: when someone calls this person from any Magizhchi app, it rings
// here, and Answer opens the call in Meet. Renders nothing itself; the bar draws its own card when a call comes.
//
// Only for people signed in with Magizhchi ID (calls name people by their ID account); for anyone else the
// pass request answers 409 (or 503 when calls aren't set up on the server) and the bar simply stays off.
// Off inside the Android app for now (mobile/): the phone shell isn't ready for calls yet.
import { useEffect } from 'react'
import { get, qs } from '../api.js'
import { native } from '../native.js'
import { desktop } from '../desktop.js'

/** Where Meet serves the suite's shared call bar. */
const MEET_WEB = (import.meta.env.VITE_MEET_WEB_URL || 'https://meet.magizhchi.software').replace(/\/+$/, '')
const SDK_URL = `${MEET_WEB}/sdk/magizhchi-call.js`

let loading = null
function loadSdk() {
  if (window.MagizhchiCall) return Promise.resolve(window.MagizhchiCall)
  if (!loading) {
    loading = new Promise((resolve, reject) => {
      const s = document.createElement('script')
      s.src = SDK_URL
      s.async = true
      s.onload = () => (window.MagizhchiCall ? resolve(window.MagizhchiCall) : reject(new Error('call bar missing')))
      s.onerror = () => {
        loading = null
        s.remove()
        reject(new Error('call bar did not load'))
      }
      document.head.appendChild(s)
    })
  }
  return loading
}

const fetchPass = ({ device, client }) => get(`/api/calls/ring-pass${qs({ device, client })}`)

export default function CallBar({ enabled = true }) {
  const on = enabled && !native
  useEffect(() => {
    if (!on) return undefined
    let stopped = false
    let first = null
    // One pass first: a 409 (no Magizhchi ID account) or 503 (calls not set up) means stay off, no retrying.
    loadSdk()
      .then((sdk) => fetchPass({ device: deviceId(), client: clientKind() }).then((p) => {
        first = p
        return sdk
      }))
      .then((sdk) => {
        if (stopped) return
        sdk.start({
          getPass: (args) => {
            if (first) {
              const p = first
              first = null
              return Promise.resolve(p)
            }
            return fetchPass(args)
          },
        })
      })
      .catch(() => { /* calls stay off in MailLook for this person; nothing to show */ })
    return () => {
      stopped = true
      try {
        window.MagizhchiCall?.stop()
      } catch {
        /* not loaded */
      }
    }
  }, [on])
  return null
}

// Same device id and client kind the bar itself uses, so the first pass and later ones match.
function deviceId() {
  try {
    let d = localStorage.getItem('magizhchi.call.device')
    if (!d) {
      d = `d-${Math.random().toString(36).slice(2)}${Date.now().toString(36)}`
      localStorage.setItem('magizhchi.call.device', d)
    }
    return d
  } catch {
    return 'unknown'
  }
}

function clientKind() {
  const ua = navigator.userAgent || ''
  if (desktop || /Electron/i.test(ua)) return 'desktop'
  if (/Android|iPhone|iPad/i.test(ua)) return 'phone'
  return 'web'
}
