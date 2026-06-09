package io.github.takahirom.arbigent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import maestro.SwipeDirection
import kotlin.math.roundToInt

internal object AgentActionJsonParser {
  private val json = Json {
    ignoreUnknownKeys = true
  }

  fun parseAgentAction(
    agentActionList: List<AgentActionType>,
    action: String,
    argumentsJsonData: JsonObject,
    elements: ArbigentElementList,
    mcpTools: List<MCPTool>?
  ): ArbigentAgentAction {
    if (action.startsWith("mcp_")) {
      val mcpAction = action.removePrefix("mcp_")
      val mcpTool = mcpTools?.firstOrNull { it.name == mcpAction }
        ?: throw IllegalArgumentException("Unknown MCP action: $action. Available actions: ${mcpTools?.joinToString { it.name }}")
      // The real tool arguments live in the nested `arguments` object when the
      // model wraps its answer (Codex requires a top-level {action,text,arguments}
      // envelope). When there is no wrapper (OpenAI function calls pass the tool
      // params directly), fall back to the top-level object. In both cases strip
      // arbigent's own envelope keys (memo / image description) so neither they
      // nor the structural keys (action/text/arguments) reach the MCP tool.
      val answerItemKeys = ArbigentAiAnswerItems.entries.map { item -> item.key }.toSet()
      val rawToolArgs = (argumentsJsonData["arguments"] as? JsonObject) ?: argumentsJsonData
      return ExecuteMcpToolAgentAction(
        tool = mcpTool,
        executeToolArgs = ExecuteToolArgs(
          arguments = JsonObject(rawToolArgs.filterKeys { key -> key !in answerItemKeys }),
        )
      )
    }

    val agentActionMap = agentActionList.associateBy { it.actionName }
    val actionPrototype = agentActionMap[action]
      ?: throw IllegalArgumentException("Unknown action: $action. Available actions: ${agentActionMap.keys.joinToString()}")
    return when (actionPrototype) {
      GoalAchievedAgentAction -> GoalAchievedAgentAction()
      FailedAgentAction -> FailedAgentAction()
      ClickWithTextAgentAction -> {
        val text = textArgument(argumentsJsonData)
        ClickWithTextAgentAction(text)
      }

      ClickWithIdAgentAction -> {
        val text = textArgument(argumentsJsonData)
        ClickWithIdAgentAction(text)
      }

      DpadUpArrowAgentAction -> {
        val text = textArgument(argumentsJsonData)
        DpadUpArrowAgentAction(text.toIntOrNull() ?: 1)
      }

      DpadDownArrowAgentAction -> {
        val text = textArgument(argumentsJsonData)
        DpadDownArrowAgentAction(text.toIntOrNull() ?: 1)
      }

      DpadLeftArrowAgentAction -> {
        val text = textArgument(argumentsJsonData)
        DpadLeftArrowAgentAction(text.toIntOrNull() ?: 1)
      }

      DpadRightArrowAgentAction -> {
        val text = textArgument(argumentsJsonData)
        DpadRightArrowAgentAction(text.toIntOrNull() ?: 1)
      }

      DpadCenterAgentAction -> {
        val text = textArgument(argumentsJsonData)
        DpadCenterAgentAction(text.toIntOrNull() ?: 1)
      }

      DpadAutoFocusWithIdAgentAction -> {
        val text = textArgument(argumentsJsonData)
        DpadAutoFocusWithIdAgentAction(text)
      }

      DpadAutoFocusWithTextAgentAction -> {
        val text = textArgument(argumentsJsonData)
        DpadAutoFocusWithTextAgentAction(text)
      }

      DpadAutoFocusWithIndexAgentAction -> {
        val text = textArgument(argumentsJsonData)
        val index = text.toIntOrNull()
          ?: throw IllegalArgumentException("text should be a number for ${DpadAutoFocusWithIndexAgentAction.actionName}")
        if (elements.elements.size <= index) {
          throw IllegalArgumentException("Index out of bounds: $index")
        }
        DpadAutoFocusWithIndexAgentAction(index)
      }

      InputTextAgentAction -> {
        val text = textArgument(argumentsJsonData)
        InputTextAgentAction(text)
      }

      ClickWithIndex -> {
        val text = textArgument(argumentsJsonData)
        val index = text.toIntOrNull()
          ?: throw IllegalArgumentException("text should be a number for ${ClickWithIndex.actionName}")
        if (elements.elements.size <= index) {
          throw IllegalArgumentException("Index out of bounds: $index")
        }
        ClickWithIndex(index = index)
      }

      ClickAtCoordinates -> {
        val text = textArgument(argumentsJsonData)
        val parts = text.split(",").map { it.trim() }
        if (parts.size != 2) {
          throw IllegalArgumentException("text should be \"nx,ny\" (fractions in [0,1]) for ${ClickAtCoordinates.actionName}, got: \"$text\"")
        }
        val nx = parts[0].toDoubleOrNull()
          ?: throw IllegalArgumentException("nx is not a number for ${ClickAtCoordinates.actionName}: \"${parts[0]}\"")
        val ny = parts[1].toDoubleOrNull()
          ?: throw IllegalArgumentException("ny is not a number for ${ClickAtCoordinates.actionName}: \"${parts[1]}\"")
        ClickAtCoordinates(xPercent = toPercent(nx), yPercent = toPercent(ny))
      }

      BackPressAgentAction -> BackPressAgentAction()

      GoHomeAgentAction -> GoHomeAgentAction()

      KeyPressAgentAction -> {
        val text = textArgument(argumentsJsonData)
        KeyPressAgentAction(text)
      }

      WaitAgentAction -> {
        val text = textArgument(argumentsJsonData)
        WaitAgentAction(text.toIntOrNull() ?: 1000)
      }

      ScrollAgentAction -> ScrollAgentAction()

      SwipeAgentAction -> {
        val text = textArgument(argumentsJsonData)
        val direction = SwipeDirection.values().firstOrNull { it.name.equals(text.trim(), ignoreCase = true) }
          ?: throw IllegalArgumentException("text should be UP, DOWN, LEFT, or RIGHT for ${SwipeAgentAction.actionName}")
        SwipeAgentAction(direction)
      }

      DragAgentAction -> {
        val text = textArgument(argumentsJsonData)
        val parts = text.split(",").map { it.trim() }
        if (parts.size != 4) {
          throw IllegalArgumentException("text should be \"startX,startY,endX,endY\" (fractions in [0,1]) for ${DragAgentAction.actionName}, got: \"$text\"")
        }
        val n = parts.map { p ->
          p.toDoubleOrNull()
            ?: throw IllegalArgumentException("non-numeric coordinate for ${DragAgentAction.actionName}: \"$p\"")
        }
        DragAgentAction(
          startXPercent = toPercent(n[0]),
          startYPercent = toPercent(n[1]),
          endXPercent = toPercent(n[2]),
          endYPercent = toPercent(n[3]),
        )
      }

      else -> throw IllegalArgumentException("Unsupported action: $action")
    }
  }

