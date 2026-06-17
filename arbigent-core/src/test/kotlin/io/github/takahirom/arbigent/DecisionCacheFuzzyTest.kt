package io.github.takahirom.arbigent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionCacheFuzzyTest {
  @Test
  fun `jaccard basics`() {
    assertEquals(1.0, DecisionCacheFuzzy.jaccard(setOf("a", "b"), setOf("a", "b")), 1e-9)
    assertEquals(0.0, DecisionCacheFuzzy.jaccard(setOf("a"), setOf("b")), 1e-9)
    assertEquals(1.0, DecisionCacheFuzzy.jaccard(emptySet(), emptySet()), 1e-9)
    assertEquals(0.0, DecisionCacheFuzzy.jaccard(setOf("a"), emptySet()), 1e-9)
    // {a,b,c} vs {a,b,d} -> 2/4
    assertEquals(0.5, DecisionCacheFuzzy.jaccard(setOf("a", "b", "c"), setOf("a", "b", "d")), 1e-9)
  }

  @Test
  fun `clock tick keeps high similarity on a realistic tree`() {
    // A realistic optimized tree has hundreds of tokens; one changed status-bar
    // clock token is a tiny fraction, so similarity stays well above 0.85.
    val body = (1..60).joinToString(" ") { "Row$it(text=item$it enabled=true clickable=true)" }
    val a = DecisionCacheFuzzy.tokenize("StatusBar(time=1356 battery=100) $body")
    val b = DecisionCacheFuzzy.tokenize("StatusBar(time=1357 battery=99) $body")
    assertTrue("only the clock/battery changed", DecisionCacheFuzzy.jaccard(a, b) >= 0.85)
  }

  @Test
  fun `different screens are dissimilar`() {
    val a = DecisionCacheFuzzy.tokenize("HomeScreen dock Phone Messages PlayStore Chrome Camera")
    val b = DecisionCacheFuzzy.tokenize("AppStore Today recommended Snake game getButton tabBar search")
    assertTrue(DecisionCacheFuzzy.jaccard(a, b) < 0.3)
  }

  @Test
  fun `contextPart extracts from cacheKey`() {
    val key = "v0.74.0-decision-r3-uitree-123-context-456-screen-789"
    assertEquals("-context-456-screen-789", DecisionCacheFuzzy.contextPart(key))
    assertEquals("", DecisionCacheFuzzy.contextPart("uitree-only-no-marker-1"))
  }

  @Test
  fun `threshold disabled by default and bounded`() {
    // No property/env set in test -> exact-only
    assertEquals(null, DecisionCacheFuzzy.threshold())
    System.setProperty("arbigent.decisionCache.fuzzyThreshold", "0.85")
    try {
      assertEquals(0.85, DecisionCacheFuzzy.threshold()!!, 1e-9)
      System.setProperty("arbigent.decisionCache.fuzzyThreshold", "1.5") // out of range
      assertEquals(null, DecisionCacheFuzzy.threshold())
      System.setProperty("arbigent.decisionCache.fuzzyThreshold", "0") // 0 = exact only
      assertEquals(null, DecisionCacheFuzzy.threshold())
    } finally {
      System.clearProperty("arbigent.decisionCache.fuzzyThreshold")
    }
  }
}
