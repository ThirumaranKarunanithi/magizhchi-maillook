/**
 * Where a movable card sits on the screen. Pure, so it can be tested without a browser.
 *
 * <p>A spot is kept as a fraction of the room the card has to move in (0 = flush left/top, 1 = flush right/bottom), not
 * in pixels: a card dropped in the bottom-right corner of a laptop is still in the bottom-right corner on a smart board,
 * and a window made smaller can never push it off the screen.
 */

/** A press that moves less than this is a tap, not a drag (a finger on a smart board wobbles a little). */
export const SLOP = 6
/** Nearest a card may come to the edge of the screen. */
export const MARGIN = 8

const clamp01 = (v) => Math.min(1, Math.max(0, v))

/** The card's top-left corner for a spot, given its size and the screen's. */
export function place(spot, w, h, vw, vh, margin = MARGIN) {
  const roomX = Math.max(0, vw - w - 2 * margin)
  const roomY = Math.max(0, vh - h - 2 * margin)
  return { x: margin + clamp01(spot.fx) * roomX, y: margin + clamp01(spot.fy) * roomY }
}

/** The spot for a top-left corner (clamped onto the screen). */
export function spotOf(x, y, w, h, vw, vh, margin = MARGIN) {
  const roomX = vw - w - 2 * margin
  const roomY = vh - h - 2 * margin
  return {
    fx: roomX > 0 ? clamp01((x - margin) / roomX) : 0,
    fy: roomY > 0 ? clamp01((y - margin) / roomY) : 0,
  }
}

/** Where the card is while being dragged: started at {x,y}, the pointer has moved {dx,dy}; it never leaves the screen. */
export function dragTo(start, dx, dy, w, h, vw, vh, margin = MARGIN) {
  return place(spotOf(start.x + dx, start.y + dy, w, h, vw, vh, margin), w, h, vw, vh, margin)
}

/** Whether a press has moved far enough to be a drag. */
export const isDrag = (dx, dy, slop = SLOP) => dx * dx + dy * dy >= slop * slop

export function readSpot(key) {
  try {
    const s = JSON.parse(localStorage.getItem(key) || 'null')
    return s && Number.isFinite(s.fx) && Number.isFinite(s.fy) ? { fx: clamp01(s.fx), fy: clamp01(s.fy) } : null
  } catch { return null }
}

export function saveSpot(key, spot) {
  try {
    if (spot) localStorage.setItem(key, JSON.stringify({ fx: +spot.fx.toFixed(4), fy: +spot.fy.toFixed(4) }))
    else localStorage.removeItem(key)
  } catch { /* private window: it just will not be remembered */ }
}
