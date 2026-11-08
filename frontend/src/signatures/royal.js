// Royal signature banners, drawn on a canvas and baked into a picture for mail.
//
// Mail programs drop 3D, animation, web fonts and sound, but they keep a picture. So each template draws
// its crest, name and title here with the depth already in it: extruded and bevelled metal letters, a key
// light and a glint, a floor reflection, and then its own colour grade (a per-channel curve, the way a
// film LUT works), vignette and grain. Saved at 3x, so it stays sharp on high-density screens.

export const BANNER_W = 520
export const BANNER_H = 170
const STAGE_H = 122 // the banner above the reflection floor

const FONT_CSS = 'https://fonts.googleapis.com/css2?family=Cinzel:wght@600;700&family=Cinzel+Decorative:wght@700&family=Cormorant+Garamond:ital,wght@0,600;0,700;1,600&family=Playfair+Display:ital,wght@0,600;0,700;1,700&family=Noto+Serif+Tamil:wght@600;700&display=block'

const METALS = {
  gold: ['#fff6cf', '#f4d27a', '#c8962f', '#6f4a10', '#d7a748', '#fff0b5', '#a87b25'],
  rosegold: ['#fff1e6', '#f2c3a2', '#c98663', '#6b3a24', '#d99b78', '#ffe6d4', '#a8684a'],
  silver: ['#ffffff', '#e3e9f5', '#9aa6bf', '#3c4660', '#bcc6da', '#f6f8ff', '#808ba6'],
  copper: ['#ffe9cf', '#f0b27a', '#c06a2e', '#5e2a0d', '#d98b4e', '#ffd9b0', '#9c5124'],
  antique: ['#e6c987', '#b88f3e', '#7d5a1c', '#3d2a08', '#97702b', '#d6b061', '#5e4314'],
}

/**
 * The templates. `grade` is the colour grade: lift/gamma/gain per channel (0..1 lift, ~1 gamma, ~1 gain),
 * saturation, warmth, vignette, grain. `note` is the chime's pitch in Hz, so each one sounds like itself.
 */
export const TEMPLATES = [
  {
    id: 'gold-crest', name: 'Gold Crest', blurb: 'Black velvet, gold leaf, a laurel crest',
    bg: ['#1d150b', '#0b0805'], light: 'rgba(255,214,140,0.30)', metal: 'gold', depth: '#3a2507',
    font: '700 {s}px "Cinzel"', titleInk: '#e8d4a2', crest: 'laurel', pattern: 'beams', flare: '#ffd98a',
    grade: { lift: [0.02, 0.012, 0], gamma: [0.96, 1, 1.08], gain: [1.04, 1, 0.9], sat: 1.08, vignette: 0.42, grain: 3 },
    note: 523.25,
  },
  {
    id: 'peacock-royal', name: 'Peacock Royal', blurb: 'Deep teal, a peacock eye, burnished gold',
    bg: ['#0b3d45', '#041a1f'], light: 'rgba(120,230,220,0.22)', metal: 'gold', depth: '#062a2e',
    font: 'italic 700 {s}px "Playfair Display"', titleInk: '#bfe6df', crest: 'peacock', pattern: 'feathers', flare: '#7ff0e0',
    grade: { lift: [0, 0.02, 0.03], gamma: [1, 0.98, 0.95], gain: [1.05, 1, 0.94], sat: 1.12, vignette: 0.45, grain: 3 },
    note: 659.25,
  },
  {
    id: 'maroon-gold', name: 'Maroon & Gold', blurb: 'Velvet maroon damask, a wedding-card gold',
    bg: ['#5c0e1d', '#22040a'], light: 'rgba(255,190,150,0.25)', metal: 'gold', depth: '#2a0509',
    font: '700 {s}px "Cinzel Decorative"', titleInk: '#f1cfa6', crest: 'laurel', pattern: 'damask', flare: '#ffc98a',
    grade: { lift: [0.03, 0, 0.005], gamma: [0.95, 1.02, 1.04], gain: [1.05, 0.98, 0.92], sat: 1.06, vignette: 0.5, grain: 3 },
    note: 392.0,
  },
  {
    id: 'midnight-sapphire', name: 'Midnight Sapphire', blurb: 'Night blue, platinum letters, a cut sapphire',
    bg: ['#15275e', '#050a1c'], light: 'rgba(150,180,255,0.28)', metal: 'silver', depth: '#0a1130',
    font: '600 {s}px "Cinzel"', titleInk: '#c9d6f5', crest: 'gem', pattern: 'stars', flare: '#a9c6ff',
    grade: { lift: [0, 0.01, 0.035], gamma: [1.04, 1, 0.94], gain: [0.96, 1, 1.06], sat: 1.04, vignette: 0.45, grain: 2 },
    note: 880.0,
  },
  {
    id: 'temple-kolam', name: 'Temple Kolam', blurb: 'Temple-lamp glow, kolam lines, a copper lotus',
    bg: ['#7a2a0c', '#2b0c03'], light: 'rgba(255,200,110,0.32)', metal: 'copper', depth: '#2d0f03',
    font: '700 {s}px "Cormorant Garamond"', titleInk: '#ffd9a8', crest: 'lotus', pattern: 'kolam', flare: '#ffc070',
    grade: { lift: [0.03, 0.01, 0], gamma: [0.94, 1, 1.1], gain: [1.06, 0.99, 0.86], sat: 1.1, vignette: 0.48, grain: 3 },
    note: 587.33,
  },
  {
    id: 'ivory-classic', name: 'Ivory Classic', blurb: 'Cotton paper, letterpress, a blind-embossed seal',
    bg: ['#fbf6ea', '#ece2cc'], light: 'rgba(255,255,255,0.55)', metal: 'antique', depth: '#8c7442',
    font: '600 {s}px "Playfair Display"', titleInk: '#6b5a3a', crest: 'seal', pattern: 'paper', flare: null, light_bg: true,
    grade: { lift: [0.035, 0.03, 0.02], gamma: [1, 1, 1.03], gain: [0.99, 0.98, 0.95], sat: 0.92, vignette: 0.18, grain: 4 },
    note: 698.46,
  },
]

