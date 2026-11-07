const test = require('node:test')
const assert = require('node:assert')
const { classify } = require('./links.js')
const { parseMailto, mailtoFromArgv } = require('./mailto.js')

const APP = 'https://maillook.magizhchi.software'
const API = ['maillookback.magizhchi.software']

test('MailLook and its API stay in the window', () => {
  assert.equal(classify('https://maillook.magizhchi.software/?view=needs_me', APP, API), 'app')
  assert.equal(classify('https://maillookback.magizhchi.software/api/oauth/google/callback?code=x', APP, API), 'app')
})

test('sign-in pages stay in the window', () => {
  assert.equal(classify('https://accounts.magizhchi.software/?return=x', APP, API), 'sign-in')
  assert.equal(classify('https://accounts.google.com/o/oauth2/v2/auth?x=1', APP, API), 'sign-in')
  assert.equal(classify('https://login.microsoftonline.com/common/oauth2', APP, API), 'sign-in')
})

test('everything else goes to the browser', () => {
  assert.equal(classify('https://meet.magizhchi.software/room/abc', APP, API), 'browser')
  assert.equal(classify('http://example.com/unsubscribe', APP, API), 'browser')
  // a look-alike host is not MailLook
  assert.equal(classify('https://maillook.magizhchi.software.evil.example/', APP, API), 'browser')
})

test('mail links and odd schemes', () => {
  assert.equal(classify('mailto:a@b.co', APP, API), 'mailto')
  assert.equal(classify('javascript:alert(1)', APP, API), 'ignore')
  assert.equal(classify('file:///C:/Windows', APP, API), 'ignore')
  assert.equal(classify('about:blank', APP, API), 'ignore')
})

test('mailto: fields', () => {
  assert.deepEqual(
    parseMailto('mailto:priya@example.com,ravi@example.net?cc=office@example.academy&subject=Saturday%20batch&body=Hi%20Priya%0D%0ASee%20you'),
    { to: ['priya@example.com', 'ravi@example.net'], cc: ['office@example.academy'], bcc: [], subject: 'Saturday batch', body: 'Hi Priya\nSee you' },
  )
  assert.deepEqual(parseMailto('MAILTO:?to=a@b.co&bcc=c@d.co'), { to: ['a@b.co'], cc: [], bcc: ['c@d.co'], subject: '', body: '' })
  assert.equal(parseMailto('https://x.y'), null)
  assert.deepEqual(parseMailto('mailto:not-an-address'), { to: [], cc: [], bcc: [], subject: '', body: '' })
})

test('the link Windows passes on the command line', () => {
  assert.equal(mailtoFromArgv(['C:\\MailLook.exe', '--hidden']), null)
  assert.equal(mailtoFromArgv(['C:\\MailLook.exe', 'mailto:a@b.co']), 'mailto:a@b.co')
})
