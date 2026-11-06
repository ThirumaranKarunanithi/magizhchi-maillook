import { useId } from 'react'

// Lit glass badges for the lanes and folders: a small bevelled tile in the lane's own hue, lit by the
// same key light as the logo (top left), a gloss across the top and a warm bounce at the bottom edge.
// Each hue is graded individually so the three lanes read apart at a glance.
const HUES = {
  marigold: { top: '#ffd775', mid: '#f29a12', bottom: '#8a4304', rim: '#fff0c2', glyph: '#3d2203' },
  teal: { top: '#6fe3df', mid: '#139099', bottom: '#063a42', rim: '#d9fffb', glyph: '#f4fffd' },
  dusk: { top: '#a9b9ff', mid: '#5865c9', bottom: '#1d2160', rim: '#e8ecff', glyph: '#f6f7ff' },
  stone: { top: '#7f9ea1', mid: '#3b5a60', bottom: '#14292e', rim: '#e0f0ee', glyph: '#f4ede0' },
  rose: { top: '#ff9fb5', mid: '#d6325f', bottom: '#5e0b25', rim: '#ffe3ea', glyph: '#fff6f8' },
}

export default function Badge3D({ hue = 'teal', size = 26, Icon, className = '' }) {
  const id = useId().replace(/:/g, '')
  const h = HUES[hue] || HUES.teal
  return (
    <span className={`relative inline-flex shrink-0 items-center justify-center ${className}`} style={{ width: size, height: size }} aria-hidden="true">
      <svg width={size} height={size} viewBox="0 0 32 32" className="absolute inset-0" style={{ filter: 'drop-shadow(0 2px 3px rgba(0,0,0,.45))' }}>
        <defs>
          <linearGradient id={`b${id}`} x1="0.15" y1="0" x2="0.85" y2="1">
            <stop offset="0" stopColor={h.top} />
            <stop offset="0.5" stopColor={h.mid} />
            <stop offset="1" stopColor={h.bottom} />
          </linearGradient>
          <linearGradient id={`g${id}`} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" stopColor="#fff" stopOpacity="0.55" />
            <stop offset="1" stopColor="#fff" stopOpacity="0" />
          </linearGradient>
          <linearGradient id={`r${id}`} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" stopColor={h.rim} stopOpacity="0.9" />
            <stop offset="0.5" stopColor={h.rim} stopOpacity="0.05" />
            <stop offset="1" stopColor="#ffb04a" stopOpacity="0.5" />
          </linearGradient>
        </defs>
        <rect x="1" y="1" width="30" height="30" rx="9" fill={`url(#b${id})`} />
        <path d="M2 10 C2 4 4 2 10 2 H22 C28 2 30 4 30 10 V12 C22 9 10 9 2 15 Z" fill={`url(#g${id})`} />
        <rect x="1.5" y="1.5" width="29" height="29" rx="8.5" fill="none" stroke={`url(#r${id})`} strokeWidth="1" />
      </svg>
      {Icon && <Icon size={Math.round(size * 0.56)} strokeWidth={2.1} className="relative" style={{ color: h.glyph, filter: 'drop-shadow(0 1px 0 rgba(0,0,0,.25))' }} />}
    </span>
  )
}
