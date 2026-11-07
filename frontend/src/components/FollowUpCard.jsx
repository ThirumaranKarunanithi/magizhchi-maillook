import { useState } from 'react'
import { post } from '../api.js'
import { useMail } from '../state.jsx'
import { FOLLOW_UP, followUpAt, whenLabel } from '../when.js'
import { IconBell } from './Icons.jsx'

/** On mail you sent: "remind me if nobody replies by …", or what it's set to. */
export default function FollowUpCard({ message, onChange }) {
  const { notify } = useMail()
  const [at, setAt] = useState(message.followUpAt || null)
  const [busy, setBusy] = useState(false)
  if (message.folder !== 'SENT') return null

  async function set(key) {
    const when = key ? followUpAt(key) : null
    setBusy(true)
    try {
      const res = await post(`/api/messages/${message.id}/followup`, { at: when ? when.toISOString() : null })
      setAt(res?.followUpAt || null)
      onChange?.(res?.followUpAt || null)
      notify(res?.followUpAt ? `If nobody replies, it comes back ${whenLabel(res.followUpAt)}` : 'Reminder off', 'success')
    } catch (e) {
      notify(e.message, 'error')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex flex-wrap items-center gap-2 rounded-xl border border-slate-200 bg-white/70 px-3 py-2 text-xs">
      <IconBell size={15} className={at ? 'text-brand-primary' : 'text-slate-400'} />
      {message.backReason === 'no_reply' && !at ? (
        <span className="font-semibold text-amber-800">Nobody has replied yet.</span>
      ) : at ? (
        <span className="text-slate-700">If nobody replies, this comes back to <b>Needs me</b> {whenLabel(at)}.</span>
      ) : (
        <span className="text-slate-600">Remind me if nobody replies</span>
      )}
      <select className="ml-auto rounded-lg border border-slate-200 bg-transparent px-1.5 py-1 text-xs outline-none" disabled={busy}
        value="" onChange={(e) => set(e.target.value === 'off' ? '' : e.target.value)} aria-label="Remind me if nobody replies">
        <option value="" disabled>{at ? 'Change…' : 'Choose…'}</option>
        {FOLLOW_UP.filter((f) => f.key).map((f) => <option key={f.key} value={f.key}>{f.label}</option>)}
        {at && <option value="off">No reminder</option>}
      </select>
    </div>
  )
}
