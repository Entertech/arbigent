package io.github.takahirom.arbigent

import io.github.takahirom.arbigent.ConfidentialInfo.removeConfidentialInfo
import io.github.takahirom.arbigent.result.ArbigentScenarioDeviceFormFactor
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.awt.image.BufferedImage.TYPE_INT_RGB
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

public class CodexCliAiProvider(
  private val codexExecutable: String = DEFAULT_CODEX_EXECUTABLE,
  private val modelName: String? = null,
  private val reasoningEffort: String? = DEFAULT_REASONING_EFFORT,
  private val sessionCacheMode: String = DEFAULT_SESSION_CACHE_MODE,
  private val profile: String? = null,
  private val sandbox: String = DEFAULT_SANDBOX,
  private val approvalPolicy: String = DEFAULT_APPROVAL_POLICY,
  private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
  private val workingDirectory: String? = null,
) : ArbigentAiProvider {
  override val metadata: ArbigentAiProviderMetadata = ArbigentAiProviderMetadata(
    id = "codex",
    label = "Codex CLI",
    runtime = ArbigentAiRuntime(
      id = "codex-cli",
      transport = ArbigentAiTransport.CodexCliExec,
      modelName = modelName,
      source = codexExecutable,
    ),
    capabilities = setOf(
      ArbigentAiCapability.AgentDecision,
      ArbigentAiCapability.VisionInput,
    ),
  )

  override fun createAi(): ArbigentAi {
    return CodexCliAi(
      codexExecutable = codexExecutable,
      modelName = modelName,
      reasoningEffort = reasoningEffort,
      sessionCacheMode = sessionCacheMode,
      profile = profile,
      sandbox = sandbox,
      approvalPolicy = approvalPolicy,
      timeoutMs = timeoutMs,
      workingDirectory = workingDirectory,
    )
  }

  public companion object {
    public const val DEFAULT_CODEX_EXECUTABLE: String = "codex"
    public const val DEFAULT_REASONING_EFFORT: String = "low"
    // Default to stateless `off`. A resumed Codex session retains every prior
    // turn's screenshot + UI tree server-side, so per-step latency grows with
    // task length (measured 27s→89s over 14 steps). `off` sends a self-contained
    // prompt (bounded text history + only the current screenshot) each call, so
    // latency stays flat (~23s avg, 33s max on the same task — ~2x faster total
    // and no degradation). `auto`/`schema-only` remain available for callers that
    // prefer server-side session continuity.
    public const val DEFAULT_SESSION_CACHE_MODE: String = "off"
    public const val DEFAULT_SANDBOX: String = "read-only"
    public const val DEFAULT_APPROVAL_POLICY: String = "never"
    public const val DEFAULT_TIMEOUT_MS: Long = 300_000L
  }
}