export const templateById = (id) => TEMPLATES.find((t) => t.id === id) || TEMPLATES[0]

let fontsReady = null
/** Loads the display faces once (they only exist inside the picture, never in the mail). */
export function loadFonts() {
  if (fontsReady) return fontsReady
  if (typeof document === 'undefined') return Promise.resolve()
  if (!document.querySelector('link[data-royal-fonts]')) {
    const l = document.createElement('link')
    l.rel = 'stylesheet'
    l.href = FONT_CSS
    l.dataset.royalFonts = '1'
    document.head.appendChild(l)
  }
  const specs = ['700 40px "Cinzel"', '600 40px "Cinzel"', '700 40px "Cinzel Decorative"', 'italic 700 40px "Playfair Display"',
    '600 40px "Playfair Display"', '700 40px "Cormorant Garamond"', '700 40px "Noto Serif Tamil"']
  const wait = (ms) => new Promise((r) => setTimeout(r, ms))
  fontsReady = Promise.race([
    (async () => {
      // The stylesheet has to arrive before document.fonts knows the faces.
      for (let i = 0; i < 40 && ![...document.styleSheets].some((s) => s.href === FONT_CSS); i++) await wait(50)
      await Promise.all(specs.map((s) => document.fonts.load(s, 'AaÉ அ').catch(() => null)))
    })(),
    wait(6000),
  ])
  return fontsReady
}

export function initials(name) {
  const words = (name || '').trim().split(/\s+/).filter(Boolean)
  if (!words.length) return 'M'
  const first = [...words[0]][0] || ''
  const last = words.length > 1 ? [...words[words.length - 1]][0] || '' : ''
  return (first + last).toUpperCase()
}

const TAMIL = /[஀-௿]/

function fontFor(t, size, text) {
  if (TAMIL.test(text)) return `700 ${size}px "Noto Serif Tamil", serif`
  return t.font.replace('{s}', size) + ', Georgia, serif'
}

function metalGradient(ctx, metal, top, bottom) {
  const g = ctx.createLinearGradient(0, top, 0, bottom)
  const stops = METALS[metal]
  const at = [0, 0.22, 0.46, 0.53, 0.7, 0.88, 1]
  stops.forEach((c, i) => g.addColorStop(at[i], c))
  return g
}

function seeded(seed) {
  let s = seed >>> 0 || 1
  return () => {
    s ^= s << 13; s ^= s >>> 17; s ^= s << 5
    return ((s >>> 0) % 100000) / 100000
  }
}

function hash(str) {
  let h = 2166136261
  for (const ch of str) h = Math.imul(h ^ ch.codePointAt(0), 16777619)
  return h >>> 0
}

