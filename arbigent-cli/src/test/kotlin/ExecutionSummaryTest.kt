package io.github.takahirom.arbigent.cli

import io.github.takahirom.arbigent.result.ArbigentAgentResult
import io.github.takahirom.arbigent.result.ArbigentAgentResults
import io.github.takahirom.arbigent.result.ArbigentAgentTaskStepResult
import io.github.takahirom.arbigent.result.ArbigentStepSource
import io.github.takahirom.arbigent.result.ArbigentProjectExecutionResult
import io.github.takahirom.arbigent.result.ArbigentScenarioDeviceFormFactor
import io.github.takahirom.arbigent.result.ArbigentScenarioResult
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.io.path.createTempDirectory

class ExecutionSummaryTest {
  @Test
  fun `summary separates decision cache and codex session diagnostics`() {
    val tempDir = createTempDirectory("arbigent-summary").toFile()
    val firstCodexLog = tempDir.resolve("first.jsonl").apply {
      writeText(
        """{"durationMs":1000,"resumed":false,"schemaEnforced":true,"sessionCacheMode":"auto"}"""
      )
    }
    val secondCodexLog = tempDir.resolve("second.jsonl").apply {
      writeText(
        """{"durationMs":2000,"resumed":true,"schemaEnforced":true,"sessionCacheMode":"auto"}"""
      )
    }
    val result = ArbigentProjectExecutionResult(
      scenarios = listOf(
        ArbigentScenarioResult(
          id = "task",
          goal = "inspect app store review",
          isSuccess = true,
          histories = listOf(
            ArbigentAgentResults(
              status = "History 1 / 1",
              agentResults = listOf(
                ArbigentAgentResult(
                  goal = "inspect app store review",
                  maxStep = 20,
                  deviceFormFactor = ArbigentScenarioDeviceFormFactor.Mobile,
                  isGoalAchieved = true,
                  deviceName = "iPhone",
                  startTimestamp = 1000L,
                  endTimestamp = 7000L,
                  steps = listOf(
                    step(
                      stepId = "step-1",
                      apiCallJsonPath = firstCodexLog.absolutePath,
                      timestamp = 3000L,
                      stepSource = ArbigentStepSource.Cache,
                    ),
                    step(
                      stepId = "step-2",
                      apiCallJsonPath = secondCodexLog.absolutePath,
                      timestamp = 7000L,
                      stepSource = ArbigentStepSource.Ai,
                    ),
                  ),
                )
              ),
            )
          ),
        )
      )
    )

    val summary = buildExecutionSummary(
      result = result,
      resultFile = tempDir.resolve("result.yml"),
      resultDir = tempDir,
    )

    assertContains(summary, "Arbigent execution result: SUCCESS")
    assertContains(summary, "Decision cache: 1/2 hits (Arbigent replay cache)")
    assertContains(summary, "Codex session: mode=auto, resumed=1/2, schema=2/2")
    assertContains(summary, "Codex time: 3.0s total, 1.5s avg, 2.0s max")
    assertContains(summary, "Non-model time: ~3.0s")
  }

  private fun step(
    stepId: String,
    apiCallJsonPath: String,
    timestamp: Long,
    stepSource: ArbigentStepSource,
  ): ArbigentAgentTaskStepResult {
    return ArbigentAgentTaskStepResult(
      stepId = stepId,
      summary = "image description: screen\naction done: Goal achieved\n",
      screenshotFilePath = "$stepId.png",
      apiCallJsonPath = apiCallJsonPath,
      agentAction = "Goal achieved",
      timestamp = timestamp,
      stepSource = stepSource,
    )
  }
}
