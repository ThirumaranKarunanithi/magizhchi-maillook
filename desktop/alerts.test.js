const test = require('node:test')
const assert = require('node:assert')
const { plan, soundFor, cleanAlertSettings } = require('./alerts.js')

const mail = (id, title = `Mail ${id}`) => ({ id, kind: 'mail', title, body: 'Subject', url: `/?open=${id}` })
const reminder = (id) => ({ id, kind: 'reminder', title: `Back from snooze ${id}`, body: 'b', url: `/?open=${id}` })

test('each new mail gets its own alert, opening that mail', () => {
  const out = plan([mail(1, 'Needs you · Priya'), mail(2)])
  assert.strictEqual(out.length, 2)
  assert.deepStrictEqual(out[0], { kind: 'mail', title: 'Needs you · Priya', body: 'Subject', url: '/?open=1', sticky: false })
})

test('many new mails at once become one alert', () => {
  const out = plan([mail(1), mail(2), mail(3), mail(4), mail(5)])
  assert.strictEqual(out.length, 1)
  assert.strictEqual(out[0].title, '5 new mails')
})

test('reminders stay on screen, and still come while MailLook is in front', () => {
  const out = plan([mail(1), reminder(2), { id: 3, kind: 'problem', title: 'A mail didn’t send', url: '/scheduled' }], { focused: true })
  assert.deepStrictEqual(out.map((a) => [a.kind, a.sticky]), [['reminder', true], ['problem', true]])
})

test('a pile waiting at start becomes "While you were away"', () => {
  const out = plan([mail(1), mail(2), reminder(3), mail(4)], { first: true })
  assert.strictEqual(out.length, 1)
  assert.strictEqual(out[0].title, 'While you were away')
  assert.match(out[0].body, /3 new mail alerts, 1 reminder\. Latest: Mail 4/)
  assert.strictEqual(out[0].sticky, true)
})

test('a few waiting at start still come one by one', () => {
  assert.strictEqual(plan([mail(1), reminder(2)], { first: true }).length, 2)
})

test('sounds follow the settings', () => {
  const s = cleanAlertSettings({ sound: true, mailSound: 'bright', volume: 3 })
  assert.strictEqual(s.volume, 1)
  assert.strictEqual(soundFor('mail', s), 'bright')
  assert.strictEqual(soundFor('reminder', s), 'reminder')
  assert.strictEqual(soundFor('mail', { ...s, sound: false }), null)
  assert.strictEqual(cleanAlertSettings({ mailSound: 'siren' }).mailSound, 'chime')
})