/** Metal letters: an extruded side, a soft drop shadow, a bevelled face and a glint across it. */
function metalText(ctx, t, text, x, y, size, { depth = 6, glint = 0.38, align = 'left' } = {}) {
  ctx.save()
  ctx.font = fontFor(t, size, text)
  ctx.textAlign = align
  ctx.textBaseline = 'alphabetic'
  const w = ctx.measureText(text).width
  const left = align === 'center' ? x - w / 2 : x
  const top = y - size * 0.82
  const bottom = y + size * 0.12

  // Shadow on the ground, then the extruded side, layer by layer.
  ctx.shadowColor = t.light_bg ? 'rgba(80,60,30,0.35)' : 'rgba(0,0,0,0.65)'
  ctx.shadowBlur = size * 0.35
  ctx.shadowOffsetY = size * 0.12
  ctx.fillStyle = t.depth
  ctx.fillText(text, x + depth * 0.55, y + depth * 0.85)
  ctx.shadowColor = 'transparent'
  for (let i = depth; i >= 1; i--) {
    ctx.globalAlpha = 0.55 + 0.45 * (1 - i / depth)
    ctx.fillStyle = shade(t.depth, 1 + (depth - i) * 0.09)
    ctx.fillText(text, x + i * 0.55, y + i * 0.85)
  }
  ctx.globalAlpha = 1

  // The face, on its own layer, so the bevel and glint stay inside the letters.
  const pad = Math.ceil(size)
  const fw = Math.ceil(w + pad * 2)
  const fh = Math.ceil(bottom - top + pad * 2)
  const face = document.createElement('canvas')
  face.width = fw
  face.height = fh
  const f = face.getContext('2d')
  f.font = ctx.font
  f.textAlign = 'left'
  f.textBaseline = 'alphabetic'
  const fx = pad
  const fy = pad + (y - top)
  f.fillStyle = METALS[t.metal][0]
  f.fillText(text, fx, fy)                                    // top-left rim catches the light
  f.globalCompositeOperation = 'source-atop'
  f.fillStyle = metalGradient(f, t.metal, pad, pad + bottom - top)
  f.fillText(text, fx + Math.max(1, size * 0.03), fy + Math.max(1, size * 0.035))
  f.fillStyle = 'rgba(0,0,0,0.28)'                            // bottom-right rim in shade
  f.globalAlpha = 0.6
  f.fillText(text, fx + size * 0.07, fy + size * 0.08)
  f.globalAlpha = 1
  f.fillStyle = metalGradient(f, t.metal, pad, pad + bottom - top)
  f.fillText(text, fx + size * 0.03, fy + size * 0.035)
  if (glint > 0) {
    const gx = fw * glint
    const g = f.createLinearGradient(gx - fh * 0.9, 0, gx + fh * 0.9, fh)
    g.addColorStop(0, 'rgba(255,255,255,0)')
    g.addColorStop(0.46, 'rgba(255,255,255,0)')
    g.addColorStop(0.5, 'rgba(255,255,255,0.75)')
    g.addColorStop(0.54, 'rgba(255,255,255,0)')
    g.addColorStop(1, 'rgba(255,255,255,0)')
    f.fillStyle = g
    f.fillRect(0, 0, fw, fh)
  }
  f.globalCompositeOperation = 'source-over'

  // A faint bloom around the brightest metal, then the face itself.
  if (!t.light_bg) {
    ctx.save()
    ctx.globalAlpha = 0.35
    ctx.shadowColor = METALS[t.metal][1]
    ctx.shadowBlur = size * 0.45
    ctx.drawImage(face, left - pad, top - pad)
    ctx.restore()
  }
  ctx.drawImage(face, left - pad, top - pad)
  ctx.restore()
  return w
}

function shade(hex, k) {
  const n = parseInt(hex.slice(1), 16)
  const c = [(n >> 16) & 255, (n >> 8) & 255, n & 255].map((v) => Math.min(255, Math.round(v * k)))
  return `rgb(${c[0]},${c[1]},${c[2]})`
}

function sparkle(ctx, x, y, r, color) {
  ctx.save()
  ctx.globalCompositeOperation = 'lighter'
  const g = ctx.createRadialGradient(x, y, 0, x, y, r)
  g.addColorStop(0, 'rgba(255,255,255,0.95)')
  g.addColorStop(0.25, color)
  g.addColorStop(1, 'rgba(0,0,0,0)')
  ctx.fillStyle = g
  ctx.beginPath()
  ctx.moveTo(x - r, y); ctx.quadraticCurveTo(x, y, x, y - r); ctx.quadraticCurveTo(x, y, x + r, y)
  ctx.quadraticCurveTo(x, y, x, y + r); ctx.quadraticCurveTo(x, y, x - r, y)
  ctx.fill()
  ctx.restore()
}

