package io.github.takahirom.arbigent

import io.github.takahirom.arbigent.result.ArbigentScenarioDeviceFormFactor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.awt.image.BufferedImage.TYPE_INT_RGB
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.UUID

/**
 * Direct-HTTP Codex provider that reuses the local ChatGPT subscription
 * (the `codex login` credentials) by POSTing to the ChatGPT backend Responses
 * API, instead of spawning `codex exec` per step. Removes the per-step process
 * spawn and enables prompt caching via `prompt_cache_key`.
 *
 * Decision shape mirrors the Codex CLI provider: a strict JSON envelope
 * ({action,text,arguments,...}) parsed by [AgentActionJsonParser]. Stateless per
 * step (like `--codex-session-cache=off`), so no server-side image accumulation.
 */
public class CodexResponsesAiProvider(
  private val modelName: String = DEFAULT_MODEL,
  private val reasoningEffort: String? = CodexCliAiProvider.DEFAULT_REASONING_EFFORT,
  private val baseUrl: String = DEFAULT_BASE_URL,
  private val timeoutMs: Long = CodexCliAiProvider.DEFAULT_TIMEOUT_MS,
) : ArbigentAiProvider {
  override val metadata: ArbigentAiProviderMetadata = ArbigentAiProviderMetadata(
    id = "codex-direct",
    label = "Codex (ChatGPT subscription, direct HTTP)",
    runtime = ArbigentAiRuntime(
      id = "codex-responses",
      transport = ArbigentAiTransport.OpenAiCompatibleHttp,
      modelName = modelName,
      source = baseUrl,
    ),
    capabilities = setOf(
      ArbigentAiCapability.AgentDecision,
      ArbigentAiCapability.VisionInput,
    ),
  )

  override fun createAi(): ArbigentAi = CodexResponsesAi(modelName, reasoningEffort, baseUrl, timeoutMs)

  public companion object {
    public const val DEFAULT_MODEL: String = "gpt-5.5"
    public const val DEFAULT_BASE_URL: String = "https://chatgpt.com/backend-api/codex/"
  }
}

