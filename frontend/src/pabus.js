// What's on screen right now, for Magizhchi PA's chat ("archive this", "reply to it"), and the
// events the chat sends back to the mail page after it acts.

const now = { page: 'mail', view: null, openMessageId: null }

/** The mail page tells the chat what it shows. */
export function setScreen(patch) {
  Object.assign(now, patch)
}

export function screen() {
  return { ...now }
}

export const PA_DONE = 'mlook:pa-done' // detail: { id, gone, patch }
export const PA_SEARCH = 'mlook:pa-search' // detail: { q }

export function emit(name, detail) {
  window.dispatchEvent(new CustomEvent(name, { detail }))
}
