// A copy of the mail being written, kept in this browser on every keystroke. Drafts are saved to the
// server a moment after typing stops; this copy covers the gap (a crash, a closed tab, no connection)
// and is offered back the next time MailLook opens.

const KEY = 'mlook_compose_backup'

export function writeBackup(data) {
  try {
    localStorage.setItem(KEY, JSON.stringify({ ...data, at: Date.now() }))
  } catch {
    /* storage full or blocked: the server draft still works */
  }
}

/** Marks the copy as safely on the server (or the mail as sent), so it isn't offered back. */
export function clearBackup() {
  try {
    localStorage.removeItem(KEY)
  } catch {
    /* nothing to clear */
  }
}

/** The copy, when it holds words that may not have reached the server; older than two weeks is let go. */
export function readBackup() {
  try {
    const b = JSON.parse(localStorage.getItem(KEY) || 'null')
    if (!b || !b.at || Date.now() - b.at > 14 * 86400000) return null
    const words = `${b.subject || ''}${b.body || ''}`.trim()
    return words ? b : null
  } catch {
    return null
  }
}
