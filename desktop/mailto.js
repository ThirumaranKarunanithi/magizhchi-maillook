// mailto: links (RFC 6068) → the fields of a new mail in MailLook.
//   mailto:priya@example.com,ravi@example.net?cc=office@example.academy&subject=Hello&body=Hi%20Priya

function split(list) {
  return (list || '')
    .split(/[,;]/)
    .map((s) => s.trim())
    .filter((s) => /^[^\s@]+@[^\s@]+$/.test(s))
}

function decode(s) {
  try {
    return decodeURIComponent((s || '').replace(/\+/g, '%20'))
  } catch {
    return s || ''
  }
}

/** { to: [], cc: [], bcc: [], subject, body }, or null when it isn't a mailto: link. */
function parseMailto(link) {
  if (typeof link !== 'string' || !/^mailto:/i.test(link)) return null
  const rest = link.slice(7)
  const q = rest.indexOf('?')
  const out = { to: split(decode(q < 0 ? rest : rest.slice(0, q))), cc: [], bcc: [], subject: '', body: '' }
  if (q >= 0) {
    for (const pair of rest.slice(q + 1).split('&')) {
      const eq = pair.indexOf('=')
      const key = decode(eq < 0 ? pair : pair.slice(0, eq)).toLowerCase()
      const value = decode(eq < 0 ? '' : pair.slice(eq + 1))
      if (key === 'to') out.to.push(...split(value))
      else if (key === 'cc') out.cc.push(...split(value))
      else if (key === 'bcc') out.bcc.push(...split(value))
      else if (key === 'subject') out.subject = value
      else if (key === 'body') out.body = value.replace(/\r\n/g, '\n')
    }
  }
  return out
}

/** The mailto: link Windows passed on the command line, if any. */
function mailtoFromArgv(argv) {
  return (argv || []).find((a) => typeof a === 'string' && /^mailto:/i.test(a)) || null
}

module.exports = { parseMailto, mailtoFromArgv }
