package io.github.takahirom.arbigent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import maestro.SwipeDirection
import maestro.TreeNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentActionJsonParserTest {
  @Test
  fun `parses normalized coordinate action from codex style json`() {
    val action = AgentActionJsonParser.parseAgentAction(
      agentActionList = listOf(ClickAtCoordinates),
      action = "ClickAtCoordinates",
      argumentsJsonData = JsonObject(mapOf("text" to JsonPrimitive("0.12,0.34"))),
      elements = emptyElements(),
      mcpTools = null,
    )

    assertEquals(ClickAtCoordinates(xPercent = 12, yPercent = 34), action)
  }

  @Test
  fun `coordinate action tolerates 0-100 percent and clamps out-of-range`() {
    // Model mistakenly emits 0-100 percent instead of a [0,1] fraction.
    assertEquals(
      ClickAtCoordinates(xPercent = 50, yPercent = 73),
      AgentActionJsonParser.parseAgentAction(
        agentActionList = listOf(ClickAtCoordinates),
        action = "ClickAtCoordinates",
        argumentsJsonData = JsonObject(mapOf("text" to JsonPrimitive("50,73"))),
        elements = emptyElements(),
        mcpTools = null,
      )
    )
    // Out-of-range and negative values clamp into [0,100] rather than crashing.
    assertEquals(
      ClickAtCoordinates(xPercent = 100, yPercent = 0),
      AgentActionJsonParser.parseAgentAction(
        agentActionList = listOf(ClickAtCoordinates),
        action = "ClickAtCoordinates",
        argumentsJsonData = JsonObject(mapOf("text" to JsonPrimitive("999,-0.2"))),
        elements = emptyElements(),
        mcpTools = null,
      )
    )
  }

  @Test
  fun `parses indexed action from normalized nested arguments`() {
    val arguments = AgentActionJsonParser.normalizeArguments(
      JsonObject(
        mapOf(
          "text" to JsonPrimitive(""),
          "arguments" to JsonObject(mapOf("text" to JsonPrimitive("1"))),
        )
      )
    )

    val action = AgentActionJsonParser.parseAgentAction(
      agentActionList = listOf(ClickWithIndex),
      action = "ClickWithIndex",
      argumentsJsonData = arguments,
      elements = elementList(size = 2),
      mcpTools = null,
    )

    assertEquals(ClickWithIndex(index = 1), action)
  }

  @Test
  fun `parses indexed action from normalized string arguments`() {
    val arguments = AgentActionJsonParser.normalizeArguments(
      JsonObject(
        mapOf(
          "text" to JsonPrimitive(""),
          "arguments" to JsonPrimitive("{\"text\":\"1\"}"),
        )
      )
    )

    val action = AgentActionJsonParser.parseAgentAction(
      agentActionList = listOf(ClickWithIndex),
      action = "ClickWithIndex",
      argumentsJsonData = arguments,
      elements = elementList(size = 2),
      mcpTools = null,
    )

    assertEquals(ClickWithIndex(index = 1), action)
  }

  @Test
  fun `parses drag action from four normalized fractions`() {
    val action = AgentActionJsonParser.parseAgentAction(
      agentActionList = listOf(DragAgentAction),
      action = "Drag",
      argumentsJsonData = JsonObject(mapOf("text" to JsonPrimitive("0.5,0.5,0.5,0.2"))),
      elements = emptyElements(),
      mcpTools = null,
    )

    assertEquals(
      DragAgentAction(startXPercent = 50, startYPercent = 50, endXPercent = 50, endYPercent = 20),
      action,
    )
  }

  @Test
  fun `goal action without text argument`() {
    val action = AgentActionJsonParser.parseAgentAction(
      agentActionList = listOf(GoalAchievedAgentAction),
      action = "GoalAchieved",
      argumentsJsonData = JsonObject(emptyMap()),
      elements = emptyElements(),
      mcpTools = null,
    )

    assertTrue(action is GoalAchievedAgentAction)
  }

  @Test
  fun `parses swipe action direction case-insensitively`() {
    val action = AgentActionJsonParser.parseAgentAction(
      agentActionList = listOf(SwipeAgentAction),
      action = "Swipe",
      argumentsJsonData = JsonObject(mapOf("text" to JsonPrimitive("down"))),
      elements = emptyElements(),
      mcpTools = null,
    )

    assertEquals(SwipeAgentAction(SwipeDirection.DOWN), action)
  }

  @Test
  fun `mcp action keeps only nested tool arguments from codex envelope`() {
    val mcpTool = MCPTool(tool = Tool(name = "search"), serverName = "test")
    val arguments = AgentActionJsonParser.normalizeArguments(
      JsonObject(
        mapOf(
          "action" to JsonPrimitive("mcp_search"),
          "text" to JsonPrimitive(""),
          "arguments" to JsonObject(mapOf("query" to JsonPrimitive("ado"))),
          "arbigent-memo" to JsonPrimitive("memo"),
          "arbigent-image-description" to JsonPrimitive("desc"),
        )
      )
    )

    val action = AgentActionJsonParser.parseAgentAction(
      agentActionList = emptyList(),
      action = "mcp_search",
      argumentsJsonData = arguments,
      elements = emptyElements(),
      mcpTools = listOf(mcpTool),
    )

    action as ExecuteMcpToolAgentAction
    // Only the real tool params survive — no action/text/arguments envelope and
    // no arbigent answer-item keys.
    assertEquals(
      JsonObject(mapOf("query" to JsonPrimitive("ado"))),
      action.executeToolArgs.arguments,
    )
  }

  @Test
  fun `mcp action strips answer items from flat openai arguments`() {
    val mcpTool = MCPTool(tool = Tool(name = "search"), serverName = "test")
    val action = AgentActionJsonParser.parseAgentAction(
      agentActionList = emptyList(),
      action = "mcp_search",
      // OpenAI passes the tool params flat (no wrapper), with answer items appended.
      argumentsJsonData = JsonObject(
        mapOf(
          "query" to JsonPrimitive("ado"),
          "arbigent-memo" to JsonPrimitive("memo"),
          "arbigent-image-description" to JsonPrimitive("desc"),
        )
      ),
      elements = emptyElements(),
      mcpTools = listOf(mcpTool),
    )

    action as ExecuteMcpToolAgentAction
    assertEquals(
      JsonObject(mapOf("query" to JsonPrimitive("ado"))),
      action.executeToolArgs.arguments,
    )
  }

  private fun emptyElements(): ArbigentElementList = ArbigentElementList(emptyList(), screenWidth = 100)

  private fun elementList(size: Int): ArbigentElementList {
    return ArbigentElementList(
      elements = (0 until size).map { index ->
        ArbigentElement(
          index = index,
          textForAI = "Button(text=$index)",
          rawText = "text=$index",
          identifierData = ArbigentElement.IdentifierData(listOf(index), 0),
          treeNode = TreeNode(
            attributes = mutableMapOf(
              "text" to "$index",
              "class" to "Button",
              "enabled" to "true",
              "clickable" to "true",
            ),
            children = emptyList(),
            clickable = true,
            enabled = true,
          ),
          x = 0,
          y = 0,
          width = 10,
          height = 10,
          isVisible = true,
        )
      },
      screenWidth = 100,
    )
  }
}