internal class CodexCliAi(
  private val codexExecutable: String,
  private val modelName: String?,
  private val reasoningEffort: String?,
  private val sessionCacheMode: String,
  private val profile: String?,
  private val sandbox: String,
  private val approvalPolicy: String,
  private val timeoutMs: Long,
  private val workingDirectory: String?,
) : ArbigentAi {
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }
  private var sessionId: String? = null
  private val normalizedSessionCacheMode: String = sessionCacheMode.lowercase()
  private val resumeSupportsOutputSchema: Boolean by lazy {
    codexResumeHelp().contains("--output-schema")
  }

  override fun generateScenarios(
    scenarioGenerationInput: ArbigentAi.ScenarioGenerationInput
  ): GeneratedScenariosContent {
    throw UnsupportedOperationException(
      "Codex CLI provider does not support scenario generation yet. Use an OpenAI-compatible provider for scenario generation."
    )
  }

  override fun decideAgentActions(decisionInput: ArbigentAi.DecisionInput): ArbigentAi.DecisionOutput {
    val original = File(decisionInput.screenshotFilePath)
    val canvas = ArbigentCanvas.load(original, decisionInput.elements.screenWidth, TYPE_INT_RGB)
    canvas.draw(decisionInput.elements)
    canvas.save(original.getAnnotatedFilePath(), decisionInput.aiOptions)

    val fullPrompt = buildDecisionPrompt(decisionInput, resumedPrompt = false)
    val resumedPrompt = buildDecisionPrompt(decisionInput, resumedPrompt = true)
    val response = try {
      runCodexJson(
        requestUuid = decisionInput.requestUuid,
        fullPrompt = fullPrompt,
        resumedPrompt = resumedPrompt,
        screenshotFile = original.toAnnotatedFile(),
        outputSchema = buildDecisionOutputSchema(decisionInput.agentActionTypes, decisionInput.mcpTools),
        apiCallJsonLFile = File(decisionInput.apiCallJsonLFilePath),
      )
    } catch (e: Exception) {
      // Mirror the OpenAI provider: record a failed step (with the current
      // screenshot and UI tree) before propagating a hard Codex failure
      // (timeout / non-zero exit / empty message), so the run's last step and
      // its context are not lost.
      decisionInput.contextHolder.addStep(
        ArbigentContextHolder.Step(
          stepId = decisionInput.stepId,
          agentAction = FailedAgentAction(),
          feedback = "Failed to get a decision from Codex CLI: ${e.message}.",
          cacheKey = decisionInput.cacheKey,
          screenshotFilePath = decisionInput.screenshotFilePath,
          uiTreeStrings = decisionInput.uiTreeStrings,
        )
      )
      throw e
    }

    val step = try {
      val responseObj = parseJsonObject(response.lastMessage)
      val action = responseObj["action"]?.jsonPrimitive?.content
        ?: throw IllegalArgumentException("Action not found in Codex response")
      val arguments = AgentActionJsonParser.normalizeArguments(responseObj)
      val agentAction = AgentActionJsonParser.parseAgentAction(
        agentActionList = decisionInput.agentActionTypes,
        action = action,
        argumentsJsonData = arguments,
        elements = decisionInput.elements,
        mcpTools = decisionInput.mcpTools,
      )
      ArbigentContextHolder.Step(
        stepId = decisionInput.stepId,
        agentAction = agentAction,
        action = action,
        imageDescription = arguments[ArbigentAiAnswerItems.ImageDescription.key]?.jsonPrimitive?.content ?: "",
        memo = arguments[ArbigentAiAnswerItems.Memo.key]?.jsonPrimitive?.content ?: "",
        aiRequest = response.prompt,
        aiResponse = response.lastMessage,
        screenshotFilePath = decisionInput.screenshotFilePath,
        apiCallJsonLFilePath = decisionInput.apiCallJsonLFilePath,
        uiTreeStrings = decisionInput.uiTreeStrings,
        cacheKey = decisionInput.cacheKey,
      )
    } catch (e: Exception) {
      ArbigentContextHolder.Step(
        stepId = decisionInput.stepId,
        feedback = "Failed to parse Codex response: ${e.message}",
        screenshotFilePath = decisionInput.screenshotFilePath,
        aiRequest = response.prompt,
        aiResponse = response.lastMessage,
        uiTreeStrings = decisionInput.uiTreeStrings,
        cacheKey = decisionInput.cacheKey,
      )
    }
    return ArbigentAi.DecisionOutput(listOfNotNull(step.agentAction), step)
  }

  override fun assertImage(imageAssertionInput: ArbigentAi.ImageAssertionInput): ArbigentAi.ImageAssertionOutput {
    throw UnsupportedOperationException(
      "Codex CLI provider does not support Arbigent image assertions yet. Use an OpenAI-compatible provider when imageAssertions are required."
    )
  }

  private fun buildDecisionPrompt(decisionInput: ArbigentAi.DecisionInput, resumedPrompt: Boolean): String {
    val focusedTreeText = decisionInput.focusedTreeString.orEmpty().ifBlank { "No focused tree" }
    val uiElements = decisionInput.elements.getPromptTexts().ifBlank { "No UI elements to select. Please check the image." }
    val aiOptions = decisionInput.aiOptions ?: ArbigentAiOptions()
    val taskPrompt = if (resumedPrompt) {
      buildResumedTaskPrompt(
        decisionInput = decisionInput,
        uiElements = uiElements,
        focusedTreeText = focusedTreeText,
      )
    } else {
      decisionInput.contextHolder.prompt(
        uiElements = uiElements,
        focusedTree = focusedTreeText,
        aiOptions = aiOptions,
        aiHints = decisionInput.uiTreeStrings.aiHints,
      )
    }
    val systemPrompts = when (decisionInput.formFactor) {
      ArbigentScenarioDeviceFormFactor.Tv -> decisionInput.prompt.systemPromptsForTv
      else -> decisionInput.prompt.systemPrompts
    } + decisionInput.prompt.additionalSystemPrompts
    val actionDescriptions = decisionInput.agentActionTypes.joinToString("\n") { actionType ->
      val args = actionType.arguments().joinToString(", ") { argument ->
        "${argument.name}: ${argument.type} (${argument.description})"
      }.ifBlank { "no arguments" }
      "- ${actionType.actionName}: ${actionType.actionDescription()} Args: $args"
    }
    val mcpDescriptions = decisionInput.mcpTools.orEmpty().joinToString("\n") { tool ->
      "- mcp_${tool.name}: ${tool.description}"
    }.ifBlank { "- none" }

    return """
You are the Codex-backed AI provider for Arbigent.

<SYSTEM_PROMPTS>
${systemPrompts.joinToString("\n")}
</SYSTEM_PROMPTS>

$taskPrompt

<AVAILABLE_ACTIONS>
$actionDescriptions
</AVAILABLE_ACTIONS>

<AVAILABLE_MCP_TOOLS>
$mcpDescriptions
</AVAILABLE_MCP_TOOLS>

<OUTPUT_CONTRACT>
Return exactly one JSON object matching the provided schema.
Choose one action from AVAILABLE_ACTIONS, or one mcp_<toolName> from AVAILABLE_MCP_TOOLS.
For ordinary Arbigent actions, put the primary argument in "text". Use an empty string when the action has no argument.
Set "arguments" to "{}" unless selecting an MCP action.
For MCP actions, put tool arguments in "arguments" as a compact JSON object string and keep "text" empty.
Use ClickWithIndex when a visible target exists in ELEMENTS.
Use ClickAtCoordinates only when the target is visible in the screenshot but missing from ELEMENTS/UI hierarchy. Its coordinates are normalized "nx,ny" fractions in [0,1] (top-left origin, screen center = "0.5,0.5").
If a visible target is only partially visible or close to a screen edge, navigation bar, or tab bar, use Scroll or Swipe to center it before clicking.
To read or advance through long content or lists (e.g. reviews, settings, search results), use Scroll, NOT Swipe: on iOS a Swipe inside a modal sheet (such as an App Store product page) can dismiss the sheet and send you back to the previous screen.
Before choosing GoalAchieved, verify every explicit constraint in GOAL and write the evidence in arbigent-memo. If the current screen is a plausible but unverified leftover from a previous task, continue navigating instead of finishing.
Do not call tools, inspect the local repository, edit files, or ask follow-up questions.
</OUTPUT_CONTRACT>
""".trimIndent()
  }

  private fun buildResumedTaskPrompt(
    decisionInput: ArbigentAi.DecisionInput,
    uiElements: String,
    focusedTreeText: String,
  ): String {
    val contextHolder = decisionInput.contextHolder
    val currentStep = contextHolder.countMeaningfulActions() + 1
    val lastStepText = contextHolder.steps().lastOrNull()?.text()?.ifBlank { null } ?: "No previous step in Arbigent state."
    val aiHintsText = decisionInput.uiTreeStrings.aiHints.takeIf { it.isNotEmpty() }
      ?.joinToString(separator = "\n", prefix = "<AI_HINTS>\n", postfix = "\n</AI_HINTS>")
      .orEmpty()
    return """
<GOAL>${contextHolder.goal}</GOAL>
$aiHintsText
<CACHED_CONTEXT>
You are continuing the same Arbigent task in a resumed Codex session.
Earlier turns in this session contain the previous screenshots, UI states, decisions, and action results.
Use that session history for long-term context, but treat the UI state below as the only current screen.
Element indexes can change after every action; only use indexes from the current ELEMENTS block.
</CACHED_CONTEXT>

<STEP>
Current step: $currentStep
Step limit: ${contextHolder.maxStep}

<LAST_RECORDED_STEP>
$lastStepText
</LAST_RECORDED_STEP>
</STEP>

<UI_STATE>
Please refer to the image.
<ELEMENTS>
index:element
$uiElements
</ELEMENTS>
<FOCUSED_TREE>
$focusedTreeText
</FOCUSED_TREE>
</UI_STATE>

<INSTRUCTIONS>
Based on the current screen and the cached session history, decide on the next action to achieve the goal.
Do not repeat actions that the session history already showed as ineffective.
Only use GoalAchieved when the current screen or earlier session turns prove every explicit goal constraint, not just the broad screen type. Verify named entities, source/context, ordinal/count requirements, and requested target/content, then cite that evidence in the memo before finishing.
</INSTRUCTIONS>
""".trimIndent()
  }

  private fun buildDecisionOutputSchema(
    agentActionTypes: List<AgentActionType>,
    mcpTools: List<MCPTool>?,
  ): JsonObject {
    val actionNames = agentActionTypes.map { it.actionName } + mcpTools.orEmpty().map { "mcp_${it.name}" }
    return buildJsonObject {
      put("type", "object")
      put("additionalProperties", false)
      putJsonArray("required") {
        addString("action")
        addString("text")
        addString("arguments")
        addString(ArbigentAiAnswerItems.Memo.key)
        addString(ArbigentAiAnswerItems.ImageDescription.key)
      }
      putJsonObject("properties") {
        putJsonObject("action") {
          put("type", "string")
          putJsonArray("enum") {
            actionNames.forEach { addString(it) }
          }
        }
        putJsonObject("text") {
          put("type", "string")
          put("description", "Primary action argument, or empty string for no-argument and MCP actions.")
        }
        putJsonObject("arguments") {
          put("type", "string")
          put("description", "Compact JSON object string for MCP tool arguments, or \"{}\" for ordinary Arbigent actions.")
        }
        putJsonObject(ArbigentAiAnswerItems.Memo.key) {
          put("type", "string")
          put("description", "Brief reasoning memo for the selected action.")
        }
        putJsonObject(ArbigentAiAnswerItems.ImageDescription.key) {
          put("type", "string")
          put("description", "Brief description of the visible screen.")
        }
      }
    }
  }

  private fun runCodexJson(
    requestUuid: String,
    fullPrompt: String,
    resumedPrompt: String,
    screenshotFile: File,
    outputSchema: JsonObject,
    apiCallJsonLFile: File,
  ): CodexCliResponse {
    apiCallJsonLFile.parentFile.mkdirs()
    val requestId = requestUuid.ifBlank { UUID.randomUUID().toString() }
    val schemaFile = File(apiCallJsonLFile.parentFile, "${apiCallJsonLFile.nameWithoutExtension}-$requestId.schema.json")
    val lastMessageFile = File(apiCallJsonLFile.parentFile, "${apiCallJsonLFile.nameWithoutExtension}-$requestId.codex-response.json")
    val processLogFile = File(apiCallJsonLFile.parentFile, "${apiCallJsonLFile.nameWithoutExtension}-$requestId.codex.log")
    schemaFile.writeText(json.encodeToString(outputSchema))

    val requestedResume = shouldResume()
    val requestedSchemaEnforced = !requestedResume || resumeSupportsOutputSchema
    val requestedPrompt = if (requestedResume) resumedPrompt else fullPrompt
    val requestedCommand = buildCodexCommand(
      schemaFile = schemaFile,
      lastMessageFile = lastMessageFile,
      screenshotFile = screenshotFile,
      resumeSessionId = sessionId.takeIf { requestedResume },
      includeOutputSchema = requestedSchemaEnforced,
    )
    var attempt = runCodexAttempt(requestedCommand, processLogFile, lastMessageFile, requestedPrompt)
    var usedResume = requestedResume
    var schemaEnforced = requestedSchemaEnforced
    var resumeFallbackReason: String? = null
    if (requestedResume && !attempt.isUsable()) {
      resumeFallbackReason = attempt.failureReason()
      arbigentWarnLog("Codex resume failed (${resumeFallbackReason}); falling back to fresh schema-enforced exec")
      sessionId = null
      val fallbackCommand = buildCodexCommand(
        schemaFile = schemaFile,
        lastMessageFile = lastMessageFile,
        screenshotFile = screenshotFile,
        resumeSessionId = null,
        includeOutputSchema = true,
      )
      attempt = runCodexAttempt(fallbackCommand, processLogFile, lastMessageFile, fullPrompt)
      usedResume = false
      schemaEnforced = true
    }
    val stdout = attempt.stdout
    val lastMessage = attempt.lastMessage
    val responseSessionId = extractSessionId(stdout)
    responseSessionId?.let { sessionId = it }
    val response = CodexCliResponse(
      command = attempt.command,
      exitCode = attempt.exitCode,
      stdout = stdout,
      lastMessage = lastMessage,
      outputFile = lastMessageFile.absolutePath,
      logFile = processLogFile.absolutePath,
      schemaFile = schemaFile.absolutePath,
      screenshotFile = screenshotFile.absolutePath,
      startedAt = attempt.startedAt,
      finishedAt = attempt.finishedAt,
      durationMs = attempt.durationMs,
      modelName = modelName,
      reasoningEffort = reasoningEffort,
      sessionCacheMode = normalizedSessionCacheMode,
      sessionId = sessionId,
      resumed = usedResume,
      schemaEnforced = schemaEnforced,
      resumeFallbackReason = resumeFallbackReason,
      prompt = attempt.prompt,
    )
    apiCallJsonLFile.writeText(json.encodeToString(response).removeConfidentialInfo())
    if (attempt.timedOut) {
      throw IllegalStateException("Codex CLI timed out after ${timeoutMs}ms. Log: ${processLogFile.absolutePath}")
    }
    if (attempt.exitCode != 0) {
      throw IllegalStateException("Codex CLI failed with exit code ${attempt.exitCode}. Log: ${processLogFile.absolutePath}")
    }
    if (lastMessage.isBlank()) {
      throw IllegalStateException("Codex CLI did not write a final response. Log: ${processLogFile.absolutePath}")
    }
    arbigentInfoLog(
      "Codex CLI decision completed in ${attempt.durationMs}ms " +
        "(reasoningEffort=${reasoningEffort ?: "default"}, sessionCache=$normalizedSessionCacheMode, resumed=$usedResume, schema=$schemaEnforced)"
    )
    return response
  }

  private fun buildCodexCommand(
    schemaFile: File,
    lastMessageFile: File,
    screenshotFile: File,
    resumeSessionId: String?,
    includeOutputSchema: Boolean,
  ): List<String> {
    return buildList {
      add(codexExecutable)
      add("exec")
      if (resumeSessionId != null) {
        add("resume")
      }
      add("--skip-git-repo-check")
      add("--ignore-rules")
      add("--disable")
      add("plugins")
      if (resumeSessionId == null) {
        if (!isSessionCacheEnabled()) {
          add("--ephemeral")
        }
        add("--sandbox")
        add(sandbox)
      }
      add("-c")
      add("approval_policy=${tomlString(approvalPolicy)}")
      reasoningEffort?.takeIf { it.isNotBlank() }?.let {
        add("-c")
        add("model_reasoning_effort=${tomlString(it)}")
      }
      if (resumeSessionId == null) {
        add("--color")
        add("never")
      }
      modelName?.takeIf { it.isNotBlank() }?.let {
        add("--model")
        add(it)
      }
      profile?.takeIf { it.isNotBlank() }?.let {
        if (resumeSessionId == null) {
          add("--profile")
          add(it)
        }
      }
      workingDirectory?.takeIf { it.isNotBlank() }?.let {
        if (resumeSessionId == null) {
          add("--cd")
          add(it)
        }
      }
      if (screenshotFile.exists()) {
        add("--image")
        add(screenshotFile.absolutePath)
      }
      if (includeOutputSchema) {
        add("--output-schema")
        add(schemaFile.absolutePath)
      }
      add("--output-last-message")
      add(lastMessageFile.absolutePath)
      resumeSessionId?.let { add(it) }
      add("-")
    }
  }

  private fun runCodexAttempt(
    command: List<String>,
    processLogFile: File,
    lastMessageFile: File,
    prompt: String,
  ): CodexAttemptResult {
    val startedAt = TimeProvider.get().currentTimeMillis()
    val processResult = runCodexProcess(command, processLogFile, prompt)
    val finishedAt = TimeProvider.get().currentTimeMillis()
    return CodexAttemptResult(
      command = command,
      prompt = prompt,
      exitCode = processResult.exitCode,
      timedOut = processResult.timedOut,
      stdout = processLogFile.takeIf { it.exists() }?.readText().orEmpty(),
      lastMessage = lastMessageFile.takeIf { it.exists() }?.readText().orEmpty(),
      startedAt = startedAt,
      finishedAt = finishedAt,
    )
  }

  private fun runCodexProcess(command: List<String>, processLogFile: File, prompt: String): CodexProcessResult {
    val processBuilder = ProcessBuilder(command)
      .redirectErrorStream(true)
      .redirectOutput(ProcessBuilder.Redirect.to(processLogFile))
    workingDirectory?.takeIf { it.isNotBlank() }?.let {
      processBuilder.directory(File(it))
    }
    val process = processBuilder.start()
    try {
      process.outputStream.bufferedWriter().use { writer ->
        writer.write(prompt)
        writer.newLine()
      }
    } catch (e: IOException) {
      // The Codex process can exit before consuming stdin (broken pipe). Don't
      // surface a raw IOException here; fall through to waitFor so the exit-code
      // / timeout path reports a clean Codex failure that callers can annotate.
      arbigentInfoLog("Failed to write prompt to Codex stdin (process may have exited early): ${e.message}")
    }
    val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
    if (!finished) {
      process.destroyForcibly()
      return CodexProcessResult(exitCode = -1, timedOut = true)
    }
    return CodexProcessResult(exitCode = process.exitValue(), timedOut = false)
  }

  private fun shouldResume(): Boolean {
    if (!isSessionCacheEnabled()) return false
    val currentSessionId = sessionId ?: return false
    if (currentSessionId.isBlank()) return false
    if (normalizedSessionCacheMode == SESSION_CACHE_SCHEMA_ONLY && !resumeSupportsOutputSchema) return false
    return true
  }

  private fun isSessionCacheEnabled(): Boolean {
    return normalizedSessionCacheMode != SESSION_CACHE_OFF
  }

  private fun codexResumeHelp(): String {
    return try {
      val process = ProcessBuilder(codexExecutable, "exec", "resume", "--help")
        .redirectErrorStream(true)
        .start()
      val output = process.inputStream.bufferedReader().readText()
      process.waitFor(5, TimeUnit.SECONDS)
      output
    } catch (_: Exception) {
      ""
    }
  }

  private fun extractSessionId(stdout: String): String? {
    return SESSION_ID_REGEX.find(stdout)?.groupValues?.getOrNull(1)
  }

  private fun tomlString(value: String): String {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
  }

  private fun parseJsonObject(text: String): JsonObject {
    return try {
      json.parseToJsonElement(text).jsonObject
    } catch (e: Exception) {
      val start = text.indexOf('{')
      val end = text.lastIndexOf('}')
      if (start >= 0 && end > start) {
        json.parseToJsonElement(text.substring(start, end + 1)).jsonObject
      } else {
        throw e
      }
    }
  }

}