/** The anamorphic streak a cinema lens throws across a bright point. */
function flare(ctx, x, y, len, color) {
  ctx.save()
  ctx.globalCompositeOperation = 'lighter'
  const g = ctx.createLinearGradient(x - len, y, x + len, y)
  g.addColorStop(0, 'rgba(0,0,0,0)')
  g.addColorStop(0.5, color)
  g.addColorStop(1, 'rgba(0,0,0,0)')
  ctx.globalAlpha = 0.55
  ctx.fillStyle = g
  ctx.fillRect(x - len, y - 0.9, len * 2, 1.8)
  ctx.globalAlpha = 0.25
  ctx.fillRect(x - len * 0.6, y - 3, len * 1.2, 6)
  ctx.restore()
  sparkle(ctx, x, y, 7, color)
}

function backdrop(ctx, t, rnd) {
  const g = ctx.createLinearGradient(0, 0, 0, BANNER_H)
  g.addColorStop(0, t.bg[0])
  g.addColorStop(1, t.bg[1])
  ctx.fillStyle = g
  ctx.fillRect(0, 0, BANNER_W, BANNER_H)
  // Key light behind the name, the way a set is lit.
  const k = ctx.createRadialGradient(250, 50, 4, 250, 60, 300)
  k.addColorStop(0, t.light)
  k.addColorStop(1, 'rgba(0,0,0,0)')
  ctx.fillStyle = k
  ctx.fillRect(0, 0, BANNER_W, BANNER_H)

  ctx.save()
  const ink = t.light_bg ? 'rgba(120,96,50,' : 'rgba(255,230,170,'
  if (t.pattern === 'beams') {
    ctx.globalCompositeOperation = 'lighter'
    for (let i = 0; i < 5; i++) {
      const x = 150 + i * 80
      const b = ctx.createLinearGradient(x, 0, x + 60, STAGE_H)
      b.addColorStop(0, 'rgba(255,220,150,0.06)')
      b.addColorStop(1, 'rgba(255,220,150,0)')
      ctx.fillStyle = b
      ctx.beginPath(); ctx.moveTo(x, 0); ctx.lineTo(x + 26, 0); ctx.lineTo(x + 90, STAGE_H); ctx.lineTo(x + 40, STAGE_H); ctx.fill()
    }
  } else if (t.pattern === 'damask') {
    ctx.strokeStyle = ink + '0.07)'
    ctx.lineWidth = 0.8
    for (let y = 10; y < BANNER_H; y += 26) {
      for (let x = (y / 26) % 2 ? 13 : 0; x < BANNER_W; x += 26) {
        ctx.beginPath()
        for (let a = 0; a < 4; a++) {
          const ang = (a * Math.PI) / 2
          ctx.moveTo(x, y)
          ctx.quadraticCurveTo(x + Math.cos(ang + 0.6) * 9, y + Math.sin(ang + 0.6) * 9, x + Math.cos(ang) * 10, y + Math.sin(ang) * 10)
          ctx.quadraticCurveTo(x + Math.cos(ang - 0.6) * 9, y + Math.sin(ang - 0.6) * 9, x, y)
        }
        ctx.stroke()
      }
    }
  } else if (t.pattern === 'stars') {
    for (let i = 0; i < 70; i++) {
      const x = rnd() * BANNER_W, y = rnd() * STAGE_H, r = rnd() * 0.9 + 0.2
      ctx.fillStyle = `rgba(210,225,255,${0.15 + rnd() * 0.5})`
      ctx.beginPath(); ctx.arc(x, y, r, 0, Math.PI * 2); ctx.fill()
    }
  } else if (t.pattern === 'kolam') {
    ctx.fillStyle = 'rgba(255,235,200,0.16)'
    ctx.strokeStyle = 'rgba(255,235,200,0.09)'
    ctx.lineWidth = 0.9
    for (let y = 12; y < STAGE_H; y += 18) {
      for (let x = 160; x < BANNER_W; x += 18) {
        ctx.beginPath(); ctx.arc(x, y, 0.9, 0, Math.PI * 2); ctx.fill()
        ctx.beginPath(); ctx.arc(x + 9, y + 9, 7.5, 0, Math.PI * 2); ctx.stroke()
      }
    }
  } else if (t.pattern === 'feathers') {
    ctx.strokeStyle = 'rgba(140,240,220,0.06)'
    ctx.lineWidth = 0.7
    for (let i = 0; i < 46; i++) {
      const a = -0.9 + i * 0.04
      ctx.beginPath(); ctx.moveTo(78, 60); ctx.quadraticCurveTo(78 + Math.cos(a) * 200, 60 + Math.sin(a) * 120, 78 + Math.cos(a) * 470, 60 + Math.sin(a) * 260); ctx.stroke()
    }
  } else if (t.pattern === 'paper') {
    for (let i = 0; i < 2200; i++) {
      ctx.fillStyle = `rgba(110,90,50,${rnd() * 0.05})`
      ctx.fillRect(rnd() * BANNER_W, rnd() * BANNER_H, 1, 1)
    }
    for (let i = 0; i < 40; i++) {
      ctx.strokeStyle = `rgba(150,130,90,${0.04 + rnd() * 0.04})`
      const x = rnd() * BANNER_W, y = rnd() * BANNER_H
      ctx.beginPath(); ctx.moveTo(x, y); ctx.lineTo(x + rnd() * 10 - 5, y + rnd() * 3); ctx.stroke()
    }
  }
  ctx.restore()
}

