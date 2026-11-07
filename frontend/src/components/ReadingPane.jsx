import { useEffect, useRef, useState } from 'react'
import { get, post } from '../api.js'
import { useMail } from '../state.jsx'
import { formatSize, fullDate, LANE_LABEL, safeHttpUrl, senderName, shortTime } from '../util.js'
import AiCard from './AiCard.jsx'
import AttachmentShelf from './AttachmentShelf.jsx'
import LaneMenu from './LaneMenu.jsx'
import MoveMenu from './MoveMenu.jsx'
import PaMenu from './PaMenu.jsx'
import MeetMenu from './MeetMenu.jsx'
import { ClassroomCard, FindTimeCard, JoinCard } from './MeetCards.jsx'
import { DigestCard, EventCard } from './AcademyCards.jsx'
import MailFrame from './MailFrame.jsx'
import SnoozeMenu from './SnoozeMenu.jsx'
import NoteCard from './NoteCard.jsx'
import FollowUpCard from './FollowUpCard.jsx'
import PersonCard from './PersonCard.jsx'
import InviteCard from './InviteCard.jsx'
import WaitingCard from './WaitingCard.jsx'
import { QuickReplies, Translate } from './MailExtras.jsx'
import { whenLabel } from '../when.js'
import { AccountDot } from './Sidebar.jsx'
import {
  IconArchive, IconBack, IconFlag, IconForward, IconInbox, IconNote, IconReply, IconReplyAll, IconTrash, IconUnsub,
} from './Icons.jsx'

const SOURCE_LABEL = { meet: 'after your meeting', ai: 'sorted by AI', user: 'you moved it', rule: 'your sender rule', heuristic: 'sorted automatically' }

function Body({ detail }) {
  if (detail.bodyHtml) return <MailFrame html={detail.bodyHtml} title={`Message: ${detail.subject || ''}`} />
  if (detail.bodyText) return <pre className="paper whitespace-pre-wrap break-words px-[clamp(18px,4vw,40px)] pb-8 pt-7 font-sans text-[17px] leading-[1.65] sm:text-[15px]">{detail.bodyText}</pre>
  return <p className="text-sm italic text-slate-500">{detail.snippet || 'This message has no text.'}</p>
}

/** One older message in the thread: collapsed to a line, loads its body when opened. */
function ThreadItem({ summary, notify }) {
  const [open, setOpen] = useState(false)
  const [detail, setDetail] = useState(null)
  const [error, setError] = useState('')

  async function toggle() {
    const next = !open
    setOpen(next)
    if (next && !detail) {
      try {
        setDetail(await get(`/api/messages/${summary.id}`))
      } catch (e) {
        setError(e.message)
      }
    }
  }

  return (
    <div className="rounded-xl border border-slate-200 bg-white/80">
      <button className="flex w-full items-center gap-3 px-3 py-2 text-left" onClick={toggle} aria-expanded={open}>
        <span className="min-w-0 flex-1">
          <span className="block truncate text-sm font-semibold">{senderName(summary)}</span>
          {!open && <span className="block truncate text-xs text-slate-500">{summary.snippet}</span>}
        </span>
        <span className="shrink-0 text-xs text-slate-500">{shortTime(summary.receivedAt)}</span>
      </button>
      {open && (
        <div className="border-t border-slate-100 px-3 pb-3 pt-2">
          {error ? <p className="text-sm text-red-700">{error}</p> : !detail ? <span className="spinner text-slate-400" /> : (
            <>
              <p className="mb-2 text-xs text-slate-500">
                To {(detail.to || []).join(', ')}{detail.cc?.length ? ` · Cc ${detail.cc.join(', ')}` : ''} · {fullDate(detail.receivedAt)}
              </p>
              <Body detail={detail} />
              <AttachmentShelf compact messageId={detail.id} attachments={detail.attachments} notify={notify} />
            </>
          )}
        </div>
      )}
    </div>
  )
}