private data class CodexProcessResult(
  val exitCode: Int,
  val timedOut: Boolean,
)

private data class CodexAttemptResult(
  val command: List<String>,
  val prompt: String,
  val exitCode: Int,
  val timedOut: Boolean,
  val stdout: String,
  val lastMessage: String,
  val startedAt: Long,
  val finishedAt: Long,
) {
  val durationMs: Long get() = finishedAt - startedAt

  fun isUsable(): Boolean {
    return !timedOut && exitCode == 0 && lastMessage.isNotBlank()
  }

  fun failureReason(): String {
    return when {
      timedOut -> "timeout"
      exitCode != 0 -> "exitCode=$exitCode"
      lastMessage.isBlank() -> "empty-last-message"
      else -> "unknown"
    }
  }
}

@Serializable
internal data class CodexCliResponse(
  val command: List<String>,
  val exitCode: Int,
  val stdout: String,
  val lastMessage: String,
  val outputFile: String,
  val logFile: String,
  val schemaFile: String,
  val screenshotFile: String,
  val startedAt: Long,
  val finishedAt: Long,
  val durationMs: Long,
  val modelName: String?,
  val reasoningEffort: String?,
  val sessionCacheMode: String,
  val sessionId: String?,
  val resumed: Boolean,
  val schemaEnforced: Boolean,
  val resumeFallbackReason: String?,
  val prompt: String,
)

private const val SESSION_CACHE_OFF = "off"
private const val SESSION_CACHE_SCHEMA_ONLY = "schema-only"
private val SESSION_ID_REGEX = Regex("""(?m)^session id:\s*([0-9a-fA-F-]{36})\s*$""")

private fun kotlinx.serialization.json.JsonArrayBuilder.addString(value: String) {
  add(JsonPrimitive(value))
}