internal class CodexResponsesAi(
  private val modelName: String,
  private val reasoningEffort: String?,
  private val baseUrl: String,
  private val timeoutMs: Long,
) : ArbigentAi {
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
  private val auth = CodexChatGptAuth()
  private val httpClient: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(30))
    .build()
  // Stable per run (a fresh AI is created per agent run), so the cached prefix
  // (system prompt + action list + output contract) is reused across steps.
  private val promptCacheKey: String = UUID.randomUUID().toString()

  override fun generateScenarios(scenarioGenerationInput: ArbigentAi.ScenarioGenerationInput): GeneratedScenariosContent =
    throw UnsupportedOperationException("codex-direct does not support scenario generation. Use an OpenAI-compatible provider.")

  override fun assertImage(imageAssertionInput: ArbigentAi.ImageAssertionInput): ArbigentAi.ImageAssertionOutput =
    throw UnsupportedOperationException("codex-direct does not support Arbigent image assertions. Use an OpenAI-compatible provider.")

  override fun decideAgentActions(decisionInput: ArbigentAi.DecisionInput): ArbigentAi.DecisionOutput {
    val original = File(decisionInput.screenshotFilePath)
    val canvas = ArbigentCanvas.load(original, decisionInput.elements.screenWidth, TYPE_INT_RGB)
    canvas.draw(decisionInput.elements)
    val annotated = File(original.parentFile, original.nameWithoutExtension + "_annotated.png")
    canvas.save(annotated.absolutePath, decisionInput.aiOptions)

    val instructions = buildInstructions(decisionInput)
    val userText = buildUserText(decisionInput)
    val schema = CodexDecisionFormat.outputSchema(decisionInput.agentActionTypes, decisionInput.mcpTools)
    val requestBody = buildRequestBody(instructions, userText, annotated, schema)

    val startedAt = TimeProvider.get().currentTimeMillis()
    val message = postForOutputText(requestBody, decisionInput.requestUuid)
    val finishedAt = TimeProvider.get().currentTimeMillis()
    writeApiLog(decisionInput, message, startedAt, finishedAt)

    val step = try {
      val responseObj = CodexDecisionFormat.parseJsonObject(message, json)
      val action = responseObj["action"]?.jsonPrimitive?.content
        ?: throw IllegalArgumentException("Action not found in codex-direct response")
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
        aiRequest = userText,
        aiResponse = message,
        screenshotFilePath = decisionInput.screenshotFilePath,
        apiCallJsonLFilePath = decisionInput.apiCallJsonLFilePath,
        uiTreeStrings = decisionInput.uiTreeStrings,
        cacheKey = decisionInput.cacheKey,
      )
    } catch (e: Exception) {
      ArbigentContextHolder.Step(
        stepId = decisionInput.stepId,
        feedback = "Failed to parse codex-direct response: ${e.message}",
        screenshotFilePath = decisionInput.screenshotFilePath,
        aiRequest = userText,
        aiResponse = message,
        uiTreeStrings = decisionInput.uiTreeStrings,
        cacheKey = decisionInput.cacheKey,
      )
    }
    return ArbigentAi.DecisionOutput(listOfNotNull(step.agentAction), step)
  }

  private fun buildInstructions(decisionInput: ArbigentAi.DecisionInput): String {
    val systemPrompts = when (decisionInput.formFactor) {
      ArbigentScenarioDeviceFormFactor.Tv -> decisionInput.prompt.systemPromptsForTv
      else -> decisionInput.prompt.systemPrompts
    } + decisionInput.prompt.additionalSystemPrompts
    val actionDescriptions = decisionInput.agentActionTypes.joinToString("\n") { actionType ->
      val args = actionType.arguments().joinToString(", ") { "${it.name}: ${it.type} (${it.description})" }
        .ifBlank { "no arguments" }
      "- ${actionType.actionName}: ${actionType.actionDescription()} Args: $args"
    }
    val mcpDescriptions = decisionInput.mcpTools.orEmpty()
      .joinToString("\n") { "- mcp_${it.name}: ${it.description}" }
      .ifBlank { "- none" }
    return """
You are the Codex-backed AI provider for Arbigent.

<SYSTEM_PROMPTS>
${systemPrompts.joinToString("\n")}
</SYSTEM_PROMPTS>

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
If a visible target is only partially visible or close to a screen edge, navigation bar, or tab bar, use Scroll or Swipe to center it before clicking.
Before choosing GoalAchieved, verify every explicit constraint in GOAL and write the evidence in arbigent-memo. If the current screen is a plausible but unverified leftover from a previous task, continue navigating instead of finishing.
""".trimIndent()
  }

  private fun buildUserText(decisionInput: ArbigentAi.DecisionInput): String {
    val focusedTreeText = decisionInput.focusedTreeString.orEmpty().ifBlank { "No focused tree" }
    val uiElements = decisionInput.elements.getPromptTexts().ifBlank { "No UI elements to select. Please check the image." }
    val aiOptions = decisionInput.aiOptions ?: ArbigentAiOptions()
    return decisionInput.contextHolder.prompt(
      uiElements = uiElements,
      focusedTree = focusedTreeText,
      aiOptions = aiOptions,
      aiHints = decisionInput.uiTreeStrings.aiHints,
    )
  }

  private fun buildRequestBody(instructions: String, userText: String, image: File, schema: JsonObject): String {
    val imageBase64 = Base64.getEncoder().encodeToString(image.readBytes())
    return buildJsonObject {
      put("model", modelName)
      put("instructions", instructions)
      putJsonArray("input") {
        addJsonObject {
          put("role", "user")
          putJsonArray("content") {
            addJsonObject {
              put("type", "input_text")
              put("text", userText)
            }
            addJsonObject {
              put("type", "input_image")
              put("image_url", "data:image/png;base64,$imageBase64")
            }
          }
        }
      }
      reasoningEffort?.takeIf { it.isNotBlank() }?.let {
        putJsonObject("reasoning") { put("effort", it) }
      }
      putJsonObject("text") {
        putJsonObject("format") {
          put("type", "json_schema")
          put("name", "arbigent_action")
          put("strict", true)
          put("schema", schema)
        }
      }
      put("store", false)
      put("stream", true)
      put("prompt_cache_key", promptCacheKey)
    }.toString()
  }

  private fun postForOutputText(body: String, requestUuid: String): String {
    var refreshed = false
    while (true) {
      val request = HttpRequest.newBuilder(URI.create(baseUrl + "responses"))
        .timeout(Duration.ofMillis(timeoutMs))
        .header("Authorization", "Bearer ${auth.accessToken()}")
        .header("chatgpt-account-id", auth.accountId())
        .header("OpenAI-Beta", "responses=experimental")
        .header("originator", "codex_cli_rs")
        .header("session_id", requestUuid)
        .header("Content-Type", "application/json")
        .header("Accept", "text/event-stream")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
      val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
      if (response.statusCode() == 401 && !refreshed) {
        refreshed = true
        auth.forceRefresh()
        continue
      }
      if (response.statusCode() !in 200..299) {
        throw IllegalStateException("codex-direct request failed (${response.statusCode()})")
      }
      return parseSseOutputText(response.body())
    }
  }

  private fun parseSseOutputText(sse: String): String {
    val deltas = StringBuilder()
    var doneText: String? = null
    sse.lineSequence().forEach { line ->
      if (!line.startsWith("data:")) return@forEach
      val payload = line.removePrefix("data:").trim()
      if (payload.isEmpty() || payload == "[DONE]") return@forEach
      val event = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return@forEach
      when (event["type"]?.jsonPrimitive?.content) {
        "response.output_text.delta" -> event["delta"]?.jsonPrimitive?.content?.let { deltas.append(it) }
        "response.output_text.done" -> doneText = event["text"]?.jsonPrimitive?.content
        "response.failed", "error", "response.error" ->
          throw IllegalStateException("codex-direct backend error: ${event["message"]?.jsonPrimitive?.content ?: payload.take(200)}")
      }
    }
    val text = deltas.toString().ifBlank { doneText.orEmpty() }
    if (text.isBlank()) throw IllegalStateException("codex-direct returned no output text")
    return text
  }

  private fun writeApiLog(input: ArbigentAi.DecisionInput, message: String, startedAt: Long, finishedAt: Long) {
    runCatching {
      val file = File(input.apiCallJsonLFilePath)
      file.parentFile?.mkdirs()
      val record = buildJsonObject {
        put("provider", "codex-direct")
        put("lastMessage", message)
        put("startedAt", startedAt)
        put("finishedAt", finishedAt)
        put("durationMs", finishedAt - startedAt)
        put("modelName", modelName)
        put("reasoningEffort", reasoningEffort ?: "")
        put("sessionCacheMode", "direct")
        put("resumed", false)
        put("schemaEnforced", true)
        put("screenshotFile", input.screenshotFilePath)
      }
      file.writeText(record.toString())
    }
  }
}

