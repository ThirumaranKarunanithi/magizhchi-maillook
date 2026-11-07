// Where a link opens: in the MailLook window, or in the person's own browser.
//
// The window is MailLook and the pages it needs to sign in (Magizhchi ID, and Google or Microsoft when a
// mailbox is connected). Everything else (a link in a mail, a Meet room, a Box file, an unsubscribe page)
// goes to the default browser, where the person's other sign-ins and tabs are.

const SIGN_IN_HOSTS = [
  'accounts.magizhchi.software',      // Magizhchi ID
  'accounts.google.com',               // "Sign in with Google" when a Gmail mailbox is added
  'login.microsoftonline.com',         // Microsoft 365 / Outlook
  'login.live.com',
  'login.microsoft.com',
]

function host(u) {
  try {
    return new URL(u).host.toLowerCase()
  } catch {
    return ''
  }
}

function isWeb(u) {
  return /^https?:\/\//i.test(u || '')
}

/**
 * 'app'      the MailLook app itself: stays in the window
 * 'sign-in'  a sign-in page MailLook sends you to: stays in the window, and comes back by itself
 * 'browser'  anything else on the web: the default browser
 * 'mailto'   a mail link: a new mail in MailLook
 * 'ignore'   nothing worth opening (javascript:, file:, about:blank and so on)
 */
function classify(url, appUrl, extraAppHosts = []) {
  if (/^mailto:/i.test(url || '')) return 'mailto'
  if (!isWeb(url)) return 'ignore'
  const h = host(url)
  const appHosts = [host(appUrl), ...extraAppHosts.map((x) => x.toLowerCase())]
  if (appHosts.includes(h)) return 'app'
  if (SIGN_IN_HOSTS.includes(h)) return 'sign-in'
  return 'browser'
}

module.exports = { classify, host, SIGN_IN_HOSTS }
