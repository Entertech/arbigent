package io.github.takahirom.arbigent

import io.github.takahirom.arbigent.result.ArbigentScenarioDeviceFormFactor
import io.github.takahirom.arbigent.result.ArbigentUiTreeStrings
import maestro.TreeNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class CodexCliAiTest {
  @get:Rule
  val temp: TemporaryFolder = TemporaryFolder()

  @Test
  fun `decideAgentActions reads codex output-last-message as arbigent action`() {
    val codexScript = temp.newFile("codex-fake.sh").apply {
      writeText(
        """
#!/bin/sh
out=""
schema=""
reasoning_effort=""
while [ "$#" -gt 0 ]; do
  if [ "$1" = "--output-last-message" ]; then
    shift
    out="$1"
  elif [ "$1" = "--output-schema" ]; then
    shift
    schema="$1"
  elif [ "$1" = "-c" ]; then
    shift
    if [ "$1" = "model_reasoning_effort=\"low\"" ]; then
      reasoning_effort="$1"
    fi
  fi
  shift
done
grep -q '"arguments":{"type":"string"' "${'$'}schema" || exit 7
[ -n "${'$'}reasoning_effort" ] || exit 8
cat >/dev/null
printf '%s' '{"action":"ClickAtCoordinates","text":"12,34","arguments":"{}","arbigent-memo":"tap visible target","arbigent-image-description":"test screen"}' > "${'$'}out"
exit 0
        """.trimIndent()
      )
      setExecutable(true)
    }
    val screenshot = temp.newFile("screen.png")
    ImageIO.write(BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB).apply {
      graphics.color = Color.WHITE
      graphics.fillRect(0, 0, width, height)
    }, "png", screenshot)
    val apiCallFile = File(temp.root, "decision.jsonl")
    val ai = CodexCliAi(
      codexExecutable = codexScript.absolutePath,
      modelName = null,
      reasoningEffort = CodexCliAiProvider.DEFAULT_REASONING_EFFORT,
      profile = null,
      sandbox = CodexCliAiProvider.DEFAULT_SANDBOX,
      approvalPolicy = CodexCliAiProvider.DEFAULT_APPROVAL_POLICY,
      timeoutMs = 10_000,
      workingDirectory = temp.root.absolutePath,
    )

    val output = ai.decideAgentActions(
      ArbigentAi.DecisionInput(
        stepId = "step-1",
        contextHolder = ArbigentContextHolder(goal = "tap target", maxStep = 3),
        formFactor = ArbigentScenarioDeviceFormFactor.Mobile,
        uiTreeStrings = ArbigentUiTreeStrings(
          allTreeString = "tree",
          optimizedTreeString = "optimized tree",
        ),
        focusedTreeString = null,
        agentActionTypes = listOf(ClickAtCoordinates, GoalAchievedAgentAction, FailedAgentAction),
        screenshotFilePath = screenshot.absolutePath,
        requestUuid = "request-1",
        apiCallJsonLFilePath = apiCallFile.absolutePath,
        elements = elementList(),
        prompt = ArbigentPrompt(),
        cacheKey = "cache-key",
        aiOptions = ArbigentAiOptions(),
      )
    )

    assertEquals(1, output.agentActions.size)
    assertEquals(ClickAtCoordinates(x = 12, y = 34), output.agentActions.first())
    assertEquals("ClickAtCoordinates", output.step.action)
    assertEquals("tap visible target", output.step.memo)
    assertTrue(apiCallFile.exists())
  }

  private fun elementList(): ArbigentElementList {
    return ArbigentElementList(
      elements = listOf(
        ArbigentElement(
          index = 0,
          textForAI = "Button(text=target)",
          rawText = "target",
          identifierData = ArbigentElement.IdentifierData(listOf("target"), 0),
          treeNode = TreeNode(
            attributes = mutableMapOf(
              "text" to "target",
              "class" to "Button",
              "enabled" to "true",
              "clickable" to "true",
            ),
            children = emptyList(),
            clickable = true,
            enabled = true,
          ),
          x = 10,
          y = 10,
          width = 20,
          height = 20,
          isVisible = true,
        )
      ),
      screenWidth = 100,
    )
  }
}