/** Decision output schema + JSON parsing shared with the Codex CLI provider's envelope shape. */
internal object CodexDecisionFormat {
  fun outputSchema(agentActionTypes: List<AgentActionType>, mcpTools: List<MCPTool>?): JsonObject {
    val actionNames = agentActionTypes.map { it.actionName } + mcpTools.orEmpty().map { "mcp_${it.name}" }
    return buildJsonObject {
      put("type", "object")
      put("additionalProperties", false)
      putJsonArray("required") {
        add("action"); add("text"); add("arguments")
        add(ArbigentAiAnswerItems.Memo.key); add(ArbigentAiAnswerItems.ImageDescription.key)
      }
      putJsonObject("properties") {
        putJsonObject("action") {
          put("type", "string")
          putJsonArray("enum") { actionNames.forEach { add(it) } }
        }
        putJsonObject("text") { put("type", "string"); put("description", "Primary action argument, or empty string for no-argument and MCP actions.") }
        putJsonObject("arguments") { put("type", "string"); put("description", "Compact JSON object string for MCP tool arguments, or \"{}\" for ordinary Arbigent actions.") }
        putJsonObject(ArbigentAiAnswerItems.Memo.key) { put("type", "string"); put("description", "Brief reasoning memo for the selected action.") }
        putJsonObject(ArbigentAiAnswerItems.ImageDescription.key) { put("type", "string"); put("description", "Brief description of the visible screen.") }
      }
    }
  }

  fun parseJsonObject(text: String, json: Json): JsonObject {
    return try {
      json.parseToJsonElement(text).jsonObject
    } catch (e: Exception) {
      val start = text.indexOf('{')
      val end = text.lastIndexOf('}')
      if (start >= 0 && end > start) json.parseToJsonElement(text.substring(start, end + 1)).jsonObject else throw e
    }
  }
}
