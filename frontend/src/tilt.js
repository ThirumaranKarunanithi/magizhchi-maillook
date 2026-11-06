// Pointer tilt for anything marked data-tilt: the element leans toward the pointer (a few degrees at most)
// and a soft glare follows it. One listener for the whole app; off for touch and for reduced motion.
export function installTilt() {
  if (typeof window === 'undefined') return
  const reduce = window.matchMedia('(prefers-reduced-motion: reduce)')
  const fine = window.matchMedia('(hover: hover) and (pointer: fine)')
  let current = null
  let frame = 0

  function reset(el) {
    el.classList.remove('tilting')
    el.style.removeProperty('--rx')
    el.style.removeProperty('--ry')
  }

  window.addEventListener('pointermove', (e) => {
    if (reduce.matches || !fine.matches) return
    const el = e.target instanceof Element ? e.target.closest('[data-tilt]') : null
    if (current && current !== el) reset(current)
    current = el
    if (!el) return
    cancelAnimationFrame(frame)
    frame = requestAnimationFrame(() => {
      const r = el.getBoundingClientRect()
      const x = (e.clientX - r.left) / r.width
      const y = (e.clientY - r.top) / r.height
      const max = Number(el.dataset.tilt) || 5
      el.classList.add('tilting')
      el.style.setProperty('--ry', `${((x - 0.5) * 2 * max).toFixed(2)}deg`)
      el.style.setProperty('--rx', `${((0.5 - y) * 2 * max).toFixed(2)}deg`)
      el.style.setProperty('--gx', `${(x * 100).toFixed(1)}%`)
      el.style.setProperty('--gy', `${(y * 100).toFixed(1)}%`)
    })
  }, { passive: true })

  document.addEventListener('pointerleave', () => { if (current) reset(current); current = null })
}
