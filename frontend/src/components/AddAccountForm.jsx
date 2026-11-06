import { useRef, useState } from 'react'
import { post } from '../api.js'
import { EMAIL_RE } from '../util.js'
import { IconChevron } from './Icons.jsx'

const PROVIDER_NAME = { hostinger: 'Hostinger', google: 'Google / Gmail', zoho: 'Zoho Mail', yahoo: 'Yahoo Mail', microsoft: 'Microsoft / Outlook', other: 'Other mail server' }

/** Add one mailbox. Looks up the provider when the email field loses focus. */
export default function AddAccountForm({ onAdded }) {
  const [email, setEmail] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [password, setPassword] = useState('')
  const [detect, setDetect] = useState(null) // { provider, note, ... }
  const [detecting, setDetecting] = useState(false)
  const [advanced, setAdvanced] = useState(false)
  const [hosts, setHosts] = useState({ imapHost: '', imapPort: '', smtpHost: '', smtpPort: '' })
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const lastDetected = useRef('')

  async function runDetect() {
    const e = email.trim()
    if (!EMAIL_RE.test(e) || e.toLowerCase() === lastDetected.current) return
    lastDetected.current = e.toLowerCase()
    setDetecting(true)
    try {
      const d = await post('/api/accounts/detect', { email: e })
      setDetect(d)
      setHosts({
        imapHost: d?.imapHost || '', imapPort: d?.imapPort ?? '',
        smtpHost: d?.smtpHost || '', smtpPort: d?.smtpPort ?? '',
      })
    } catch (err) {
      setDetect({ provider: 'other', note: `Couldn't look up this domain (${err.message}). Fill in the servers under Advanced.` })
      setAdvanced(true)
    } finally {
      setDetecting(false)
    }
  }

  async function submit(e) {
    e.preventDefault()
    setError('')
    if (!EMAIL_RE.test(email.trim())) return setError('That email address doesn’t look right.')
    const body = { email: email.trim(), password }
    if (displayName.trim()) body.displayName = displayName.trim()
    if (advanced || detect) {
      if (hosts.imapHost) body.imapHost = hosts.imapHost.trim()
      if (hosts.imapPort !== '') body.imapPort = Number(hosts.imapPort)
      if (hosts.smtpHost) body.smtpHost = hosts.smtpHost.trim()
      if (hosts.smtpPort !== '') body.smtpPort = Number(hosts.smtpPort)
    }
    setBusy(true)
    try {
      const account = await post('/api/accounts', body)
      setPassword('')
      setEmail('')
      setDisplayName('')
      setDetect(null)
      setAdvanced(false)
      lastDetected.current = ''
      onAdded?.(account)
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  const setHost = (k) => (e) => setHosts((h) => ({ ...h, [k]: e.target.value }))

  return (
    <form onSubmit={submit} className="space-y-3" autoComplete="off">
      <div className="grid gap-3 sm:grid-cols-2">
        <div>
          <label className="label" htmlFor="add-email">Email</label>
          <input id="add-email" className="input" type="email" required value={email} placeholder="info@magizhchi.academy"
            onChange={(e) => setEmail(e.target.value)} onBlur={runDetect} />
        </div>
        <div>
          <label className="label" htmlFor="add-name">Name in the sidebar <span className="font-normal text-slate-400">(optional)</span></label>
          <input id="add-name" className="input" value={displayName} placeholder="Info desk" onChange={(e) => setDisplayName(e.target.value)} />
        </div>
      </div>

      {(detecting || detect) && (
        <div className="rounded-lg border border-peacock-100 bg-peacock-50/70 px-3 py-2 text-sm">
          {detecting ? (
            <span className="text-slate-600"><span className="spinner mr-2 text-peacock-500" />Looking up your mail provider…</span>
          ) : (
            <>
              <span className="font-semibold text-peacock-700">{PROVIDER_NAME[detect.provider] || detect.provider}</span>
              {detect.imapHost && <span className="text-slate-500"> · {detect.imapHost}</span>}
              {detect.note && <p className="mt-0.5 text-xs text-slate-600">{detect.note}</p>}
            </>
          )}
        </div>
      )}

      <div>
        <label className="label" htmlFor="add-password">Mailbox password</label>
        <input id="add-password" className="input" type="password" required autoComplete="new-password"
          value={password} onChange={(e) => setPassword(e.target.value)} />
      </div>

      <button type="button" className="flex items-center gap-1 text-xs font-semibold text-slate-600 hover:text-brand-ink" onClick={() => setAdvanced((a) => !a)} aria-expanded={advanced}>
        <IconChevron open={advanced} size={14} /> Advanced: mail servers
      </button>
      {advanced && (
        <div className="grid gap-3 rounded-lg border border-slate-200 bg-slate-50/70 p-3 sm:grid-cols-[1fr_100px]">
          <div>
            <label className="label" htmlFor="imap-host">IMAP host (incoming)</label>
            <input id="imap-host" className="input" value={hosts.imapHost} onChange={setHost('imapHost')} placeholder="imap.hostinger.com" />
          </div>
          <div>
            <label className="label" htmlFor="imap-port">Port</label>
            <input id="imap-port" className="input" type="number" min="1" max="65535" value={hosts.imapPort} onChange={setHost('imapPort')} placeholder="993" />
          </div>
          <div>
            <label className="label" htmlFor="smtp-host">SMTP host (outgoing)</label>
            <input id="smtp-host" className="input" value={hosts.smtpHost} onChange={setHost('smtpHost')} placeholder="smtp.hostinger.com" />
          </div>
          <div>
            <label className="label" htmlFor="smtp-port">Port</label>
            <input id="smtp-port" className="input" type="number" min="1" max="65535" value={hosts.smtpPort} onChange={setHost('smtpPort')} placeholder="465" />
          </div>
        </div>
      )}

      {error && <p className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700" role="alert">{error}</p>}
      <button className="btn-primary" disabled={busy || !email || !password}>
        {busy && <span className="spinner" />} {busy ? 'Checking the login…' : 'Add account'}
      </button>
    </form>
  )
}
