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
import java.util.UUID
import java.util.concurrent.TimeUnit

public class CodexCliAiProvider(
  private val codexExecutable: String = DEFAULT_CODEX_EXECUTABLE,
  private val modelName: String? = null,
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
      profile = profile,
      sandbox = sandbox,
      approvalPolicy = approvalPolicy,
      timeoutMs = timeoutMs,
      workingDirectory = workingDirectory,
    )
  }

  public companion object {
    public const val DEFAULT_CODEX_EXECUTABLE: String = "codex"
    public const val DEFAULT_SANDBOX: String = "read-only"
    public const val DEFAULT_APPROVAL_POLICY: String = "never"
    public const val DEFAULT_TIMEOUT_MS: Long = 300_000L
  }
}

internal class CodexCliAi(
  private val codexExecutable: String,
  private val modelName: String?,
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

    val prompt = buildDecisionPrompt(decisionInput)
    val response = runCodexJson(
      requestUuid = decisionInput.requestUuid,
      prompt = prompt,
      screenshotFile = original.toAnnotatedFile(),
      outputSchema = buildDecisionOutputSchema(decisionInput.agentActionTypes, decisionInput.mcpTools),
      apiCallJsonLFile = File(decisionInput.apiCallJsonLFilePath),
    )

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
        aiRequest = prompt,
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
        aiRequest = prompt,
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

  private fun buildDecisionPrompt(decisionInput: ArbigentAi.DecisionInput): String {
    val focusedTreeText = decisionInput.focusedTreeString.orEmpty().ifBlank { "No focused tree" }
    val uiElements = decisionInput.elements.getPromptTexts().ifBlank { "No UI elements to select. Please check the image." }
    val aiOptions = decisionInput.aiOptions ?: ArbigentAiOptions()
    val taskPrompt = decisionInput.contextHolder.prompt(
      uiElements = uiElements,
      focusedTree = focusedTreeText,
      aiOptions = aiOptions,
      aiHints = decisionInput.uiTreeStrings.aiHints,
    )
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
For MCP actions, put tool arguments in "arguments" and keep "text" empty.
Use ClickWithIndex when a visible target exists in ELEMENTS.
Use ClickAtCoordinates only when the target is visible in the screenshot but missing from ELEMENTS/UI hierarchy.
Do not call tools, inspect the local repository, edit files, or ask follow-up questions.
</OUTPUT_CONTRACT>
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
          put("type", "object")
          put("description", "MCP tool arguments or optional provider-specific structured arguments.")
          put("additionalProperties", true)
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
    prompt: String,
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

    val command = buildCodexCommand(
      schemaFile = schemaFile,
      lastMessageFile = lastMessageFile,
      screenshotFile = screenshotFile,
    )
    val processBuilder = ProcessBuilder(command)
      .redirectErrorStream(true)
      .redirectOutput(ProcessBuilder.Redirect.to(processLogFile))
    workingDirectory?.takeIf { it.isNotBlank() }?.let {
      processBuilder.directory(File(it))
    }
    val process = processBuilder.start()
    process.outputStream.bufferedWriter().use { writer ->
      writer.write(prompt)
      writer.newLine()
    }
    val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
    if (!finished) {
      process.destroyForcibly()
      throw IllegalStateException("Codex CLI timed out after ${timeoutMs}ms. Log: ${processLogFile.absolutePath}")
    }
    val exitCode = process.exitValue()
    val stdout = processLogFile.takeIf { it.exists() }?.readText().orEmpty()
    val lastMessage = lastMessageFile.takeIf { it.exists() }?.readText().orEmpty()
    val response = CodexCliResponse(
      command = command,
      exitCode = exitCode,
      stdout = stdout,
      lastMessage = lastMessage,
      outputFile = lastMessageFile.absolutePath,
      logFile = processLogFile.absolutePath,
    )
    apiCallJsonLFile.writeText(json.encodeToString(response).removeConfidentialInfo())
    if (exitCode != 0) {
      throw IllegalStateException("Codex CLI failed with exit code $exitCode. Log: ${processLogFile.absolutePath}")
    }
    if (lastMessage.isBlank()) {
      throw IllegalStateException("Codex CLI did not write a final response. Log: ${processLogFile.absolutePath}")
    }
    return response
  }

  private fun buildCodexCommand(
    schemaFile: File,
    lastMessageFile: File,
    screenshotFile: File,
  ): List<String> {
    return buildList {
      add(codexExecutable)
      add("exec")
      add("--skip-git-repo-check")
      add("--ephemeral")
      add("--ignore-rules")
      add("--disable")
      add("plugins")
      add("--sandbox")
      add(sandbox)
      add("-c")
      add("approval_policy=${tomlString(approvalPolicy)}")
      add("--color")
      add("never")
      modelName?.takeIf { it.isNotBlank() }?.let {
        add("--model")
        add(it)
      }
      profile?.takeIf { it.isNotBlank() }?.let {
        add("--profile")
        add(it)
      }
      workingDirectory?.takeIf { it.isNotBlank() }?.let {
        add("--cd")
        add(it)
      }
      if (screenshotFile.exists()) {
        add("--image")
        add(screenshotFile.absolutePath)
      }
      add("--output-schema")
      add(schemaFile.absolutePath)
      add("--output-last-message")
      add(lastMessageFile.absolutePath)
      add("-")
    }
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

@Serializable
internal data class CodexCliResponse(
  val command: List<String>,
  val exitCode: Int,
  val stdout: String,
  val lastMessage: String,
  val outputFile: String,
  val logFile: String,
)

private fun kotlinx.serialization.json.JsonArrayBuilder.addString(value: String) {
  add(JsonPrimitive(value))
}
