package io.github.takahirom.arbigent

import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ArbigentCanvasCapTest {
  private fun img(w: Int, h: Int) = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)

  @Test
  fun `caps the long edge and preserves aspect ratio`() {
    val out = ArbigentCanvas.capLongEdge(img(1080, 2280), 1024)
    assertEquals(1024, maxOf(out.width, out.height))
    // 1080 * (1024/2280) = 485
    assertEquals(485, out.width)
    assertEquals(1024, out.height)
  }

  @Test
  fun `leaves images already under the cap untouched (iOS case)`() {
    val original = img(375, 812)
    assertSame(original, ArbigentCanvas.capLongEdge(original, 1024))
  }

  @Test
  fun `cap of zero or negative disables downscaling`() {
    val original = img(1080, 2280)
    assertSame(original, ArbigentCanvas.capLongEdge(original, 0))
    assertSame(original, ArbigentCanvas.capLongEdge(original, -1))
  }
}
