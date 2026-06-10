package io.github.takahirom.arbigent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundPackageParserTest {
  @Test
  fun `parses mCurrentFocus line from real Pixel dumpsys output`() {
    val out =
      "  mCurrentFocus=Window{ed2bb9f u0 com.google.android.apps.nexuslauncher/com.google.android.apps.nexuslauncher.NexusLauncherActivity}\n" +
        "  mFocusedApp=ActivityRecord{17c0837 u0 com.google.android.apps.nexuslauncher/.NexusLauncherActivity} t328}"
    assertEquals("com.google.android.apps.nexuslauncher", parseForegroundPackage(out))
  }

  @Test
  fun `parses mFocusedApp when mCurrentFocus is absent`() {
    val out = "  mFocusedApp=ActivityRecord{17c0837 u0 com.android.vending/.AssetBrowserActivity} t328}"
    assertEquals("com.android.vending", parseForegroundPackage(out))
  }

  @Test
  fun `parses ResumedActivity form`() {
    val out = "    ResumedActivity: ActivityRecord{5c2a1f u0 com.example.app/.MainActivity t12}"
    assertEquals("com.example.app", parseForegroundPackage(out))
  }

  @Test
  fun `returns null for unmatched output`() {
    assertNull(parseForegroundPackage(""))
    assertNull(parseForegroundPackage("  mCurrentFocus=null"))
  }
}