function frame(ctx, t) {
  ctx.save()
  const c = METALS[t.metal]
  const g = ctx.createLinearGradient(0, 0, BANNER_W, STAGE_H)
  g.addColorStop(0, c[2]); g.addColorStop(0.5, c[0]); g.addColorStop(1, c[2])
  ctx.strokeStyle = g
  ctx.globalAlpha = t.light_bg ? 0.75 : 0.85
  ctx.lineWidth = 1.2
  ctx.strokeRect(6.5, 6.5, BANNER_W - 13, STAGE_H - 9)
  ctx.globalAlpha = 0.45
  ctx.lineWidth = 0.6
  ctx.strokeRect(10.5, 10.5, BANNER_W - 21, STAGE_H - 17)
  // Corner diamonds.
  ctx.globalAlpha = 0.9
  ctx.fillStyle = g
  for (const [x, y] of [[6.5, 6.5], [BANNER_W - 6.5, 6.5], [6.5, STAGE_H - 2.5], [BANNER_W - 6.5, STAGE_H - 2.5]]) {
    ctx.beginPath(); ctx.moveTo(x, y - 3.5); ctx.lineTo(x + 3.5, y); ctx.lineTo(x, y + 3.5); ctx.lineTo(x - 3.5, y); ctx.fill()
  }
  ctx.restore()
}