  fun normalizeArguments(responseJsonObject: JsonObject): JsonObject {
    val nestedArguments = responseJsonObject["arguments"]?.toJsonObjectOrNull()
    return if (nestedArguments == null) {
      responseJsonObject
    } else {
      JsonObject(responseJsonObject + nestedArguments)
    }
  }

  private fun JsonElement.toJsonObjectOrNull(): JsonObject? {
    if (this is JsonObject) return this
    if (this !is JsonPrimitive) return null
    val content = content.trim()
    if (content.isBlank() || content == "{}") return null
    return try {
      json.parseToJsonElement(content).jsonObject
    } catch (e: Exception) {
      throw IllegalArgumentException("arguments must be a JSON object or JSON object string: ${e.message}", e)
    }
  }

  private fun textArgument(argumentsJsonData: JsonObject): String {
    return argumentsJsonData["text"]?.jsonPrimitive?.content
      ?: throw IllegalArgumentException("Text not found")
  }

  /**
   * Normalize a model-supplied coordinate value to an integer percent in [0,100].
   * The contract asks for a fraction in [0,1], but models sometimes emit a 0-100
   * percent instead; both are accepted and anything out of range is clamped, so a
   * hallucinated value degrades to an in-bounds tap rather than crashing downstream.
   */
  private fun toPercent(raw: Double): Int {
    val fraction = when {
      raw <= 1.0 -> raw           // intended contract: fraction in [0,1]
      raw <= 100.0 -> raw / 100.0 // tolerate a 0-100 percent mistake
      else -> 1.0                 // out of range -> clamp to far edge
    }
    return (fraction.coerceIn(0.0, 1.0) * 100).roundToInt().coerceIn(0, 100)
  }
}
