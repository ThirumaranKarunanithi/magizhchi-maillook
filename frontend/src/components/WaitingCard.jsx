import { useState } from 'react'
import { post } from '../api.js'
import { useMail } from '../state.jsx'
import { IconClock, IconSparkle } from './Icons.jsx'

/**
 * On mail you sent that nobody has answered: how long it's been, a kind follow-up ready to send,
 * or "I'm not waiting on this".
 */
export default function WaitingCard({ message, onDismissed }) {
  const { openComposer, notify, aiEnabled, refreshCounts } = useMail()
  const [busy, setBusy] = useState(null)
  const [gone, setGone] = useState(false)
  if (message.folder !== 'SENT' || !message.waitingDays || gone) return null

  async function nudge() {
    setBusy('nudge')
    try {
      const res = await post(`/api/messages/${message.id}/nudge`)
      openComposer({ mode: 'replyAll', message, draftBody: res.body })
    } catch (e) {
      notify(e.message, 'error')
    } finally {
      setBusy(null)
    }
  }

  async function dismiss() {
    setBusy('off')
    try {
      await post(`/api/messages/${message.id}/waiting/off`)
      setGone(true)
      notify('Okay, not waiting on that one.', 'info')
      refreshCounts()
      onDismissed?.(message.id)
    } catch (e) {
      notify(e.message, 'error')
    } finally {
      setBusy(null)
    }
  }

  return (
    <section className="flex flex-wrap items-center gap-2 rounded-xl border border-peacock-200 bg-peacock-50/70 px-3 py-2 text-xs" aria-label="Waiting for a reply">
      <IconClock size={15} className="text-peacock-700" />
      <span className="min-w-0 flex-1 text-brand-ink">
        <b>{message.waitingDays} days</b> and no reply{message.waitingFor ? <> · waiting for {message.waitingFor}</> : ''}.
      </span>
      <button className="btn-primary !px-2.5 !py-1 text-xs" onClick={nudge} disabled={!!busy}>
        {busy === 'nudge' ? <span className="spinner" /> : aiEnabled && <IconSparkle size={13} />} Follow up
      </button>
      <button className="btn-ghost !px-2 !py-1 text-xs" onClick={dismiss} disabled={!!busy}>Not waiting</button>
    </section>
  )
}
