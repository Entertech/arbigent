package io.github.takahirom.arbigent

import maestro.TreeNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClearTapPointTest {
  private fun el(index: Int, x: Int, y: Int, w: Int, h: Int, node: TreeNode = TreeNode()): ArbigentElement =
    ArbigentElement(
      index = index, textForAI = "e$index", rawText = "", treeNode = node,
      identifierData = ArbigentElement.IdentifierData(listOf(index), 0),
      x = x, y = y, width = w, height = h, isVisible = true,
    )

  @Test
  fun `clear center returns center`() {
    val target = el(0, 0, 0, 100, 100)
    assertEquals(50 to 50, clearTapPoint(target, listOf(target)))
  }

  @Test
  fun `higher-index occluder over center shifts the tap, staying inside target`() {
    val target = el(0, 0, 0, 100, 100)
    val occluder = el(1, 30, 30, 40, 40) // covers center (50,50)
    val (x, y) = clearTapPoint(target, listOf(target, occluder))
    // moved off the covered center...
    assertTrue("should avoid the occluder", !(x in 30 until 70 && y in 30 until 70))
    // ...but still inside the target
    assertTrue(x in 0..100 && y in 0..100)
  }

  @Test
  fun `descendant covering center is ignored (center kept)`() {
    val child = TreeNode()
    val parentNode = TreeNode(children = listOf(child))
    val target = el(0, 0, 0, 100, 100, node = parentNode)
    val descendant = el(1, 30, 30, 40, 40, node = child) // inside the target's subtree
    assertEquals(50 to 50, clearTapPoint(target, listOf(target, descendant)))
  }

  @Test
  fun `lower-index element (drawn under) is not an occluder`() {
    val under = el(0, 30, 30, 40, 40)
    val target = el(1, 0, 0, 100, 100)
    assertEquals(50 to 50, clearTapPoint(target, listOf(under, target)))
  }

  @Test
  fun `full-screen occluder leaves no clear point - falls back to center`() {
    val target = el(0, 0, 0, 100, 100)
    val scrim = el(1, 0, 0, 100, 100)
    assertEquals(50 to 50, clearTapPoint(target, listOf(target, scrim)))
  }
}
