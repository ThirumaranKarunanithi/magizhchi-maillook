// npm run sounds → android/app/src/main/res/raw/chime.wav and reminder.wav
// The same bells as the desktop app (desktop/sounds.js), rendered to files, because Android plays an alert's
// sound from the app's resources. Mono, 16-bit, 44.1 kHz.
const fs = require('fs')
const path = require('path')

const RATE = 44100
const TUNES = {
  chime: [[0, 880, 0.9, 0.6], [0.14, 1318.5, 1.1, 0.5]],
  reminder: [[0, 987.8, 0.6, 0.6], [0.16, 1318.5, 0.8, 0.55], [0.75, 987.8, 0.6, 0.6], [0.91, 1318.5, 1.0, 0.55]],
}

function render(tune) {
  const end = Math.max(...tune.map(([at, , len]) => at + len)) + 0.1
  const out = new Float32Array(Math.ceil(end * RATE))
  for (const [at, freq, len, loud] of tune) {
    for (const [mult, part] of [[1, 1], [2, 0.18], [3, 0.05]]) {
      const l = len / mult
      const start = Math.floor(at * RATE)
      for (let i = 0; i < l * RATE && start + i < out.length; i++) {
        const t = i / RATE
        const env = t < 0.012 ? t / 0.012 : Math.exp(-5 * (t - 0.012) / l)
        out[start + i] += Math.sin(2 * Math.PI * freq * mult * t) * loud * part * env
      }
    }
  }
  const peak = out.reduce((m, v) => Math.max(m, Math.abs(v)), 0) || 1
  const pcm = Buffer.alloc(out.length * 2)
  for (let i = 0; i < out.length; i++) pcm.writeInt16LE(Math.round((out[i] / peak) * 0.8 * 32767), i * 2)
  const h = Buffer.alloc(44)
  h.write('RIFF', 0); h.writeUInt32LE(36 + pcm.length, 4); h.write('WAVE', 8)
  h.write('fmt ', 12); h.writeUInt32LE(16, 16); h.writeUInt16LE(1, 20); h.writeUInt16LE(1, 22)
  h.writeUInt32LE(RATE, 24); h.writeUInt32LE(RATE * 2, 28); h.writeUInt16LE(2, 32); h.writeUInt16LE(16, 34)
  h.write('data', 36); h.writeUInt32LE(pcm.length, 40)
  return Buffer.concat([h, pcm])
}

const dir = path.join(__dirname, 'android', 'app', 'src', 'main', 'res', 'raw')
fs.mkdirSync(dir, { recursive: true })
for (const [name, tune] of Object.entries(TUNES)) {
  const file = path.join(dir, `${name}.wav`)
  fs.writeFileSync(file, render(tune))
  console.log('wrote', path.relative(__dirname, file))
}
