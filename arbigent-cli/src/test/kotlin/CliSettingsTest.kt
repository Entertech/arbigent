package io.github.takahirom.arbigent.cli

import io.github.takahirom.arbigent.IosRealXCTestDeviceConfig
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.io.path.createTempDirectory

class CliSettingsTest {
  @Test
  fun `loadHostSettings uses command-specific keys before global keys in the same file`() {
    val tempDir = createTempDirectory().toFile()
    val arbigentDir = File(tempDir, ".arbigent").also { it.mkdirs() }
    File(arbigentDir, "settings.local.yml").writeText(
      """
      ios-xctest-apple-team-id: GLOBALTEAM
      run:
        task:
          ios-xctest-apple-team-id: TASKTEAM
      """.trimIndent()
    )

    val settings = loadHostSettings(
      commandPathCandidates = listOf("run.task", "task", "run"),
      baseDir = tempDir,
    )

    assertEquals("TASKTEAM", settings[IosRealXCTestDeviceConfig.APPLE_TEAM_ID_SETTING])
  }

  @Test
  fun `loadHostSettings keeps higher priority file global keys before lower priority command keys`() {
    val tempDir = createTempDirectory().toFile()
    val arbigentDir = File(tempDir, ".arbigent").also { it.mkdirs() }
    File(arbigentDir, "settings.local.yml").writeText(
      """
      ios-xctest-apple-team-id: LOCALGLOBAL
      """.trimIndent()
    )
    File(arbigentDir, "settings.yml").writeText(
      """
      run:
        ios-xctest-apple-team-id: LOWERCOMMAND
      """.trimIndent()
    )

    val settings = loadHostSettings(
      commandPathCandidates = listOf("run"),
      baseDir = tempDir,
    )

    assertEquals("LOCALGLOBAL", settings[IosRealXCTestDeviceConfig.APPLE_TEAM_ID_SETTING])
  }
}
