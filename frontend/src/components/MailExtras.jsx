import { useEffect, useState } from 'react'
import { get, post, qs } from '../api.js'
import { useMail } from '../state.jsx'
import { IconReply, IconSparkle } from './Icons.jsx'

const TAMIL = /[஀-௿]/

/**
 * Ready answers under a mail ("Yes, works for me"): a tap opens the reply with that answer written,
 * ready to send. Shown by itself for mail in Needs me; on other mail from a person, one tap asks for them.
 */
export function QuickReplies({ detail }) {
  const { aiEnabled, openComposer, accounts } = useMail()
  const fromMe = accounts.some((a) => a.email.toLowerCase() === (detail.fromEmail || '').toLowerCase())
  const eligible = aiEnabled && detail.folder === 'INBOX' && !fromMe && !detail.canUnsubscribe
  const [state, setState] = useState(eligible && detail.lane === 'needs_me' ? 'loading' : 'idle') // idle | loading | done | error
  const [replies, setReplies] = useState([])

  async function load() {
    setState('loading')
    try {
      const res = await get(`/api/ai/quick-replies${qs({ messageId: detail.id })}`)
      setReplies(Array.isArray(res?.replies) ? res.replies.filter((r) => r?.body) : [])
      setState('done')
    } catch {
      setState('error')
    }
  }

  useEffect(() => {
    if (eligible && detail.lane === 'needs_me') load()
  }, [detail.id]) // eslint-disable-line react-hooks/exhaustive-deps

  if (!eligible) return null
  if (state === 'idle') {
    return (
      <button className="btn-ghost !px-2 !py-1 text-xs text-peacock-700" onClick={load}>
        <IconSparkle size={13} /> Quick replies
      </button>
    )
  }
  if (state === 'done' && replies.length === 0) return null
  return (
    <section aria-label="Quick replies" className="flex flex-wrap items-center gap-1.5">
      <span className="mr-0.5 flex items-center gap-1 text-[11px] font-semibold uppercase tracking-wide text-slate-500"><IconSparkle size={12} /> Reply</span>
      {state === 'loading' && [0, 1, 2].map((i) => <span key={i} className="h-7 w-28 animate-pulse rounded-full bg-slate-100" />)}
      {state === 'error' && <button className="text-xs font-semibold text-peacock-700 hover:underline" onClick={load}>Couldn't get replies. Try again</button>}
      {state === 'done' && replies.map((r, i) => (
        <button key={i} className="chip !bg-white !py-1 !text-[13px] !text-brand-ink hover:!bg-peacock-50" title={r.body}
          onClick={() => openComposer({ mode: 'reply', message: detail, draftBody: r.body })}>
          <IconReply size={12} className="text-peacock-600" /> {r.label || r.body}
        </button>
      ))}
    </section>
  )
}

/** Tamil ↔ English for mail you receive: the translation shows in place of the mail, with a way back. */
export function Translate({ detail, children }) {
  const { aiEnabled, notify } = useMail()
  const isTamil = TAMIL.test(`${detail.subject || ''} ${detail.snippet || ''} ${(detail.bodyText || '').slice(0, 2000)}`)
  const [shown, setShown] = useState(null) // { to, subject, body, from }
  const [busy, setBusy] = useState(null)
  useEffect(() => setShown(null), [detail.id])

  async function translate(to) {
    setBusy(to)
    try {
      const res = await post('/api/ai/translate', { messageId: detail.id, to })
      setShown({ ...res, to })
    } catch (e) {
      notify(e.message, 'error')
    } finally {
      setBusy(null)
    }
  }

  if (!aiEnabled) return children
  const first = isTamil ? 'en' : 'ta'
  const label = { en: 'English', ta: 'தமிழ்' }
  return (
    <div>
      <div className="mb-1.5 flex flex-wrap items-center justify-end gap-x-3 gap-y-1 text-xs">
        {shown ? (
          <>
            <span className="mr-auto text-slate-500">Translated to {shown.to === 'ta' ? 'Tamil' : 'English'}{shown.from ? ` from ${shown.from}` : ''} by AI</span>
            <button className="font-semibold text-peacock-700 hover:underline" onClick={() => setShown(null)}>Show original</button>
          </>
        ) : (
          <>
            <button className="font-semibold text-peacock-700 hover:underline disabled:opacity-60" disabled={busy != null} onClick={() => translate(first)}>
              {busy === first ? 'Translating…' : `Translate to ${label[first]}`}
            </button>
            {!isTamil && (
              <button className="text-slate-500 hover:underline disabled:opacity-60" disabled={busy != null} onClick={() => translate('en')}>
                {busy === 'en' ? 'Translating…' : 'to English'}
              </button>
            )}
          </>
        )}
      </div>
      {shown ? (
        <div className="paper px-[clamp(18px,4vw,40px)] pb-8 pt-7">
          {shown.subject && <p className="mb-3 text-[15px] font-bold text-brand-ink">{shown.subject}</p>}
          <pre className="whitespace-pre-wrap break-words font-sans text-[15px] leading-[1.65]">{shown.body}</pre>
        </div>
      ) : children}
    </div>
  )
}
