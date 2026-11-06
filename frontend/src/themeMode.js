import { useEffect, useState } from 'react'

// Light ("Morning") is the default; the choice is remembered on this device. index.html applies the saved
// theme before the app loads, so the page never flashes the wrong one.
const KEY = 'mlook_theme'
const EVENT = 'mlook:theme'

export function currentTheme() {
  return document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light'
}

export function setTheme(theme) {
  const t = theme === 'dark' ? 'dark' : 'light'
  document.documentElement.dataset.theme = t
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', t === 'dark' ? '#05161a' : '#fbf8f2')
  try {
    localStorage.setItem(KEY, t)
  } catch {
    /* private mode: the choice lasts for this visit */
  }
  window.dispatchEvent(new CustomEvent(EVENT, { detail: t }))
}

export function useTheme() {
  const [theme, set] = useState(currentTheme)
  useEffect(() => {
    const on = (e) => set(e.detail)
    window.addEventListener(EVENT, on)
    return () => window.removeEventListener(EVENT, on)
  }, [])
  return [theme, () => setTheme(theme === 'dark' ? 'light' : 'dark')]
}
