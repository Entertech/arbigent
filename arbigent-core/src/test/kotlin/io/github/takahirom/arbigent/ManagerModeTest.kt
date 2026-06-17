package io.github.takahirom.arbigent

import io.github.takahirom.arbigent.result.ArbigentUiTreeStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagerModeTest {
  private fun step(
    tree: String?,
    action: ArbigentAgentAction? = null,
    desc: String? = null,
  ): ArbigentContextHolder.Step =
    ArbigentContextHolder.Step(
      stepId = "step",
      agentAction = action,
      imageDescription = desc,
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

  // --- Signal A: frozen-screen action grind ---

  @Test
  fun `no hint below repeat threshold`() {
    val s = listOf(step("AppPage", ClickWithIndex(2)), step("AppPage", ClickWithIndex(2)))
    assertNull(ManagerMode.interventionHintOrNull(s))
  }

  @Test
  fun `same action on unchanged screen 3x triggers the stall hint`() {
    val s = List(3) { step("AppDetail(frozen)", ClickWithIndex(13)) }
    val hint = ManagerMode.interventionHintOrNull(s)
    assertNotNull(hint)
    assertTrue(hint!!.contains("PROGRESS STALL"))
    assertTrue(hint.contains("Click on index: 13"))
  }

  @Test
  fun `run must be the latest steps - a change at the tail breaks it`() {
    val s = listOf(
      step("AppDetail", ClickWithIndex(13)),
      step("AppDetail", ClickWithIndex(13)),
      step("OtherScreen", GoHomeAgentAction()),
      step("AppDetail", ClickWithIndex(13)),
    )
    assertNull(ManagerMode.interventionHintOrNull(s))
  }

  // --- Signal B: same-screen dwell despite changing tree (the qwen case) ---

  @Test
  fun `stuck on the same screen for many steps fires despite changing trees and varied actions`() {
    // The real qwen failure: scroll-position tree churns every step, actions vary,
    // but the model keeps describing the SAME app page. Tree-hash methods miss it.
    val desc = "The screen shows the Google Play Store page for the app Peacock TV with screenshots and an about section"
    val s = listOf(
      step("tree-scrollpos-1", ClickWithIndex(4), desc = desc),
      step("tree-scrollpos-2", ScrollAgentAction(), desc = "$desc and ratings"),
      step("tree-scrollpos-3", ScrollAgentAction(), desc = "$desc and reviews teaser"),
      step("tree-scrollpos-4", ClickWithIndex(10), desc = desc),
      step("tree-scrollpos-5", ScrollAgentAction(), desc = "$desc shown again"),
    )
    val hint = ManagerMode.interventionHintOrNull(s)
    assertNotNull(hint)
    assertTrue(hint!!.contains("PROGRESS STALL"))
    assertTrue(hint.contains("same screen"))
  }

  @Test
  fun `genuinely progressing through different screens does NOT fire`() {
    val s = listOf(
      step("t1", ClickWithIndex(2), desc = "Home screen with app icons and a search bar"),
      step("t2", ClickWithIndex(3), desc = "Play Store top charts ranked list of apps"),
      step("t3", ScrollAgentAction(), desc = "Peacock TV app detail page with install button"),
      step("t4", ClickWithIndex(9), desc = "Ratings and reviews page with individual user reviews"),
      step("t5", ScrollAgentAction(), desc = "Reviews list showing the fifth reviewer and comment"),
    )
    assertNull(ManagerMode.interventionHintOrNull(s))
  }

  @Test
  fun `blank trees and blank descriptions never fire`() {
    assertNull(ManagerMode.interventionHintOrNull(List(5) { step("", ClickWithIndex(1)) }))
    assertNull(ManagerMode.interventionHintOrNull(List(5) { step(null, ScrollAgentAction(), desc = "") }))
  }
}
