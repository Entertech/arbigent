package io.github.takahirom.arbigent

import io.github.takahirom.arbigent.result.ArbigentUiTreeStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagerModeTest {
  private fun step(tree: String?, action: ArbigentAgentAction? = null): ArbigentContextHolder.Step =
    ArbigentContextHolder.Step(
      stepId = "step",
      agentAction = action,
      uiTreeStrings = tree?.let { ArbigentUiTreeStrings(allTreeString = it, optimizedTreeString = it) },
      cacheKey = "key",
      screenshotFilePath = "/tmp/none.png",
    )

  @Test
  fun `disabled by default, enabled by property`() {
    assertEquals(false, ManagerMode.enabled())
    System.setProperty("arbigent.managerMode", "true")
    try {
      assertTrue(ManagerMode.enabled())
      System.setProperty("arbigent.managerMode", "off")
      assertEquals(false, ManagerMode.enabled())
    } finally {
      System.clearProperty("arbigent.managerMode")
    }
  }

  @Test
  fun `no hint below repeat threshold`() {
    val s = listOf(step("AppPage", ClickWithIndex(2)), step("AppPage", ClickWithIndex(2)))
    assertNull(ManagerMode.interventionHintOrNull(s))
  }

  @Test
  fun `same action on unchanged screen 3x triggers the stall hint`() {
    val s = List(3) { step("AppDetail(scroll-stuck)", ClickWithIndex(13)) }
    val hint = ManagerMode.interventionHintOrNull(s)
    assertNotNull(hint)
    assertTrue(hint!!.contains("PROGRESS STALL"))
    assertTrue(hint.contains("Click on index: 13"))
  }

  @Test
  fun `productive scrolling (action repeats but tree changes) does NOT fire`() {
    val s = listOf(
      step("ReviewsPage(row1..5)", ClickWithIndex(9)),
      step("ReviewsPage(row6..10)", ClickWithIndex(9)),
      step("ReviewsPage(row11..15)", ClickWithIndex(9)),
    )
    assertNull(ManagerMode.interventionHintOrNull(s))
  }

  @Test
  fun `blank tree never fires`() {
    val s = List(3) { step("", ClickWithIndex(1)) }
    assertNull(ManagerMode.interventionHintOrNull(s))
  }

  @Test
  fun `run must be the latest steps - a change before the tail breaks it`() {
    val s = listOf(
      step("AppDetail", ClickWithIndex(13)),
      step("AppDetail", ClickWithIndex(13)),
      step("OtherScreen", GoHomeAgentAction()), // breaks the run at the tail
      step("AppDetail", ClickWithIndex(13)),
    )
    assertNull(ManagerMode.interventionHintOrNull(s))
  }
}