function crest(ctx, t, mono, rnd) {
  const cx = 78, cy = 62, r = 40
  const c = METALS[t.metal]
  ctx.save()
  if (t.crest === 'peacock') {
    // The eye of a peacock feather behind the medallion.
    const eye = [['#0e5b6b', 58], ['#1c8f86', 50], ['#c9a24a', 44], ['#0d3b7a', 36]]
    for (const [col, rr] of eye) {
      ctx.fillStyle = col
      ctx.globalAlpha = 0.85
      ctx.beginPath(); ctx.ellipse(cx, cy, rr * 0.9, rr * 0.62, 0, 0, Math.PI * 2); ctx.fill()
    }
    ctx.globalAlpha = 1
  }
  if (t.crest === 'lotus') {
    for (let i = 0; i < 12; i++) {
      const a = (i / 12) * Math.PI * 2 - Math.PI / 2
      ctx.save()
      ctx.translate(cx, cy); ctx.rotate(a)
      const pg = ctx.createLinearGradient(0, -r - 14, 0, -r + 4)
      pg.addColorStop(0, c[0]); pg.addColorStop(1, c[3])
      ctx.fillStyle = pg
      ctx.beginPath(); ctx.moveTo(0, -r + 6); ctx.quadraticCurveTo(9, -r - 4, 0, -r - 15); ctx.quadraticCurveTo(-9, -r - 4, 0, -r + 6); ctx.fill()
      ctx.restore()
    }
  }
  if (t.crest === 'laurel') {
    ctx.strokeStyle = c[2]
    ctx.fillStyle = metalGradient(ctx, t.metal, cy - r - 10, cy + r + 10)
    for (const side of [-1, 1]) {
      for (let i = 0; i < 9; i++) {
        const a = Math.PI / 2 + side * (0.35 + i * 0.27)
        const lx = cx + Math.cos(a) * (r + 7), ly = cy + Math.sin(a) * (r + 7)
        ctx.save(); ctx.translate(lx, ly); ctx.rotate(a + side * 0.9)
        ctx.beginPath(); ctx.ellipse(0, 0, 6, 2.4, 0, 0, Math.PI * 2); ctx.fill()
        ctx.restore()
      }
    }
  }

  ctx.shadowColor = t.light_bg ? 'rgba(90,70,30,0.35)' : 'rgba(0,0,0,0.6)'
  ctx.shadowBlur = 14
  ctx.shadowOffsetY = 5
  if (t.crest === 'gem') {
    // A cut sapphire: an octagon of facets, lit from the top left.
    const pts = Array.from({ length: 8 }, (_, i) => {
      const a = (i / 8) * Math.PI * 2 + Math.PI / 8
      return [cx + Math.cos(a) * r, cy + Math.sin(a) * r]
    })
    const tones = ['#9fc0ff', '#4f78e0', '#2346a8', '#14286b', '#0b1a4a', '#1b3787', '#3a62cc', '#7aa2ff']
    pts.forEach((p, i) => {
      const q = pts[(i + 1) % 8]
      ctx.fillStyle = tones[i]
      ctx.beginPath(); ctx.moveTo(cx, cy); ctx.lineTo(p[0], p[1]); ctx.lineTo(q[0], q[1]); ctx.fill()
      ctx.shadowColor = 'transparent'
    })
    ctx.strokeStyle = 'rgba(230,240,255,0.8)'
    ctx.lineWidth = 1.4
    ctx.beginPath(); pts.forEach((p, i) => (i ? ctx.lineTo(p[0], p[1]) : ctx.moveTo(p[0], p[1]))); ctx.closePath(); ctx.stroke()
    ctx.fillStyle = 'rgba(20,40,110,0.55)'
    ctx.beginPath(); ctx.arc(cx, cy, r * 0.58, 0, Math.PI * 2); ctx.fill()
  } else {
    // A struck coin: rim, beads, a recessed field.
    const rim = ctx.createRadialGradient(cx - r * 0.4, cy - r * 0.5, 2, cx, cy, r)
    rim.addColorStop(0, c[0]); rim.addColorStop(0.55, c[2]); rim.addColorStop(1, c[3])
    ctx.fillStyle = t.crest === 'seal' ? 'rgba(0,0,0,0)' : rim
    ctx.beginPath(); ctx.arc(cx, cy, r, 0, Math.PI * 2); ctx.fill()
    ctx.shadowColor = 'transparent'
    if (t.crest === 'seal') {
      // Blind emboss: no ink, only light and shade pressed into the paper.
      ctx.lineWidth = 2.2
      ctx.strokeStyle = 'rgba(255,255,255,0.9)'
      ctx.beginPath(); ctx.arc(cx - 0.8, cy - 0.8, r, 0, Math.PI * 2); ctx.stroke()
      ctx.strokeStyle = 'rgba(120,98,60,0.45)'
      ctx.beginPath(); ctx.arc(cx + 0.8, cy + 0.8, r, 0, Math.PI * 2); ctx.stroke()
      ctx.lineWidth = 1
      ctx.strokeStyle = 'rgba(120,98,60,0.35)'
      ctx.beginPath(); ctx.arc(cx, cy, r - 6, 0, Math.PI * 2); ctx.stroke()
    } else {
      const field = ctx.createRadialGradient(cx + r * 0.3, cy + r * 0.35, 2, cx, cy, r * 0.8)
      field.addColorStop(0, shade(t.depth, 2.2)); field.addColorStop(1, shade(t.depth, 0.9))
      ctx.fillStyle = field
      ctx.beginPath(); ctx.arc(cx, cy, r * 0.8, 0, Math.PI * 2); ctx.fill()
      ctx.strokeStyle = 'rgba(0,0,0,0.35)'
      ctx.lineWidth = 1
      ctx.stroke()
    }
    ctx.fillStyle = t.crest === 'seal' ? 'rgba(130,108,70,0.4)' : c[1]
    for (let i = 0; i < 36; i++) {
      const a = (i / 36) * Math.PI * 2
      ctx.beginPath(); ctx.arc(cx + Math.cos(a) * r * 0.9, cy + Math.sin(a) * r * 0.9, 1, 0, Math.PI * 2); ctx.fill()
    }
  }
  ctx.restore()

  const size = mono.length > 1 ? 30 : 36
  if (t.crest === 'seal') {
    // Pressed letters: shade above-left, light below-right.
    ctx.save()
    ctx.font = fontFor(t, size, mono)
    ctx.textAlign = 'center'
    ctx.fillStyle = 'rgba(120,98,60,0.5)'; ctx.fillText(mono, cx - 0.8, cy + size * 0.36 - 0.8)
    ctx.fillStyle = 'rgba(255,255,255,0.95)'; ctx.fillText(mono, cx + 0.8, cy + size * 0.36 + 0.8)
    ctx.fillStyle = 'rgba(214,200,168,0.9)'; ctx.fillText(mono, cx, cy + size * 0.36)
    ctx.restore()
  } else {
    metalText(ctx, t, mono, cx, cy + size * 0.36, size, { depth: 3, glint: 0.3, align: 'center' })
  }
  if (t.flare) sparkle(ctx, cx - r * 0.55, cy - r * 0.6, 9 + rnd() * 3, t.flare)
}

