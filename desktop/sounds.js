// MailLook's own alert sounds, made with Web Audio in the page (no sound files to ship).
//   chime     two soft rising notes (new mail, the default)
//   soft      one low, round bell (new mail, quieter)
//   bright    three quick notes (new mail, easier to hear in a busy room)
//   reminder  a double chime, played twice: snooze-back, no reply, the morning briefing, a failed send

// Each note: [start seconds, frequency Hz, length seconds, loudness 0–1]
const TUNES = {
  chime: [[0, 880, 0.9, 0.6], [0.14, 1318.5, 1.1, 0.5]],
  soft: [[0, 659.3, 1.4, 0.55]],
  bright: [[0, 1046.5, 0.5, 0.45], [0.11, 1318.5, 0.5, 0.45], [0.22, 1568, 0.9, 0.45]],
  reminder: [[0, 987.8, 0.6, 0.6], [0.16, 1318.5, 0.8, 0.55], [0.75, 987.8, 0.6, 0.6], [0.91, 1318.5, 1.0, 0.55]],
}

let ctx = null

/** Plays a sound by name at volume 0–1. Returns false when Web Audio isn't there. */
function playSound(name, volume = 0.7) {
  const tune = TUNES[name] || TUNES.chime
  const AC = globalThis.AudioContext || globalThis.webkitAudioContext
  if (!AC) return false
  if (!ctx || ctx.state === 'closed') ctx = new AC()
  if (ctx.state === 'suspended') ctx.resume().catch(() => {})
  const now = ctx.currentTime + 0.03
  const master = ctx.createGain()
  master.gain.value = Math.max(0, Math.min(1, volume)) * 0.5
  master.connect(ctx.destination)
  for (const [at, freq, len, loud] of tune) {
    // A bell: a sine with a little of its octave, a quick attack and a long fade.
    for (const [mult, part] of [[1, 1], [2, 0.18], [3, 0.05]]) {
      const osc = ctx.createOscillator()
      const g = ctx.createGain()
      osc.type = 'sine'
      osc.frequency.value = freq * mult
      g.gain.setValueAtTime(0.0001, now + at)
      g.gain.exponentialRampToValueAtTime(loud * part, now + at + 0.012)
      g.gain.exponentialRampToValueAtTime(0.0001, now + at + len / mult)
      osc.connect(g)
      g.connect(master)
      osc.start(now + at)
      osc.stop(now + at + len + 0.05)
    }
  }
  return true
}

module.exports = { playSound, SOUND_NAMES: Object.keys(TUNES) }
