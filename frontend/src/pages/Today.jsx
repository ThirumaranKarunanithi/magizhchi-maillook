import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { get, qs } from '../api.js'
import { useMail } from '../state.jsx'
import { safeHttpUrl, senderName } from '../util.js'
import { IconCalendar, IconCheck, IconClock, IconHand, IconMenu, IconRefresh, IconSnooze, IconSparkle } from '../components/Icons.jsx'

const TZ = (() => {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || ''
  } catch {
    return ''
  }
})()

const time = (iso) => new Date(iso).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })

function ago(iso) {
  const days = Math.floor((Date.now() - new Date(iso).getTime()) / 86400000)
  if (days <= 0) return 'today'
  if (days === 1) return 'yesterday'
  return `${days} days ago`
}

function Stat({ n, label, Icon, tone }) {
  return (
    <div className="flex items-center gap-2.5 rounded-xl border border-slate-200/80 bg-white/70 px-3 py-2">
      <span className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-lg ${tone}`}><Icon size={16} /></span>
      <span className="min-w-0">
        <span className="block text-lg font-bold leading-none text-brand-ink num">{n}</span>
        <span className="block truncate text-[11px] text-slate-500">{label}</span>
      </span>
    </div>
  )
}

function Section({ title, Icon, action, children }) {
  return (
    <section className="rounded-2xl border border-slate-200/80 bg-white/70 p-3 sm:p-4">
      <div className="mb-2 flex items-center gap-2">
        <Icon size={16} className="text-brand-primary" />
        <h2 className="flex-1 text-sm font-bold text-brand-ink">{title}</h2>
        {action}
      </div>
      {children}
    </section>
  )
}

/** Today: the AI overview, who is waiting on you and what each needs, today's PA calendar, and who you're waiting on. */
export default function Today() {
  const { accountId, setMenuOpen, pa } = useMail()
  const navigate = useNavigate()
  const [b, setB] = useState(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)

  const load = useCallback(async (refresh = false) => {
    setLoading(true)
    setError('')
    try {
      setB(await get(`/api/briefing${qs({ tz: TZ, accountId: accountId ?? '', refresh: refresh || '' })}`))
    } catch (e) {
      setError(e.message)
    } finally {
      setLoading(false)
    }
  }, [accountId])

  useEffect(() => { load() }, [load])

  const open = (view, id) => navigate(`/?view=${view}&open=${id}`)
  const dateLine = new Date().toLocaleDateString([], { weekday: 'long', day: 'numeric', month: 'long' })
  const timed = (b?.events || []).filter((e) => !e.allDay)
  const allDay = (b?.events || []).filter((e) => e.allDay)
  const next = timed.find((e) => new Date(e.end) > new Date())

  return (
    <section className="slab rise-3d d1 flex min-h-0 flex-1 flex-col overflow-hidden max-lg:rounded-none">
      <header className="border-b border-slate-200/70 px-3 py-2.5 sm:px-4">
        <div className="flex items-center gap-2">
          <button className="icon-btn lg:hidden" onClick={() => setMenuOpen(true)} aria-label="Open menu"><IconMenu /></button>
          <IconSparkle size={20} className="hidden text-peacock-600 sm:block" />
          <div className="min-w-0 flex-1">
            <h1 className="truncate text-base font-bold leading-tight">Today</h1>
            <p className="truncate text-[11px] text-slate-500">{dateLine}</p>
          </div>
          <button className="icon-btn" onClick={() => load(true)} disabled={loading} title="Brief me again">
            <IconRefresh size={17} className={loading ? 'animate-spin' : ''} />
          </button>
        </div>
      </header>

      <div className="scroll-thin min-h-0 flex-1 overflow-y-auto px-3 py-4 max-md:pb-24 sm:px-6">
        <div className="mx-auto max-w-3xl space-y-4">
          {error && (
            <p className="rounded-xl border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700" role="alert">
              {error} <button className="font-semibold underline" onClick={() => load()}>Try again</button>
            </p>
          )}
          {!b && loading && (
            <div className="space-y-3" aria-busy="true">
              <div className="h-28 animate-pulse rounded-2xl bg-slate-100" />
              <div className="h-40 animate-pulse rounded-2xl bg-slate-100" />
            </div>
          )}

          {b && (
            <>
              <section className="rounded-2xl border border-peacock-200 bg-gradient-to-br from-peacock-50 to-white p-4 sm:p-5">
                <p className="text-2xl font-bold tracking-tight text-brand-ink sm:text-3xl">{b.greeting}</p>
                <p className="mt-2 max-w-2xl text-[15px] leading-relaxed text-slate-800">{b.summary}</p>
                {b.byAi && <p className="mt-2 flex items-center gap-1 text-[11px] text-slate-500"><IconSparkle size={11} /> Written by AI from your mail and calendar</p>}
              </section>

              <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
                <Stat n={b.needsMeTotal} label="waiting on you" Icon={IconHand} tone="bg-amber-50 text-amber-700" />
                <Stat n={b.events.length} label={b.paConnected ? 'meetings today' : 'meetings (PA not linked)'} Icon={IconCalendar} tone="bg-emerald-50 text-emerald-700" />
                <Stat n={b.waitingTotal} label="you're waiting on" Icon={IconClock} tone="bg-peacock-50 text-peacock-700" />
                <Stat n={b.snoozedToday} label="coming back today" Icon={IconSnooze} tone="bg-slate-100 text-slate-600" />
              </div>

              <Section title="Needs you" Icon={IconHand}
                action={b.needsMeTotal > b.needsMe.length && (
                  <button className="text-xs font-semibold text-peacock-700 hover:underline" onClick={() => navigate('/?view=needs_me')}>All {b.needsMeTotal} →</button>
                )}>
                {b.needsMe.length === 0 ? (
                  <p className="flex items-center gap-2 py-1 text-sm text-slate-600"><IconCheck size={15} className="text-emerald-600" /> Nobody is waiting on you. Lovely.</p>
                ) : (
                  <ul className="-mx-1 divide-y divide-slate-200/70">
                    {b.needsMe.map((m) => (
                      <li key={m.id}>
                        <button className="flex w-full items-start gap-3 rounded-lg px-1 py-2 text-left hover:bg-slate-50" onClick={() => open('needs_me', m.id)}>
                          <span className="mt-1.5 h-2 w-2 shrink-0 rounded-full" style={{ background: m.accountColor || '#0e7c86' }} />
                          <span className="min-w-0 flex-1">
                            <span className="flex items-baseline gap-2">
                              <span className="truncate text-sm font-semibold text-brand-ink">{senderName(m)}</span>
                              <span className="shrink-0 text-[11px] text-slate-500">{m.backReason === 'snooze' ? 'back from snooze' : ago(m.receivedAt)}</span>
                            </span>
                            <span className="block truncate text-[13px] text-slate-700">{m.line || m.subject || '(no subject)'}</span>
                            {m.line && <span className="block truncate text-[11px] text-slate-500">{m.subject}</span>}
                          </span>
                        </button>
                      </li>
                    ))}
                  </ul>
                )}
              </Section>

              <Section title="Your day" Icon={IconCalendar}
                action={b.webUrl && <a className="text-xs font-semibold text-peacock-700 hover:underline" href={b.webUrl} target="_blank" rel="noreferrer">Open PA ↗</a>}>
                {!b.paConnected ? (
                  <p className="py-1 text-sm text-slate-600">
                    {pa?.connected ? 'Magizhchi PA couldn’t be reached just now.' : 'Sign in with Magizhchi ID to see your Magizhchi PA calendar here.'}
                  </p>
                ) : b.events.length === 0 && b.tasks.length === 0 ? (
                  <p className="py-1 text-sm text-slate-600">Nothing on the calendar today. Time for deep work.</p>
                ) : (
                  <ul className="space-y-1.5">
                    {allDay.map((e) => (
                      <li key={e.id} className="flex items-center gap-3 text-sm">
                        <span className="w-16 shrink-0 text-xs font-semibold text-slate-500">All day</span>
                        <span className="h-4 w-1 shrink-0 rounded-full" style={{ background: e.color || '#0e7c86' }} />
                        <span className="truncate font-medium">{e.title}</span>
                      </li>
                    ))}
                    {timed.map((e) => {
                      const over = new Date(e.end) < new Date()
                      const loc = safeHttpUrl(e.location)
                      return (
                        <li key={e.id} className={`flex items-center gap-3 text-sm ${over ? 'opacity-50' : ''}`}>
                          <span className="w-16 shrink-0 text-xs font-semibold text-slate-600 num">{time(e.start)}</span>
                          <span className="h-4 w-1 shrink-0 rounded-full" style={{ background: e.color || '#0e7c86' }} />
                          <span className="min-w-0 flex-1 truncate">
                            <span className="font-medium">{e.title}</span>
                            {e.location && !loc && <span className="text-slate-500"> · {e.location}</span>}
                          </span>
                          {next?.id === e.id && !over && <span className="shrink-0 rounded-full bg-peacock-50 px-2 py-0.5 text-[11px] font-semibold text-peacock-800">Next</span>}
                          {loc && <a href={loc} target="_blank" rel="noreferrer" className="shrink-0 text-xs font-semibold text-peacock-700 hover:underline">Join</a>}
                        </li>
                      )
                    })}
                    {b.tasks.map((t) => (
                      <li key={t.id} className="flex items-center gap-3 text-sm">
                        <span className="w-16 shrink-0 text-xs font-semibold text-slate-500 num">{time(t.dueAt)}</span>
                        <span className="flex h-4 w-4 shrink-0 items-center justify-center rounded border border-slate-300" />
                        <span className="min-w-0 truncate">{t.title} <span className="text-xs text-slate-500">· task due</span></span>
                        {t.priority === 'HIGH' && <span className="shrink-0 text-[11px] font-semibold text-red-700">High</span>}
                      </li>
                    ))}
                  </ul>
                )}
              </Section>

              <Section title="You're waiting on" Icon={IconClock}
                action={b.waitingTotal > 0 && (
                  <button className="text-xs font-semibold text-peacock-700 hover:underline" onClick={() => navigate('/?view=waiting')}>Open →</button>
                )}>
                {b.waiting.length === 0 ? (
                  <p className="py-1 text-sm text-slate-600">Nobody owes you a reply.</p>
                ) : (
                  <ul className="-mx-1 divide-y divide-slate-200/70">
                    {b.waiting.map((m) => (
                      <li key={m.id}>
                        <button className="flex w-full items-center gap-3 rounded-lg px-1 py-2 text-left hover:bg-slate-50" onClick={() => open('waiting', m.id)}>
                          <span className="min-w-0 flex-1">
                            <span className="block truncate text-sm font-medium text-brand-ink">{(m.to || []).join(', ')}</span>
                            <span className="block truncate text-xs text-slate-500">{m.waitingFor ? `Waiting for ${m.waitingFor}` : m.subject}</span>
                          </span>
                          <span className="shrink-0 rounded-md bg-peacock-50 px-1.5 py-0.5 text-[11px] font-semibold text-peacock-800 ring-1 ring-peacock-200">{m.waitingDays} days</span>
                        </button>
                      </li>
                    ))}
                  </ul>
                )}
              </Section>
            </>
          )}
        </div>
      </div>
    </section>
  )
}
