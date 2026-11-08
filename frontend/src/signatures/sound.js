// The designer's sounds, made on the spot with Web Audio (nothing to download): a soft tick when a design
// is hovered, a bell for each design at its own pitch when it is picked, and a seal being pressed when
// the signature is saved. Quiet by design, and off with one switch (remembered on this device).

const KEY = 'mlook.royalSound'
let ctx = null
let master = null

export function soundOn() {
  try {
    return localStorage.getItem(KEY) !== 'off'
  } catch {
    return true
  }
}

export function setSoundOn(on) {
  try {
    localStorage.setItem(KEY, on ? 'on' : 'off')
  } catch {
    // private window: it just isn't remembered
  }
}

function audio() {
  if (!soundOn() || typeof window === 'undefined') return null
  const AC = window.AudioContext || window.webkitAudioContext
  if (!AC) return null
  if (!ctx) {
    ctx = new AC()
    master = ctx.createGain()
    master.gain.value = 0.55
    // A small room, so the bell rings instead of beeping.
    const verb = ctx.createConvolver()
    verb.buffer = room(ctx, 1.6)
    const wet = ctx.createGain()
    wet.gain.value = 0.28
    master.connect(ctx.destination)
    master.connect(verb)
    verb.connect(wet)
    wet.connect(ctx.destination)
  }
  if (ctx.state === 'suspended') ctx.resume().catch(() => {})
  return ctx
}

function room(ac, seconds) {
  const len = Math.floor(ac.sampleRate * seconds)
  const buf = ac.createBuffer(2, len, ac.sampleRate)
  for (let ch = 0; ch < 2; ch++) {
    const d = buf.getChannelData(ch)
    for (let i = 0; i < len; i++) d[i] = (Math.random() * 2 - 1) * Math.pow(1 - i / len, 3)
  }
  return buf
}

function tone(ac, freq, at, { dur = 1.4, vol = 0.2, type = 'sine', attack = 0.004 } = {}) {
  const o = ac.createOscillator()
  const g = ac.createGain()
  o.type = type
  o.frequency.setValueAtTime(freq, at)
  g.gain.setValueAtTime(0.0001, at)
  g.gain.exponentialRampToValueAtTime(vol, at + attack)
  g.gain.exponentialRampToValueAtTime(0.0001, at + dur)
  o.connect(g)
  g.connect(master)
  o.start(at)
  o.stop(at + dur + 0.05)
}

/** A bell: the struck note with the uneven overtones real bells have. */
function bell(ac, freq, at, vol = 0.16) {
  const partials = [[1, 1, 1.8], [2.0, 0.45, 1.2], [2.76, 0.32, 0.9], [5.4, 0.16, 0.5], [8.93, 0.08, 0.3]]
  for (const [ratio, level, dur] of partials) tone(ac, freq * ratio, at, { dur, vol: vol * level })
}

let lastTick = 0
export function tick() {
  const ac = audio()
  if (!ac) return
  const now = ac.currentTime
  if (now - lastTick < 0.07) return
  lastTick = now
  tone(ac, 2600, now, { dur: 0.05, vol: 0.025, type: 'triangle', attack: 0.002 })
}

/** Picking a design: its bell, then a quick shimmer a fifth and an octave above. */
export function chime(freq = 523.25) {
  const ac = audio()
  if (!ac) return
  const now = ac.currentTime + 0.01
  bell(ac, freq, now)
  tone(ac, freq * 1.5, now + 0.09, { dur: 0.7, vol: 0.035 })
  tone(ac, freq * 2, now + 0.17, { dur: 0.8, vol: 0.03 })
  tone(ac, freq * 3, now + 0.24, { dur: 0.6, vol: 0.018 })
}

/** Saving: a seal pressed into wax (a soft low thud), then a full chord on the design's note. */
export function seal(freq = 523.25) {
  const ac = audio()
  if (!ac) return
  const now = ac.currentTime + 0.01
  const o = ac.createOscillator()
  const g = ac.createGain()
  o.type = 'sine'
  o.frequency.setValueAtTime(140, now)
  o.frequency.exponentialRampToValueAtTime(48, now + 0.22)
  g.gain.setValueAtTime(0.0001, now)
  g.gain.exponentialRampToValueAtTime(0.35, now + 0.01)
  g.gain.exponentialRampToValueAtTime(0.0001, now + 0.3)
  o.connect(g)
  g.connect(master)
  o.start(now)
  o.stop(now + 0.35)
  bell(ac, freq, now + 0.12, 0.13)
  bell(ac, freq * 1.25, now + 0.2, 0.09)
  bell(ac, freq * 1.5, now + 0.28, 0.09)
  bell(ac, freq * 2, now + 0.4, 0.07)
}
