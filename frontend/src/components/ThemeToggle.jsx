import { useTheme } from '../themeMode.js'

const Sun = () => (
  <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true">
    <circle cx="12" cy="12" r="4.2" fill="currentColor" stroke="none" />
    <path d="M12 2v2.2M12 19.8V22M4.9 4.9l1.6 1.6M17.5 17.5l1.6 1.6M2 12h2.2M19.8 12H22M4.9 19.1l1.6-1.6M17.5 6.5l1.6-1.6" />
  </svg>
)
const Moon = () => (
  <svg width="14" height="14" viewBox="0 0 24 24" aria-hidden="true">
    <path d="M20.5 14.6A8.6 8.6 0 0 1 9.4 3.5a8.6 8.6 0 1 0 11.1 11.1Z" fill="currentColor" />
  </svg>
)

/** Light / dark switch: a small lit track with a knob that slides to the chosen mode. */
export default function ThemeToggle({ className = '' }) {
  const [theme, toggle] = useTheme()
  const dark = theme === 'dark'
  return (
    <button
      type="button"
      role="switch"
      aria-checked={dark}
      aria-label="Dark mode"
      title={dark ? 'Switch to light mode' : 'Switch to dark mode'}
      onClick={toggle}
      className={`relative inline-flex h-7 w-[52px] shrink-0 items-center rounded-full transition-colors ${className}`}
      style={{
        background: dark ? 'linear-gradient(180deg,#06252b,#0e3d45)' : 'linear-gradient(180deg,#fff3d6,#ffe2a3)',
        boxShadow: dark
          ? 'inset 0 2px 5px rgba(0,0,0,.55), inset 0 0 0 1px rgba(82,198,201,.25)'
          : 'inset 0 2px 4px rgba(160,100,10,.25), inset 0 0 0 1px rgba(224,161,42,.45)',
      }}
    >
      <span
        className="absolute top-[3px] flex h-[22px] w-[22px] items-center justify-center rounded-full transition-[left] duration-300"
        style={{
          left: dark ? 27 : 3,
          color: dark ? '#cfe9ff' : '#b45309',
          background: dark ? 'radial-gradient(circle at 35% 30%,#3c5a63,#13323a)' : 'radial-gradient(circle at 35% 30%,#fff,#ffe9b5)',
          boxShadow: dark ? '0 2px 6px rgba(0,0,0,.6), inset 0 1px 0 rgba(255,255,255,.15)' : '0 2px 5px rgba(160,100,10,.45), inset 0 1px 0 #fff',
        }}
      >
        {dark ? <Moon /> : <Sun />}
      </span>
    </button>
  )
}
