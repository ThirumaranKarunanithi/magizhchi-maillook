import { useEffect, useRef, useState } from 'react'

// An open MailLook (the desktop app above all: closing its window only hides it) keeps running the code it
// loaded, so a new upload never reached it. This looks for a newer upload now and then and moves to it:
// by itself while MailLook isn't on screen and no mail is being written (drafts save themselves anyway),
// otherwise with a Reload button.
const EVERY_MS = 10 * 60_000

function loadedBundle() {
  const s = document.querySelector('script[type="module"][src*="/assets/index-"]')
  return s ? new URL(s.getAttribute('src'), location.origin).pathname : null
}

async function publishedBundle() {
  const res = await fetch(`/?v=${Date.now()}`, { cache: 'no-store', credentials: 'same-origin' })
  if (!res.ok) return null
  const m = (await res.text()).match(/src="([^"]*\/assets\/index-[^"]+\.js)"/)
  return m ? new URL(m[1], location.origin).pathname : null
}

export default function UpdateCheck({ writing }) {
  const [ready, setReady] = useState(false)
  const writingRef = useRef(writing)
  writingRef.current = writing

  useEffect(() => {
    const mine = loadedBundle()
    if (!mine) return undefined // the dev server: nothing published to compare with
    let found = false
    let busy = false
    const reloadIfAway = () => {
      if (found && document.visibilityState === 'hidden' && !writingRef.current) location.reload()
    }
    const check = async () => {
      if (found || busy || !navigator.onLine) return
      busy = true
      try {
        const latest = await publishedBundle()
        if (latest && latest !== mine) {
          found = true
          setReady(true)
          reloadIfAway()
        }
      } catch {
        // offline or the site is busy: try again next time
      } finally {
        busy = false
      }
    }
    const onVisibility = () => (document.visibilityState === 'visible' ? check() : reloadIfAway())
    const t = setInterval(check, EVERY_MS)
    window.addEventListener('focus', check)
    document.addEventListener('visibilitychange', onVisibility)
    check()
    return () => {
      clearInterval(t)
      window.removeEventListener('focus', check)
      document.removeEventListener('visibilitychange', onVisibility)
    }
  }, [])

  if (!ready) return null
  return (
    <div className="fixed bottom-4 left-1/2 z-[60] flex -translate-x-1/2 items-center gap-3 rounded-full border border-slate-200 bg-white px-4 py-2 text-sm text-slate-700 shadow-lg"
      role="status">
      <span>MailLook has been updated.</span>
      <button type="button" className="font-semibold text-peacock-600 hover:underline" onClick={() => location.reload()}>
        Reload
      </button>
    </div>
  )
}
