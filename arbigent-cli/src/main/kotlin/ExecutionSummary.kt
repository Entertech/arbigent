@file:OptIn(ArbigentInternalApi::class)

package io.github.takahirom.arbigent.cli

import io.github.takahirom.arbigent.ArbigentHtmlReport
import io.github.takahirom.arbigent.ArbigentInternalApi
import io.github.takahirom.arbigent.ArbigentProject
import io.github.takahirom.arbigent.ArbigentProjectSerializer
import io.github.takahirom.arbigent.ArbigentScenario
import io.github.takahirom.arbigent.result.ArbigentAgentResult
import io.github.takahirom.arbigent.result.ArbigentAgentTaskStepResult
import io.github.takahirom.arbigent.result.ArbigentProjectExecutionResult
import io.github.takahirom.arbigent.result.ArbigentScenarioResult
import java.io.File
import java.util.Locale
import kotlin.math.max

internal fun saveAndPrintExecutionSummary(
  arbigentProject: ArbigentProject,
  scenarios: List<ArbigentScenario>,
  resultFile: File,
  resultDir: File,
): ArbigentProjectExecutionResult {
  val result = arbigentProject.getResult(scenarios)
  ArbigentProjectSerializer().save(result, resultFile)
  ArbigentHtmlReport().saveReportHtml(
    resultDir.absolutePath,
    result,
    needCopy = false,
  )
  val summaryFile = File(resultDir, "summary.txt")
  val summary = buildExecutionSummary(result, resultFile, resultDir, summaryFile)
  summaryFile.writeText(summary + "\n")
  println()
  println(summary)
  return result
}

internal fun buildExecutionSummary(
  result: ArbigentProjectExecutionResult,
  resultFile: File,
  resultDir: File,
  summaryFile: File = File(resultDir, "summary.txt"),
): String {
  val scenarios = result.scenarios
  val isSuccess = scenarios.all { it.isSuccess }
  val histories = scenarios.sumOf { it.histories.size }
  val agents = scenarios.flatMap { scenario -> scenario.histories.flatMap { it.agentResults } }
  val steps = agents.sumOf { it.steps.size }
  val durationText = formatDuration(result.startTimestamp(), result.endTimestamp())
  val lastScenario = scenarios.lastOrNull()
  val lastAgent = agents.lastOrNull()
  val lastStep = lastAgent?.steps?.lastOrNull()
  val lastAction = lastStep?.agentAction ?: if (isSuccess) "Goal achieved" else "None"
  val finalReason = result.finalReason(lastScenario, lastAgent, lastStep)

  return buildString {
    appendLine("Arbigent execution result: ${if (isSuccess) "SUCCESS" else "FAILED"}")
    appendLine("Scenarios: ${scenarios.count { it.isSuccess }}/${scenarios.size} succeeded")
    appendLine("Histories: $histories")
    appendLine("Steps: $steps")
    appendLine("Duration: $durationText")
    appendLine("Last action: ${lastAction.truncateForSummary()}")
    appendLine("Conclusion: ${finalReason.truncateForSummary(300)}")
    appendLine("Results:")
    appendLine("  YAML: ${resultFile.absolutePath}")
    appendLine("  HTML: ${File(resultDir, "report.html").absolutePath}")
    appendLine("  Summary: ${summaryFile.absolutePath}")
  }.trimEnd()
}

private fun ArbigentProjectExecutionResult.finalReason(
  lastScenario: ArbigentScenarioResult?,
  lastAgent: ArbigentAgentResult?,
  lastStep: ArbigentAgentTaskStepResult?,
): String {
  if (scenarios.all { it.isSuccess }) {
    return lastStep?.summary?.firstUsefulLine() ?: "All selected scenarios reached GoalAchieved."
  }
  if (lastAgent == null) {
    return "No agent result was recorded."
  }
  if (!lastAgent.isGoalAchieved && lastAgent.steps.size >= lastAgent.maxStep) {
    return "Scenario '${lastScenario?.id ?: "unknown"}' reached maxStep=${lastAgent.maxStep} before GoalAchieved."
  }
  return lastStep?.summary?.firstUsefulLine()
    ?: lastScenario?.executionStatus
    ?: "At least one selected scenario did not reach GoalAchieved."
}

private fun String.firstUsefulLine(): String {
  return lineSequence()
    .map { it.trim() }
    .firstOrNull { it.isNotBlank() }
    ?: trim()
}

private fun String.truncateForSummary(maxLength: Int = 180): String {
  val compact = replace(Regex("\\s+"), " ").trim()
  if (compact.length <= maxLength) return compact
  return compact.take(max(0, maxLength - 3)) + "..."
}

private fun formatDuration(startTimestamp: Long?, endTimestamp: Long?): String {
  if (startTimestamp == null || endTimestamp == null || endTimestamp < startTimestamp) {
    return "unknown"
  }
  val totalSeconds = (endTimestamp - startTimestamp) / 1000.0
  return "%.1fs".format(Locale.US, totalSeconds)
}
