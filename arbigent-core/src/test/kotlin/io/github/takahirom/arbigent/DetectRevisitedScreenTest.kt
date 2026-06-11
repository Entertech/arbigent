package io.github.takahirom.arbigent

import io.github.takahirom.arbigent.result.ArbigentUiTreeStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectRevisitedScreenTest {
  private fun step(tree: String?, action: ArbigentAgentAction? = null): ArbigentContextHolder.Step {
    return ArbigentContextHolder.Step(
      stepId = "step",
      agentAction = action,
      uiTreeStrings = tree?.let { ArbigentUiTreeStrings(allTreeString = it, optimizedTreeString = it) },
      cacheKey = "key",
      screenshotFilePath = "/tmp/none.png",
    )
  }

  @Test
  fun `no hint on first or second visit`() {
    val home = "HomeScreen(tree)"
    assertNull(revisitedScreenHintOrNull(emptyList(), home))
    // one prior visit (second visit now) — still no hint
    assertNull(revisitedScreenHintOrNull(listOf(step(home), step("OtherScreen")), home))
  }

  @Test
  fun `third visit triggers hint listing tried actions`() {
    val home = "HomeScreen(tree)"
    val steps = listOf(
      step(home, ClickWithIndex(3)),
      step("HealthApp(tree)", GoHomeAgentAction()),
      step(home, ClickAtCoordinates(16, 58)),
      step("HealthApp(tree)", GoHomeAgentAction()),
    )
    val hint = revisitedScreenHintOrNull(steps, home)
    assertNotNull(hint)
    assertTrue(hint!!.contains("LOOP DETECTED"))
    assertTrue(hint.contains("Click on index: 3"))
    assertTrue(hint.contains("Click at coordinates: (16%, 58%)"))
  }

  @Test
  fun `blank trees never match`() {
    assertEquals(emptyList<ArbigentContextHolder.Step>(), findRevisitedSteps(listOf(step(""), step("")), ""))
    assertNull(revisitedScreenHintOrNull(listOf(step(""), step("")), ""))
  }

  @Test
  fun `matches outside window are ignored`() {
    val home = "HomeScreen(tree)"
    val old = List(2) { step(home) }
    val recent = List(8) { step("Other$it") }
    // the two matching visits fell out of the 8-step window
    assertEquals(0, findRevisitedSteps(old + recent, home).size)
  }
}