function fitSize(ctx, t, text, max, from, min) {
  for (let s = from; s >= min; s -= 1) {
    ctx.font = fontFor(t, s, text)
    if (ctx.measureText(text).width <= max) return s
  }
  return min
}

function divider(ctx, t, x, y, w) {
  const c = METALS[t.metal]
  ctx.save()
  const g = ctx.createLinearGradient(x, 0, x + w, 0)
  g.addColorStop(0, 'rgba(0,0,0,0)'); g.addColorStop(0.15, c[1]); g.addColorStop(0.5, c[0]); g.addColorStop(0.85, c[1]); g.addColorStop(1, 'rgba(0,0,0,0)')
  ctx.fillStyle = g
  ctx.fillRect(x, y - 0.5, w, 1)
  const m = x + w / 2
  ctx.fillStyle = c[1]
  ctx.beginPath(); ctx.moveTo(m, y - 4); ctx.lineTo(m + 4, y); ctx.lineTo(m, y + 4); ctx.lineTo(m - 4, y); ctx.fill()
  ctx.restore()
}

/** The floor: the lower part of the stage, mirrored and fading, with a gloss line where it meets. */
function reflection(ctx, t, scale) {
  const band = BANNER_H - STAGE_H
  const src = ctx.getImageData(0, (STAGE_H - band) * scale, BANNER_W * scale, band * scale)
  const tmp = document.createElement('canvas')
  tmp.width = BANNER_W * scale
  tmp.height = band * scale
  tmp.getContext('2d').putImageData(src, 0, 0)
  ctx.save()
  ctx.setTransform(1, 0, 0, 1, 0, 0)
  // Cover the floor with the backdrop's lower tone, then lay the mirror on it.
  const floor = ctx.createLinearGradient(0, STAGE_H * scale, 0, BANNER_H * scale)
  floor.addColorStop(0, shade(t.bg[1], t.light_bg ? 0.985 : 1.25))
  floor.addColorStop(1, t.bg[1])
  ctx.fillStyle = floor
  ctx.fillRect(0, STAGE_H * scale, BANNER_W * scale, band * scale)
  const mirror = document.createElement('canvas')
  mirror.width = tmp.width
  mirror.height = tmp.height
  const m = mirror.getContext('2d')
  m.translate(0, tmp.height)
  m.scale(1, -1)
  m.drawImage(tmp, 0, 0)
  m.setTransform(1, 0, 0, 1, 0, 0)
  m.globalCompositeOperation = 'destination-in'
  const fade = m.createLinearGradient(0, 0, 0, tmp.height)
  fade.addColorStop(0, `rgba(0,0,0,${t.light_bg ? 0.22 : 0.38})`)
  fade.addColorStop(0.75, 'rgba(0,0,0,0)')
  m.fillStyle = fade
  m.fillRect(0, 0, tmp.width, tmp.height)
  ctx.drawImage(mirror, 0, STAGE_H * scale)
  const gloss = ctx.createLinearGradient(0, 0, BANNER_W * scale, 0)
  gloss.addColorStop(0, 'rgba(255,255,255,0)')
  gloss.addColorStop(0.5, t.light_bg ? 'rgba(160,140,100,0.35)' : 'rgba(255,240,210,0.35)')
  gloss.addColorStop(1, 'rgba(255,255,255,0)')
  ctx.fillStyle = gloss
  ctx.fillRect(0, STAGE_H * scale, BANNER_W * scale, Math.max(1, scale * 0.8))
  ctx.restore()
}

/** The grade: a 256-step curve per channel (lift, gamma, gain), saturation, vignette and grain. */
function grade(ctx, t, scale, rnd) {
  const { lift, gamma, gain, sat, vignette, grain } = t.grade
  const w = BANNER_W * scale, h = BANNER_H * scale
  const img = ctx.getImageData(0, 0, w, h)
  const d = img.data
  const lut = [0, 1, 2].map((ch) => {
    const table = new Uint8ClampedArray(256)
    for (let i = 0; i < 256; i++) {
      let v = i / 255
      v = lift[ch] + v * (1 - lift[ch])
      v = Math.pow(v, gamma[ch])
      v *= gain[ch]
      table[i] = Math.round(Math.max(0, Math.min(1, v)) * 255)
    }
    return table
  })
  const cx = w / 2, cy = h * 0.42
  const maxD = Math.hypot(cx, h - cy)
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const i = (y * w + x) * 4
      let r = lut[0][d[i]], g = lut[1][d[i + 1]], b = lut[2][d[i + 2]]
      const l = 0.299 * r + 0.587 * g + 0.114 * b
      r = l + (r - l) * sat; g = l + (g - l) * sat; b = l + (b - l) * sat
      const dist = Math.hypot((x - cx) * 0.75, y - cy) / maxD
      const v = 1 - vignette * Math.pow(Math.max(0, dist - 0.25) / 0.75, 1.6)
      const n = (rnd() - 0.5) * grain * 2
      d[i] = r * v + n; d[i + 1] = g * v + n; d[i + 2] = b * v + n
    }
  }
  ctx.putImageData(img, 0, 0)
}

