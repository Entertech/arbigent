package io.github.takahirom.arbigent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
      return ExecuteMcpToolAgentAction(
        tool = mcpTool,
        executeToolArgs = ExecuteToolArgs(
          arguments = argumentsJsonData.let {
            JsonObject(it.filterKeys { key ->
              !ArbigentAiAnswerItems.entries.map { item -> item.key }.contains(key)
            }.toMap())
          },
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
          throw IllegalArgumentException("text should be \"x,y\" for ${ClickAtCoordinates.actionName}, got: \"$text\"")
        }
        val x = parts[0].toIntOrNull()
          ?: throw IllegalArgumentException("x is not an integer for ${ClickAtCoordinates.actionName}: \"${parts[0]}\"")
        val y = parts[1].toIntOrNull()
          ?: throw IllegalArgumentException("y is not an integer for ${ClickAtCoordinates.actionName}: \"${parts[1]}\"")
        ClickAtCoordinates(x = x, y = y)
      }

      BackPressAgentAction -> BackPressAgentAction()

      KeyPressAgentAction -> {
        val text = textArgument(argumentsJsonData)
        KeyPressAgentAction(text)
      }

      WaitAgentAction -> {
        val text = textArgument(argumentsJsonData)
        WaitAgentAction(text.toIntOrNull() ?: 1000)
      }

      ScrollAgentAction -> ScrollAgentAction()

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
}
