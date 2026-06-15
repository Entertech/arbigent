package io.github.takahirom.arbigent

import maestro.TreeNode

/**
 * Overlap-aware tap point for ClickWithIndex (mobilerun's get_clear_point idea).
 *
 * Tapping an element's geometric center is wrong when another element is drawn on
 * top of that center — e.g. a floating button over a list, or two overlapping
 * cards — because the tap lands on the occluder instead of the target. This finds
 * a point INSIDE the target's bounds that no occluder covers.
 *
 * Safety / zero-regression by construction:
 * - Returns the plain center when the center is clear (the common case).
 * - "Occluder" = an element drawn ON TOP (higher index; the element list is DFS
 *   pre-order, so ancestors have a lower index and are excluded) that is NOT a
 *   descendant of the target (a child INSIDE the target is part of it — tapping
 *   there is fine, so descendants are ignored).
 * - Any chosen alternative point stays within the target's own bounds, and falls
 *   back to the center if no clear point exists (e.g. a full-screen scrim).
 */
internal fun clearTapPoint(target: ArbigentElement, all: List<ArbigentElement>): Pair<Int, Int> {
  val r = target.rect
  val cx = r.centerX()
  val cy = r.centerY()

  fun covers(e: ArbigentElement, x: Int, y: Int): Boolean {
    val er = e.rect
    return x >= er.left && x < er.right && y >= er.top && y < er.bottom
  }

  val occluders = all.filter { e ->
    e.index > target.index &&
      e.isVisible && e.width > 0 && e.height > 0 &&
      e.treeNode !== target.treeNode &&
      !isDescendantNode(node = e.treeNode, ancestor = target.treeNode) &&
      covers(e, cx, cy)
  }
  if (occluders.isEmpty()) return cx to cy

  // Scan an interior grid of the target rect for a point no occluder covers;
  // prefer the one closest to the center (least surprising vs the default).
  val steps = 6
  var best: Pair<Int, Int>? = null
  var bestDist = Long.MAX_VALUE
  for (i in 1 until steps) {
    for (j in 1 until steps) {
      val x = r.left + r.width() * i / steps
      val y = r.top + r.height() * j / steps
      if (occluders.any { covers(it, x, y) }) continue
      val dx = (x - cx).toLong()
      val dy = (y - cy).toLong()
      val dist = dx * dx + dy * dy
      if (dist < bestDist) {
        bestDist = dist
        best = x to y
      }
    }
  }
  return best ?: (cx to cy)
}

/** True if [node] is somewhere in [ancestor]'s subtree (by reference identity). */
private fun isDescendantNode(node: TreeNode, ancestor: TreeNode): Boolean {
  for (child in ancestor.children) {
    if (child === node || isDescendantNode(node, child)) return true
  }
  return false
}
