/** @type {import('tailwindcss').Config} */
// Two looks, one set of colour names. The values live in src/theme.css as RGB channels:
//   light ("Morning", the default): pearl ground, peacock ink, marigold accent
//   dark  ("Night grade"): the teal-and-orange film grade, shadows in peacock, ivory text
// html[data-theme] picks the set, so `bg-white`, `text-slate-500` and so on follow the theme.
const v = (name) => `rgb(var(--c-${name}) / <alpha-value>)`
const scale = (name, steps) => Object.fromEntries(steps.map((s) => [s, v(`${name}-${s}`)]))
const STEPS = [50, 100, 200, 300, 400, 500, 600, 700, 800, 900]

export default {
  content: ['./index.html', './src/**/*.{js,jsx}'],
  theme: {
    extend: {
      colors: {
        white: v('white'),
        slate: scale('slate', STEPS),
        peacock: scale('peacock', STEPS),
        red: scale('red', STEPS),
        emerald: scale('emerald', STEPS),
        amber: scale('amber', STEPS),
        brand: {
          primary: v('brand-primary'),
          dark: v('brand-dark'),
          gold: '#F5B320',
          ink: v('brand-ink'),
          ivory: '#FFFDF8',
          peacock: '#0B5563',
        },
      },
      fontFamily: {
        sans: ['"Plus Jakarta Sans"', 'system-ui', '"Segoe UI"', '"Noto Sans Tamil"', '"Nirmala UI"', 'sans-serif'],
      },
    },
  },
  plugins: [],
}