/**
 * Draws one banner. `scale` 3 is the saved picture; previews use less. Returns the canvas.
 * Details: { name, title, company }.
 */
export function renderBanner(template, details, scale = 2, canvas = document.createElement('canvas')) {
  const t = typeof template === 'string' ? templateById(template) : template
  const name = (details.name || '').trim() || 'Your Name'
  const title = [details.title, details.company].map((s) => (s || '').trim()).filter(Boolean).join('  ·  ')
  canvas.width = BANNER_W * scale
  canvas.height = BANNER_H * scale
  const ctx = canvas.getContext('2d', { willReadFrequently: true })
  ctx.setTransform(scale, 0, 0, scale, 0, 0)
  const rnd = seeded(hash(t.id + name))

  backdrop(ctx, t, rnd)
  frame(ctx, t)
  crest(ctx, t, initials(name), rnd)

  const x = 146, maxW = BANNER_W - x - 22
  const size = fitSize(ctx, t, name, maxW, 40, 20)
  const nameY = title ? 62 : 74
  const w = metalText(ctx, t, name, x, nameY, size, { depth: Math.max(3, Math.round(size / 7)), glint: 0.36 })
  if (title) {
    divider(ctx, t, x, nameY + 13, Math.min(maxW, Math.max(140, w)))
    ctx.save()
    const shown = TAMIL.test(title) ? title : title.toUpperCase()
    const titleFont = (s) => (TAMIL.test(title) ? `600 ${s}px "Noto Serif Tamil"` : `600 ${s}px "Cinzel"`) + ', Georgia, serif'
    const spaced = 'letterSpacing' in ctx && !TAMIL.test(title) // spacing would pull Tamil letters apart
    let ts = 12
    for (; ts > 8; ts--) {
      ctx.font = titleFont(ts)
      if (spaced) ctx.letterSpacing = `${(ts * 0.18).toFixed(1)}px`
      if (ctx.measureText(shown).width <= maxW) break
    }
    ctx.font = titleFont(ts)
    ctx.fillStyle = t.titleInk
    ctx.shadowColor = t.light_bg ? 'rgba(255,255,255,0.8)' : 'rgba(0,0,0,0.6)'
    ctx.shadowOffsetY = t.light_bg ? 1 : 1.5
    ctx.shadowBlur = t.light_bg ? 0 : 3
    ctx.fillText(shown, x, nameY + 36)
    ctx.restore()
  }
  if (t.flare) flare(ctx, Math.min(x + w - 6, BANNER_W - 40), nameY - size * 0.62, 120, t.flare)

  reflection(ctx, t, scale)
  ctx.setTransform(1, 0, 0, 1, 0, 0)
  grade(ctx, t, scale, rnd)
  return canvas
}

/** The saved picture: 3x, as JPEG (small enough for mail, grain and all). */
export async function bannerBlob(templateId, details) {
  await loadFonts()
  const c = renderBanner(templateId, details, 3)
  return new Promise((resolve, reject) => c.toBlob((b) => (b ? resolve(b) : reject(new Error("Couldn't draw the signature."))), 'image/jpeg', 0.9))
}

/** The colours of the text lines under the banner (the same as the server's, for the preview). */
export const LINE_COLORS = {
  'gold-crest': { ink: '#2a2112', accent: '#b8892b', muted: '#6e6047', link: '#8a6414' },
  'peacock-royal': { ink: '#0d2b33', accent: '#1f8a8a', muted: '#4d6b70', link: '#13707a' },
  'maroon-gold': { ink: '#3a0d14', accent: '#a8742a', muted: '#6e4a4f', link: '#8c1d2c' },
  'midnight-sapphire': { ink: '#111a33', accent: '#3b5bb8', muted: '#56607a', link: '#2a4aa3' },
  'temple-kolam': { ink: '#3b1d0b', accent: '#c0541b', muted: '#73513c', link: '#a8420f' },
  'ivory-classic': { ink: '#262420', accent: '#9c8a63', muted: '#6b665c', link: '#5a4a26' },
}
