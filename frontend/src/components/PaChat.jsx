import { useCallback, useEffect, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { get, post, qs } from '../api.js'
import { useMail } from '../state.jsx'
import { PA_DONE, PA_SEARCH, emit, screen } from '../pabus.js'
import { IconMic, IconSend, IconX } from './Icons.jsx'

const STORE = 'mlook.pa.chat'
const SPEAK = 'mlook.pa.speak'
const MAIL_VIEWS = new Set(['needs_me', 'fyi', 'can_wait', 'all', 'sent', 'archive', 'spam', 'trash', 'drafts', 'snoozed', 'waiting'])
const PLACE_LABEL = {
  needs_me: 'Needs me', fyi: 'For my info', can_wait: 'Can wait', all: 'All mail', sent: 'Sent', archive: 'Archive', spam: 'Spam',
  trash: 'Trash', drafts: 'Drafts', snoozed: 'Snoozed', waiting: 'Waiting on others', today: 'Today', files: 'Files', bills: 'Bills',
  forms: 'Forms', subscriptions: 'Subscriptions', calendar: 'Calendar', contacts: 'Contacts', accounts: 'Manage accounts', scheduled: 'Scheduled',
}
const ACT_LABEL = {
  archive: 'Archived', trash: 'Moved to Trash', spam: 'Moved to Spam', flag: 'Flagged', unflag: 'Unflagged', read: 'Marked read',
  unread: 'Marked unread', snooze: 'Snoozed', lane: 'Moved',
}
const LANE_LABEL = { needs_me: 'Needs me', fyi: 'For my info', can_wait: 'Can wait' }
const IDEAS = [
  'What needs me today?',
  'Open the latest invoice',
  'Archive this mail',
  'Snooze this till tomorrow 9 am',
  'Reply that I’ll call at 5',
  'What’s on my calendar tomorrow?',
]

function load() {
  try { return JSON.parse(sessionStorage.getItem(STORE) || '[]') } catch { return [] }
}
function keep(msgs) {
  try { sessionStorage.setItem(STORE, JSON.stringify(msgs.slice(-40))) } catch { /* private window */ }
}
function readSpeak() {
  try { return localStorage.getItem(SPEAK) === '1' } catch { return false }
}

/** Reads a reply aloud, in Tamil when it is written in Tamil. */
function say(text) {
  if (!('speechSynthesis' in window) || !text) return
  window.speechSynthesis.cancel()
  const u = new SpeechSynthesisUtterance(text.replace(/[*_`#]/g, ''))
  u.lang = /[஀-௿]/.test(text) ? 'ta-IN' : 'en-IN'
  u.rate = 1.03
  window.speechSynthesis.speak(u)
}

function toBase64(blob) {
  return new Promise((resolve, reject) => {
    const r = new FileReader()
    r.onload = () => resolve(String(r.result).split(',')[1] || '')
    r.onerror = reject
    r.readAsDataURL(blob)
  })
}

/** PA's own logo (the lamp and its smiling sun): it floats and glows, pulses while listening, and a ring turns while it thinks. */
function Orb({ size = 40, state = 'idle', still = false }) {
  return (
    <span className={`pa-logo pa-orb-${state} ${still ? 'still' : ''}`} style={{ width: size, height: size }} aria-hidden="true">
      <img src="/brand/pa-logo-128.webp" alt="" width={size} height={size} decoding="async" />
    </span>
  )
}

function Bubble({ m, onRun, onOpenMail, onDecide, onUndo }) {
  if (m.role === 'user') {
    return (
      <div className="flex justify-end">
        <div className="pa-bubble-me max-w-[85%] whitespace-pre-wrap rounded-2xl rounded-br-md px-3.5 py-2 text-sm">{m.voice && <IconMic size={12} className="mr-1 inline opacity-70" />}{m.content}</div>
      </div>
    )
  }
  return (
    <div className="flex gap-2">
      <Orb size={26} still />
      <div className="min-w-0 max-w-[88%] space-y-1.5">
        {m.content && <div className={`pa-bubble whitespace-pre-wrap rounded-2xl rounded-tl-md px-3.5 py-2 text-sm ${m.error ? 'pa-bubble-error' : ''}`}>{m.content}</div>}
        {m.done?.map((d, i) => (
          <div key={i} className="pa-step flex items-center gap-2 rounded-xl px-2.5 py-1.5 text-xs">
            <span className={`h-1.5 w-1.5 shrink-0 rounded-full ${d.failed ? 'bg-red-500' : 'bg-emerald-500'}`} />
            <span className="min-w-0 flex-1 truncate">{d.text}</span>
            {d.openId && <button className="pa-link shrink-0" onClick={() => onOpenMail(d.openId)}>Open</button>}
            {d.undo && !d.undone && <button className="pa-link shrink-0" onClick={() => onRun(d)}>Undo</button>}
            {d.undone && <span className="shrink-0 text-slate-400">Undone</span>}
          </div>
        ))}
        {m.sources?.length > 0 && (
          <div className="space-y-1">
            {m.sources.map((s) => (
              <button key={s.id} className="pa-step flex w-full items-center gap-2 rounded-xl px-2.5 py-1.5 text-left text-xs" onClick={() => onOpenMail(s.id)}>
                <span className="min-w-0 flex-1 truncate"><b>{s.from}</b> · {s.subject}</span>
                <span className="pa-link shrink-0">Open</span>
              </button>
            ))}
          </div>
        )}
        {m.pa?.map((a, i) => (
          <PaAction key={i} a={a} onDecide={(ans) => onDecide(m, i, ans)} onUndo={() => onUndo(m, i)} />
        ))}
      </div>
    </div>
  )
}

/** What PA's own assistant did: a mail waiting for Send, a sent mail with Undo, a calendar change… */
function PaAction({ a, onDecide, onUndo }) {
  if (a.type === 'CONFIRM_MAIL') {
    return (
      <div className="pa-card rounded-xl p-2.5 text-xs">
        <div className="font-semibold text-brand-ink">{a.subject || 'A mail ready to send'}</div>
        {a.email && <div className="text-slate-500">To {a.email}</div>}
        {a.body && <div className="mt-1 line-clamp-4 whitespace-pre-wrap text-slate-600">{a.body}</div>}
        {a.attachments?.length > 0 && <div className="mt-1 text-slate-500">With {a.attachments.join(', ')}</div>}
        {a.answer ? (
          <div className="mt-2 font-semibold text-slate-500">{a.answer === 'approve' ? 'Sent ✓' : 'Discarded'}</div>
        ) : (
          <div className="mt-2 flex gap-2">
            <button className="btn-primary !py-1 text-xs" disabled={a.busy} onClick={() => onDecide('approve')}>{a.busy && <span className="spinner" />} Send</button>
            <button className="btn-secondary !py-1 text-xs" disabled={a.busy} onClick={() => onDecide('decline')}>Discard</button>
          </div>
        )}
      </div>
    )
  }
  return (
    <div className="pa-step flex items-center gap-2 rounded-xl px-2.5 py-1.5 text-xs">
      <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-emerald-500" />
      <span className="min-w-0 flex-1">{a.summary || a.type?.toLowerCase().replace(/_/g, ' ')}</span>
      {a.activityId && a.undoSeconds > 0 && !a.undone && <button className="pa-link shrink-0" onClick={onUndo}>Undo</button>}
      {a.undone && <span className="shrink-0 text-slate-400">Undone</span>}
    </div>
  )
}

/**
 * Magizhchi PA's chat inside MailLook: type or speak, and PA works the app (open, find, archive, snooze,
 * reply, compose) or answers from your calendar, tasks and contacts. Ctrl+J opens it.
 */
export default function PaChat() {
  const { pa, aiEnabled, openComposer, refreshCounts, bumpMail, notify, composer } = useMail()
  const navigate = useNavigate()
  const location = useLocation()
  const [open, setOpen] = useState(false)
  const [msgs, setMsgs] = useState(load)
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const [rec, setRec] = useState(null) // { stop } while recording
  const [hearing, setHearing] = useState(false) // turning speech into text
  const [speak, setSpeak] = useState(readSpeak)
  const end = useRef(null)
  const input = useRef(null)
  const locRef = useRef(location)
  locRef.current = location

  useEffect(() => { keep(msgs) }, [msgs])
  useEffect(() => { end.current?.scrollIntoView({ block: 'end' }) }, [msgs, busy, open])
  useEffect(() => { if (open) setTimeout(() => input.current?.focus(), 60) }, [open])
  useEffect(() => { try { localStorage.setItem(SPEAK, speak ? '1' : '0') } catch { /* fine */ } }, [speak])

  // Closing folds the panel down like a laptop lid, then takes it away.
  const [closing, setClosing] = useState(false)
  const closeTimer = useRef(null)
  const close = useCallback(() => {
    if (closeTimer.current) return
    setClosing(true)
    closeTimer.current = setTimeout(() => {
      closeTimer.current = null
      setClosing(false)
      setOpen(false)
    }, 280)
  }, [])
  useEffect(() => () => clearTimeout(closeTimer.current), [])

  useEffect(() => {
    const key = (e) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'j') {
        e.preventDefault()
        if (open) close()
        else setOpen(true)
      } else if (e.key === 'Escape' && open && !composer) {
        close()
      }
    }
    window.addEventListener('keydown', key)
    return () => window.removeEventListener('keydown', key)
  }, [open, composer, close])

  const goMail = useCallback((params) => {
    const here = locRef.current
    const p = here.pathname === '/' ? new URLSearchParams(here.search) : new URLSearchParams({ view: 'all' })
    Object.entries(params).forEach(([k, v]) => p.set(k, String(v)))
    navigate(`/?${p}`)
  }, [navigate])

  const openMail = useCallback((id) => {
    goMail({ open: id })
    if (window.matchMedia('(max-width: 767px)').matches) close()
  }, [goMail, close])

  /** Carries out one action from PA the same way the page's own buttons do. Returns a line for the chat. */
  const run = useCallback(async (a) => {
    switch (a.type) {
      case 'go':
        navigate(MAIL_VIEWS.has(a.to) ? `/?view=${a.to}` : `/${a.to}`)
        return { text: `Opened ${PLACE_LABEL[a.to] || a.to}` }
      case 'search':
        if (locRef.current.pathname !== '/') navigate('/?view=all')
        setTimeout(() => emit(PA_SEARCH, { q: a.q }), 80)
        return { text: `Searching mail for “${a.q}”` }
      case 'open':
        goMail({ open: a.messageId })
        return { text: `Opened “${a.subject || '(no subject)'}” from ${a.from}`, openId: a.messageId }
      case 'mail': {
        const id = a.messageId
        const calls = {
          archive: () => post(`/api/messages/${id}/archive`),
          trash: () => post(`/api/messages/${id}/trash`),
          spam: () => post(`/api/messages/${id}/move`, { folder: 'SPAM' }),
          flag: () => post(`/api/messages/${id}/flag`, { flagged: true }),
          unflag: () => post(`/api/messages/${id}/flag`, { flagged: false }),
          read: () => post(`/api/messages/${id}/seen`, { seen: true }),
          unread: () => post(`/api/messages/${id}/seen`, { seen: false }),
          snooze: () => post(`/api/messages/${id}/snooze`, { at: a.at }),
          lane: () => post(`/api/messages/${id}/lane`, { lane: a.lane, rememberSender: false }),
        }
        await calls[a.act]()
        const gone = ['archive', 'trash', 'spam', 'snooze'].includes(a.act)
        const patch = { flag: { flagged: true }, unflag: { flagged: false }, read: { seen: true }, unread: { seen: false } }[a.act]
        emit(PA_DONE, { id, gone, patch })
        if (a.act === 'lane') bumpMail()
        refreshCounts()
        const when = a.act === 'snooze' ? ` until ${new Date(a.at).toLocaleString([], { weekday: 'short', day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' })}` : ''
        const where = a.act === 'lane' ? ` to ${LANE_LABEL[a.lane]}` : ''
        const undo = gone ? { kind: a.act, id } : null
        return { text: `${ACT_LABEL[a.act]}${where}${when}: “${a.subject || '(no subject)'}”`, undo }
      }
      case 'compose':
        openComposer({ mode: 'new', to: a.to, cc: a.cc, subject: a.subject || '', draftBody: a.body || '' })
        return { text: 'Draft ready. Check it and press Send.' }
      case 'reply':
      case 'forward': {
        const detail = await get(`/api/messages/${a.messageId}`)
        if (a.type === 'forward') openComposer({ mode: 'forward', message: detail, to: a.to, draftBody: a.body ? `${a.body}\n` : '' })
        else openComposer({ mode: a.all ? 'replyAll' : 'reply', message: detail, draftBody: a.body || '' })
        return { text: `${a.type === 'forward' ? 'Forward' : 'Reply'} to “${a.subject || '(no subject)'}” ready. Check it and press Send.` }
      }
      case 'ask': {
        const res = await get(`/api/ai/ask${qs({ q: a.q, tz: Intl.DateTimeFormat().resolvedOptions().timeZone })}`)
        const byId = new Map((res.items || []).map((x) => [x.id, x]))
        const ids = res.sources?.length ? res.sources : (res.items || []).slice(0, 3).map((x) => x.id)
        return {
          answer: res.answer || (res.items?.length ? 'Here’s what I found.' : 'Nothing in your mail matches that.'),
          sources: ids.map((i) => byId.get(i)).filter(Boolean).map((x) => ({ id: x.id, subject: x.subject || '(no subject)', from: x.fromName || x.fromEmail })),
        }
      }
      default:
        return null
    }
  }, [navigate, goMail, openComposer, refreshCounts, bumpMail])

  async function undoStep(msgIndex, stepIndex, d) {
    const u = d.undo
    try {
      if (u.kind === 'snooze') await post(`/api/messages/${u.id}/snooze`, { at: null })
      else await post(`/api/messages/${u.id}/move`, { folder: 'INBOX' })
      bumpMail()
      refreshCounts()
      setMsgs((all) => all.map((m, i) => i !== msgIndex ? m : { ...m, done: m.done.map((x, j) => j === stepIndex ? { ...x, undone: true } : x) }))
    } catch (e) {
      notify(e.message, 'error')
    }
  }

  async function decide(msgIndex, actionIndex, answer) {
    const a = msgs[msgIndex].pa[actionIndex]
    const set = (patch) => setMsgs((all) => all.map((m, i) => i !== msgIndex ? m : { ...m, pa: m.pa.map((x, j) => j === actionIndex ? { ...x, ...patch } : x) }))
    set({ busy: true })
    try {
      await post(`/api/assistant/decisions/${a.decisionId}/${answer}`)
      set({ busy: false, answer })
      if (answer === 'approve') bumpMail()
    } catch (e) {
      set({ busy: false })
      notify(e.message, 'error')
    }
  }

  async function undoPa(msgIndex, actionIndex) {
    const a = msgs[msgIndex].pa[actionIndex]
    try {
      await post(`/api/assistant/activity/${a.activityId}/undo`)
      setMsgs((all) => all.map((m, i) => i !== msgIndex ? m : { ...m, pa: m.pa.map((x, j) => j === actionIndex ? { ...x, undone: true } : x) }))
    } catch (e) {
      notify(e.message, 'error')
    }
  }

  async function send(raw, voice = false) {
    const message = (raw ?? text).trim()
    if (!message || busy) return
    setText('')
    const history = msgs.filter((m) => m.content && !m.error).slice(-12).map((m) => ({ role: m.role, content: m.content }))
    setMsgs((all) => [...all, { role: 'user', content: message, voice }])
    setBusy(true)
    try {
      const s = screen()
      const page = location.pathname === '/' ? 'mail' : location.pathname.slice(1)
      const res = await post('/api/assistant/chat', {
        message, history, voice,
        context: { page, view: page === 'mail' ? s.view : PLACE_LABEL[page] || page, openMessageId: page === 'mail' ? s.openMessageId : null,
          listIds: page === 'mail' ? s.listIds || [] : [] },
        tz: Intl.DateTimeFormat().resolvedOptions().timeZone,
      })
      const done = []
      const sources = []
      let reply = res.reply || ''
      for (const a of res.actions || []) {
        try {
          const r = await run(a)
          // Let the page show what was just opened before the next step acts on it.
          if (a.type === 'open' || a.type === 'go') await new Promise((ok) => setTimeout(ok, 250))
          if (r?.answer) {
            reply = reply ? `${reply}\n\n${r.answer}` : r.answer
            sources.push(...(r.sources || []))
          } else if (r) done.push(r)
        } catch (e) {
          done.push({ text: e.message, failed: true })
        }
      }
      setMsgs((all) => [...all, { role: 'assistant', content: reply, done, sources, pa: res.paActions || [], by: res.by }])
      if ((speak || voice) && reply) say(reply)
    } catch (e) {
      setMsgs((all) => [...all, { role: 'assistant', content: e.message, error: true }])
    } finally {
      setBusy(false)
    }
  }

  async function toggleMic() {
    if (rec) {
      rec.stop()
      return
    }
    if (!navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === 'undefined') {
      notify('This browser can’t record. Type instead.', 'error')
      return
    }
    let stream
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: true })
    } catch {
      notify('MailLook needs the microphone to hear you. Allow it in the browser and try again.', 'error')
      return
    }
    const type = ['audio/webm;codecs=opus', 'audio/webm', 'audio/ogg', 'audio/mp4'].find((t) => MediaRecorder.isTypeSupported?.(t)) || ''
    const mr = new MediaRecorder(stream, type ? { mimeType: type } : undefined)
    const parts = []
    mr.ondataavailable = (e) => e.data.size && parts.push(e.data)
    const limit = setTimeout(() => mr.state === 'recording' && mr.stop(), 45000)
    mr.onstop = async () => {
      clearTimeout(limit)
      stream.getTracks().forEach((t) => t.stop())
      setRec(null)
      const blob = new Blob(parts, { type: mr.mimeType || type || 'audio/webm' })
      if (blob.size < 1200) return
      setHearing(true)
      try {
        const res = await post('/api/assistant/transcribe', { audio: await toBase64(blob), mimeType: blob.type })
        const heard = (res.text || '').trim()
        if (heard) await send(heard, true)
        else notify('I didn’t catch that. Try again a little closer to the mic.', 'info')
      } catch (e) {
        notify(e.message, 'error')
      } finally {
        setHearing(false)
      }
    }
    mr.start()
    if ('speechSynthesis' in window) window.speechSynthesis.cancel()
    setRec({ stop: () => mr.state === 'recording' && mr.stop() })
  }

  function clear() {
    setMsgs([])
    if ('speechSynthesis' in window) window.speechSynthesis.cancel()
  }

  const ready = aiEnabled !== false
  const state = rec ? 'listening' : busy || hearing ? 'thinking' : 'idle'

  if (!open) {
    return (
      <button className="pa-launch fixed bottom-24 right-4 z-40 flex items-center gap-2 rounded-full py-1.5 pl-1.5 pr-4 lg:bottom-5 lg:right-5"
        onClick={() => setOpen(true)} title="Ask Magizhchi PA (Ctrl+J)" aria-label="Open Magizhchi PA chat">
        <Orb size={34} state={state} />
        <span className="text-sm font-bold">Ask PA</span>
      </button>
    )
  }

  return (
    <aside className={`pa-panel ${closing ? 'pa-lid-close' : 'pa-lid-open'} fixed inset-0 z-40 flex flex-col sm:inset-y-2.5 sm:left-auto sm:right-2.5 sm:w-[420px] sm:rounded-2xl`}
      role="dialog" aria-label="Magizhchi PA">
      <header className="pa-head flex items-center gap-3 px-4 py-3">
        <Orb size={38} state={state} />
        <div className="min-w-0 flex-1">
          <h2 className="text-[15px] font-bold leading-tight">Magizhchi PA</h2>
          <p className="truncate text-[11px] opacity-75">
            {rec ? 'Listening… tap the mic when you’re done' : hearing ? 'Working out what you said…' : busy ? 'On it…' : 'Type or speak. I can work MailLook for you.'}
          </p>
        </div>
        <button className={`pa-chip ${speak ? 'on' : ''}`} onClick={() => setSpeak((s) => !s)} title={speak ? 'Replies are read aloud' : 'Read replies aloud'}>
          {speak ? 'Voice on' : 'Voice off'}
        </button>
        {msgs.length > 0 && <button className="pa-chip" onClick={clear} title="Start a new chat">New</button>}
        <button className="icon-btn !h-8 !w-8" onClick={close} aria-label="Close"><IconX size={16} /></button>
      </header>

      <div className="scroll-thin min-h-0 flex-1 space-y-3 overflow-y-auto px-4 py-4">
        {!ready && (
          <div className="pa-card rounded-xl p-3 text-sm">
            {pa?.message || 'Magizhchi PA’s AI isn’t on for you yet. Sign in with Magizhchi ID and switch on AI in Magizhchi PA.'}
          </div>
        )}
        {msgs.length === 0 && ready && (
          <div className="pt-6 text-center">
            <Orb size={64} />
            <p className="mt-3 text-base font-bold text-brand-ink">Vanakkam! What shall we do?</p>
            <p className="mx-auto mt-1 max-w-[18rem] text-xs text-slate-500">
              Open lanes, find a mail, archive or snooze it, write a reply, or ask about your calendar and tasks. Tamil or English.
            </p>
            <div className="mt-4 flex flex-wrap justify-center gap-1.5">
              {IDEAS.map((i) => <button key={i} className="pa-idea" onClick={() => send(i)}>{i}</button>)}
            </div>
          </div>
        )}
        {msgs.map((m, i) => (
          <Bubble key={i} m={m}
            onRun={(d) => undoStep(i, m.done.indexOf(d), d)}
            onOpenMail={openMail}
            onDecide={(mm, j, ans) => decide(i, j, ans)}
            onUndo={(mm, j) => undoPa(i, j)} />
        ))}
        {(busy || hearing) && (
          <div className="flex items-center gap-2">
            <Orb size={26} state="thinking" />
            <span className="pa-dots" aria-label="Thinking"><i /><i /><i /></span>
          </div>
        )}
        <div ref={end} />
      </div>

      <form className="pa-foot flex items-end gap-2 px-3 py-3" onSubmit={(e) => { e.preventDefault(); send() }}>
        <button type="button" className={`pa-mic ${rec ? 'on' : ''}`} onClick={toggleMic} disabled={!ready || hearing || busy}
          aria-label={rec ? 'Stop and send' : 'Speak'} title={rec ? 'Stop and send' : 'Speak (Tamil or English)'}>
          {hearing ? <span className="spinner" /> : <IconMic size={18} />}
        </button>
        <textarea ref={input} className="pa-input min-h-[42px] flex-1 resize-none" rows={1} value={text} disabled={!ready}
          placeholder={rec ? 'Listening…' : 'Ask PA or tell it what to do…'} maxLength={2000}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send() } }} />
        <button className="pa-send" disabled={!text.trim() || busy || !ready} aria-label="Send"><IconSend size={17} /></button>
      </form>
    </aside>
  )
}