export default function ReadingPane({ id, onBack, onSeen, onFlag, onArchive, onTrash, onMoveLane, onMove, onSnooze, onNote }) {
  const { openComposer, notify } = useMail()
  const [detail, setDetail] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [unsub, setUnsub] = useState(null) // { busy } | result
  const seenSent = useRef(new Set())
  const [meetKey, setMeetKey] = useState(0)
  const [noteOpen, setNoteOpen] = useState(false)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError('')
    setDetail(null)
    setUnsub(null)
    get(`/api/messages/${id}`)
      .then((d) => {
        if (cancelled) return
        setDetail(d)
        if (!d.seen && !seenSent.current.has(d.id)) {
          seenSent.current.add(d.id)
          onSeen(d.id)
          setDetail((x) => (x && x.id === d.id ? { ...x, seen: true } : x))
        }
      })
      .catch((e) => !cancelled && setError(e.message))
      .finally(() => !cancelled && setLoading(false))
    return () => { cancelled = true }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id])

  if (loading) {
    return (
      <div className="flex flex-1 items-center justify-center text-slate-400">
        <span className="spinner !h-6 !w-6" />
      </div>
    )
  }
  if (error || !detail) {
    return (
      <div className="flex flex-1 flex-col items-center justify-center gap-3 p-6 text-center text-sm">
        <p className="text-red-700">{error || 'This message could not be opened.'}</p>
        {onBack && <button className="btn-secondary md:hidden" onClick={onBack}>Back to the list</button>}
      </div>
    )
  }

  const rawThread = Array.isArray(detail.thread) ? detail.thread : []
  const thread = rawThread.some((t) => t.id === detail.id) ? rawThread : [...rawThread, detail]
  const others = thread.filter((t) => t.id !== detail.id)

  async function toggleFlag() {
    const flagged = !detail.flagged
    setDetail({ ...detail, flagged })
    try {
      await onFlag(detail.id, flagged)
    } catch {
      setDetail((d) => ({ ...d, flagged: !flagged }))
    }
  }

  async function moveLane(lane, remember) {
    const ok = await onMoveLane(detail.id, lane, remember)
    if (ok !== false) setDetail((d) => ({ ...d, lane, laneSource: 'user' }))
  }

  async function unsubscribe() {
    setUnsub({ busy: true })
    try {
      const res = await post(`/api/messages/${detail.id}/unsubscribe`)
      if (res?.status === 'open_url') {
        const url = safeHttpUrl(res.url)
        if (url) window.open(url, '_blank', 'noopener,noreferrer')
        setUnsub({ ...res, url })
      } else {
        setUnsub(res || { status: 'done', message: 'Unsubscribed.' })
        notify(res?.message || 'Unsubscribed. You will hear less from them.', 'success')
      }
    } catch (e) {
      setUnsub({ status: 'error', message: e.message })
    }
  }

  return (
    <div className="scroll-thin flex min-h-0 flex-1 flex-col overflow-y-auto">
      {/* toolbar */}
      <div className="sticky top-0 z-20 flex flex-wrap items-center gap-0.5 border-b border-slate-200/70 bg-white/85 px-2 py-1.5 backdrop-blur-xl">
        {onBack && (
          <button className="icon-btn md:hidden" onClick={onBack} aria-label="Back to the list"><IconBack /></button>
        )}
        <button className="btn-ghost" onClick={() => openComposer({ mode: 'reply', message: detail })} title="Reply">
          <IconReply size={17} /><span className="hidden sm:inline">Reply</span>
        </button>
        <button className="btn-ghost" onClick={() => openComposer({ mode: 'replyAll', message: detail })} title="Reply all">
          <IconReplyAll size={17} /><span className="hidden sm:inline">Reply all</span>
        </button>
        <button className="btn-ghost" onClick={() => openComposer({ mode: 'forward', message: detail })} title="Forward">
          <IconForward size={17} /><span className="hidden sm:inline">Forward</span>
        </button>
        <span className="mx-1 h-5 w-px bg-slate-200" />
        {(detail.folder === 'SPAM' || detail.folder === 'TRASH') && (
          <button className="btn-secondary !py-1" onClick={() => onMove(detail.id, 'INBOX', 'Inbox')}
            title={detail.folder === 'SPAM' ? 'Not spam: move it to the Inbox' : 'Put it back in the Inbox'}>
            <IconInbox size={16} />{detail.folder === 'SPAM' ? 'Not spam' : 'Restore'}
          </button>
        )}
        <button className="btn-ghost" onClick={() => onArchive(detail.id)} title="Done: archive it">
          <IconArchive size={17} /><span className="hidden xl:inline">Done</span>
        </button>
        <button className="btn-ghost hover:!text-red-700" onClick={() => onTrash(detail.id)} title="Move to Trash">
          <IconTrash size={17} /><span className="hidden xl:inline">Trash</span>
        </button>
        <button className={`btn-ghost ${detail.flagged ? '!text-red-600' : ''}`} onClick={toggleFlag} title={detail.flagged ? 'Unflag' : 'Flag'}>
          <IconFlag filled={detail.flagged} size={17} /><span className="hidden xl:inline">{detail.flagged ? 'Flagged' : 'Flag'}</span>
        </button>
        {detail.folder === 'INBOX' && onSnooze && <SnoozeMenu message={detail} onSnooze={onSnooze} />}
        {detail.folder === 'INBOX' && <LaneMenu currentLane={detail.lane} onMove={moveLane} sender={detail.fromEmail} />}
        {onMove && detail.folder !== 'SENT' && <MoveMenu message={detail} onMove={onMove} />}
        <button className={`btn-ghost ${noteOpen || detail.note?.body ? '!text-amber-700' : ''}`} onClick={() => setNoteOpen(true)} title="A private note on this conversation">
          <IconNote size={17} /><span className="hidden xl:inline">Note</span>
        </button>
        <PaMenu message={detail} />
        <MeetMenu message={detail} onStarted={() => setMeetKey((k) => k + 1)} />
        {detail.canUnsubscribe && (
          <button className="btn-ghost" onClick={unsubscribe} disabled={unsub?.busy} title="Unsubscribe from this sender">
            {unsub?.busy ? <span className="spinner" /> : <IconUnsub size={17} />}<span className="hidden sm:inline">Unsubscribe</span>
          </button>
        )}
      </div>

      <div className="mx-auto w-full max-w-4xl space-y-4 px-4 py-4 sm:px-6">
        {unsub && !unsub.busy && (
          <div className={`rounded-lg border px-3 py-2 text-sm ${unsub.status === 'error' ? 'border-red-200 bg-red-50 text-red-800' : 'border-emerald-200 bg-emerald-50 text-emerald-800'}`}>
            {unsub.message || (unsub.status === 'open_url' ? 'Finish unsubscribing in the page that just opened.' : 'Unsubscribed.')}
            {unsub.status === 'open_url' && unsub.url && (
              <> {' '}<a className="font-semibold underline" href={unsub.url} target="_blank" rel="noopener noreferrer">Open the unsubscribe page</a></>
            )}
          </div>
        )}

        {/* header */}
        <header>
          <h1 className="mail-title text-2xl font-extrabold leading-snug tracking-tight text-brand-ink">{detail.subject || '(no subject)'}</h1>
          <div className="mt-2 flex flex-wrap items-center gap-2 text-xs">
            <span className="inline-flex items-center gap-1.5 rounded-full border border-slate-200 bg-white px-2 py-0.5 font-medium text-slate-700">
              <AccountDot color={detail.accountColor} size={8} /> {detail.accountEmail}
            </span>
            {detail.backReason === 'snooze' && <span className="chip !bg-amber-50 !text-amber-800 !ring-amber-200">Back from snooze</span>}
            {detail.snoozedUntil && <span className="chip !bg-amber-50 !text-amber-800 !ring-amber-200">Snoozed until {whenLabel(detail.snoozedUntil)}</span>}
            {detail.lane && (
              <span className="chip" title={SOURCE_LABEL[detail.laneSource] || ''}>
                {LANE_LABEL[detail.lane] || detail.lane}{detail.laneSource && <span className="font-normal text-peacock-600"> · {SOURCE_LABEL[detail.laneSource] || detail.laneSource}</span>}
              </span>
            )}
          </div>
          <div className="mt-3 text-sm">
            <div>
              <span className="font-semibold text-brand-ink">{detail.fromName || detail.fromEmail}</span>
              {detail.fromName && <span className="text-slate-500"> &lt;{detail.fromEmail}&gt;</span>}
            </div>
            <div className="mt-0.5 text-xs text-slate-500">
              <span>To {(detail.to || []).join(', ') || '(no recipients)'}</span>
              {detail.cc?.length > 0 && <span> · Cc {detail.cc.join(', ')}</span>}
            </div>
            <div className="mt-0.5 text-xs text-slate-500">{fullDate(detail.receivedAt)}</div>
          </div>
        </header>

        <InviteCard key={`inv-${detail.id}`} message={detail} />
        {detail.folder !== 'SENT' && <PersonCard email={detail.fromEmail} name={detail.fromName} />}
        <WaitingCard key={`wt-${detail.id}`} message={detail} />
        <FollowUpCard message={detail} />
        <NoteCard message={detail} open={noteOpen} onClose={() => setNoteOpen(false)}
          onChange={(has) => { setDetail((d) => ({ ...d, note: has ? { ...(d.note || {}), body: 'x' } : null })); onNote?.(detail.id, has) }} />

        <JoinCard message={detail} refreshKey={meetKey} onStarted={() => setMeetKey((k) => k + 1)} />

        <FindTimeCard key={`ft-${detail.id}`} message={detail} onBooked={() => setMeetKey((k) => k + 1)} />
        <ClassroomCard key={`cls-${detail.id}`} message={detail} onDone={() => setMeetKey((k) => k + 1)} />
        <EventCard key={`ev-${detail.id}`} message={detail} onBooked={() => setMeetKey((k) => k + 1)} />
        <DigestCard key={`dg-${detail.id}`} message={detail} />

        <AttachmentShelf messageId={detail.id} attachments={detail.attachments} notify={notify} />

        <AiCard key={detail.id} messageId={detail.id}
          onDraft={(body) => openComposer({ mode: 'reply', message: detail, draftBody: body })} />

        {/* the thread, oldest first; older messages collapsed */}
        {thread.length > 1 && (
          <p className="text-xs font-semibold uppercase tracking-wider text-slate-500">{thread.length} messages in this conversation</p>
        )}
        <div className="space-y-2">
          {thread.map((t) =>
            t.id === detail.id ? (
              <article key={t.id} className={others.length ? 'rounded-xl border border-amber-200 bg-white p-3' : ''}>
                {others.length > 0 && (
                  <div className="mb-2 flex items-center justify-between text-xs text-slate-500">
                    <span className="font-semibold text-brand-ink">{senderName(detail)}</span>
                    <span>{shortTime(detail.receivedAt)}</span>
                  </div>
                )}
                <Translate detail={detail}><Body detail={detail} /></Translate>
              </article>
            ) : (
              <ThreadItem key={t.id} summary={t} notify={notify} />
            ),
          )}
        </div>
        <QuickReplies key={`qr-${detail.id}`} detail={detail} />
      </div>
    </div>
  )
}
