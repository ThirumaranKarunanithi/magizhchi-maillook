// What the desktop app does with the alerts the server records (GET /api/alerts): which ones ring,
// which stay on screen, which sound plays, and when a pile of them becomes one "while you were away".
// No Electron here, so it can be tested with node --test.

const MAIL_SOUNDS = ['chime', 'soft', 'bright']

function defaultAlertSettings() {
  return { sound: true, volume: 0.7, mailSound: 'chime' }
}

function cleanAlertSettings(s) {
  const d = defaultAlertSettings()
  const v = Number(s && s.volume)
  return {
    sound: s && typeof s.sound === 'boolean' ? s.sound : d.sound,
    volume: Number.isFinite(v) ? Math.min(1, Math.max(0.05, v)) : d.volume,
    mailSound: s && MAIL_SOUNDS.includes(s.mailSound) ? s.mailSound : d.mailSound,
  }
}

/** A reminder (snooze, no reply, briefing) or a send that failed stays until dismissed. */
function sticky(kind) {
  return kind === 'reminder' || kind === 'problem'
}

/** The sound for an alert of this kind, or null for silence. */
function soundFor(kind, s) {
  if (!s.sound) return null
  if (kind === 'reminder' || kind === 'problem') return 'reminder'
  return s.mailSound
}

function plural(n, one, many) {
  return `${n} ${n === 1 ? one : many}`
}

/**
 * What to show for the alerts one poll brought.
 * @param items   alerts, oldest first: { id, kind, title, body, url }
 * @param opts    { first: true on the first poll after the app started, focused: the window is in front }
 * @returns       list of { kind, title, body, url, sticky } to show, newest last
 */
function plan(items, { first = false, focused = false } = {}) {
  const list = (items || []).filter((a) => a && a.title)
  if (!list.length) return []
  const mail = list.filter((a) => a.kind === 'mail')
  const others = list.filter((a) => a.kind !== 'mail')

  // Back after being off or asleep: one summary instead of a burst.
  if (first && list.length > 3) {
    const reminders = list.filter((a) => a.kind === 'reminder').length
    const problems = list.filter((a) => a.kind === 'problem').length
    const parts = []
    if (mail.length) parts.push(plural(mail.length, 'new mail alert', 'new mail alerts'))
    if (reminders) parts.push(plural(reminders, 'reminder', 'reminders'))
    if (problems) parts.push(plural(problems, 'mail that didn’t send', 'mails that didn’t send'))
    const latest = list[list.length - 1]
    return [{
      kind: reminders || problems ? 'reminder' : 'mail',
      title: 'While you were away',
      body: `${parts.join(', ')}. Latest: ${latest.title}`,
      url: problems ? '/scheduled' : reminders ? '/?view=needs_me' : '/?view=needs_me',
      sticky: !!(reminders || problems),
    }]
  }

  const out = []
  // The person is looking at MailLook: new mail shows up in the list by itself, so it doesn't pop up.
  if (!focused) {
    if (mail.length > 3) {
      out.push({ kind: 'mail', title: `${mail.length} new mails`, body: mail.slice(-3).map((a) => a.title).join(' · '), url: '/?view=needs_me', sticky: false })
    } else {
      for (const a of mail) out.push({ kind: 'mail', title: a.title, body: a.body || '', url: a.url || '/', sticky: false })
    }
  }
  for (const a of others) {
    out.push({ kind: a.kind === 'test' ? 'mail' : a.kind, title: a.title, body: a.body || '', url: a.url || '/', sticky: sticky(a.kind) })
  }
  return out
}

module.exports = { MAIL_SOUNDS, defaultAlertSettings, cleanAlertSettings, sticky, soundFor, plan }
