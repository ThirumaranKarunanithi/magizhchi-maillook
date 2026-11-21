/**
 * Lets a person pick a floating card up and put it anywhere on the screen, with a mouse, a finger or a smart-board pen.
 *
 * <p>Smooth because nothing re-renders while it moves: each pointer move only sets the element's CSS `translate` (drawn
 * by the compositor, no layout), at most once a frame. `translate` rather than `transform` because the card's own
 * animations (opening, the hover lift) use `transform`, and an animation would override an inline transform. On release
 * the card's left/top take the new place and the translate is cleared in the same frame, so it never jumps.
 *
 * <p>A press that barely moves is still a tap (the pill opens PA); a real drag swallows the click that follows it. The
 * place is remembered on this computer (paSpot.js), as a share of the screen, so it survives a reload and a resize.
 */
import { useCallback, useEffect, useLayoutEffect, useRef } from 'react'
import { dragTo, isDrag, place, readSpot, saveSpot, spotOf } from './paSpot.js'

const viewport = () => ({ vw: document.documentElement.clientWidth || window.innerWidth, vh: window.innerHeight })
const INTERACTIVE = 'button, a, input, textarea, select, [role="button"], [data-nodrag]'

export function useMovable(key, { enabled = true } = {}) {
  const ref = useRef(null)          // the card that moves
  const spot = useRef(readSpot(key))
  const press = useRef(null)
  const frame = useRef(0)
  const swallowClick = useRef(false)
  const on = useRef(enabled)
  on.current = enabled

  /** Put the card where its spot says, or back on its stylesheet place when it has none. */
  const apply = useCallback(() => {
    const el = ref.current
    if (!el || press.current?.dragging) return
    if (!spot.current || !on.current) {
      el.style.left = el.style.top = el.style.right = el.style.bottom = ''
      return
    }
    const { vw, vh } = viewport()
    const p = place(spot.current, el.offsetWidth, el.offsetHeight, vw, vh)
    el.style.left = `${p.x}px`
    el.style.top = `${p.y}px`
    el.style.right = 'auto'
    el.style.bottom = 'auto'
  }, [])

  // Every render (the card may have just appeared), before it is painted, so it never flashes in its old place.
  useLayoutEffect(apply)
  useEffect(() => {
    window.addEventListener('resize', apply)
    return () => { window.removeEventListener('resize', apply); cancelAnimationFrame(frame.current); frame.current = 0 }
  }, [apply])

  const finish = (e) => {
    const p = press.current
    if (!p || e.pointerId !== p.id) return
    press.current = null
    try { e.currentTarget.releasePointerCapture?.(p.id) } catch { /* already released */ }
    if (!p.dragging) return
    cancelAnimationFrame(frame.current)
    frame.current = 0              // or the next drag would think a frame is already coming, and never draw
    const el = ref.current
    document.body.style.userSelect = p.userSelect
    if (!el) return
    const { vw, vh } = viewport()
    const at = p.last || { x: p.x, y: p.y }
    el.style.left = `${at.x}px`
    el.style.top = `${at.y}px`
    el.style.right = 'auto'
    el.style.bottom = 'auto'
    el.style.translate = ''
    el.style.willChange = ''
    el.classList.remove('pa-dragging')
    spot.current = spotOf(at.x, at.y, p.w, p.h, vw, vh)
    saveSpot(key, spot.current)
    swallowClick.current = true
    setTimeout(() => { swallowClick.current = false }, 0)
  }

  const handle = {
    style: enabled ? { touchAction: 'none' } : undefined,
    onPointerDown(e) {
      if (!on.current || !ref.current || (e.pointerType === 'mouse' && e.button !== 0)) return
      const hit = e.target.closest?.(INTERACTIVE)
      if (hit && hit !== e.currentTarget) return     // the buttons in a header still work as buttons
      const el = ref.current
      const cs = getComputedStyle(el)
      press.current = {
        id: e.pointerId, sx: e.clientX, sy: e.clientY,
        x: parseFloat(cs.left) || 0, y: parseFloat(cs.top) || 0,
        w: el.offsetWidth, h: el.offsetHeight, dragging: false, last: null, userSelect: '',
      }
      try { e.currentTarget.setPointerCapture?.(e.pointerId) } catch { /* not capturable */ }
    },
    onPointerMove(e) {
      const p = press.current
      if (!p || e.pointerId !== p.id) return
      const dx = e.clientX - p.sx
      const dy = e.clientY - p.sy
      if (!p.dragging) {
        if (!isDrag(dx, dy)) return
        p.dragging = true
        p.userSelect = document.body.style.userSelect
        document.body.style.userSelect = 'none'
        ref.current.style.willChange = 'translate'
        ref.current.classList.add('pa-dragging')
      }
      e.preventDefault()
      const { vw, vh } = viewport()
      p.last = dragTo({ x: p.x, y: p.y }, dx, dy, p.w, p.h, vw, vh)
      if (frame.current) return
      frame.current = requestAnimationFrame(() => {
        frame.current = 0
        const el = ref.current
        if (el && press.current === p && p.last) el.style.translate = `${p.last.x - p.x}px ${p.last.y - p.y}px`
      })
    },
    onPointerUp: finish,
    onPointerCancel: finish,
    onClickCapture(e) {
      if (!swallowClick.current) return
      swallowClick.current = false
      e.preventDefault()
      e.stopPropagation()
    },
  }

  /** Back to where the card sits by default. */
  const reset = useCallback(() => {
    spot.current = null
    saveSpot(key, null)
    apply()
  }, [key, apply])

  return { ref, handle, reset }
}
