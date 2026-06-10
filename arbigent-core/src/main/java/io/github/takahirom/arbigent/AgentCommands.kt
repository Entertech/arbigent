package io.github.takahirom.arbigent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import maestro.KeyCode
import maestro.MaestroException
import maestro.SwipeDirection
import maestro.orchestra.*

@Serializable
public sealed interface ArbigentAgentAction {
  public val actionName: String
  public fun runDeviceAction(runInput: RunInput)
  public fun stepLogText(): String

  public class RunInput(
    public val device: ArbigentDevice,
    public val elements: ArbigentElementList,
  )

  public fun isGoal(): Boolean {
    return actionName == GoalAchievedAgentAction.actionName
  }
}

public interface AgentActionType {
  public val actionName: String

  /**
   * Returns a description of the action.
   */
  public fun actionDescription(): String

  public data class Argument(
    val name: String,
    val type: String,
    val description: String
  ) {
    public fun toJson(): String {
      val description = JsonPrimitive(description)
      return """
        "$name": { "type": "$type", "description": $description }
      """.trimIndent()
    }
  }

  /**
   * Returns a list of argument descriptions for the action.
   */
  public fun arguments(): List<Argument>

  public fun isSupported(deviceOs: ArbigentDeviceOs): Boolean = true
}

private fun getRegexToIndex(text: String): Pair<String, String> {
  val regex = Regex("""(.*)\[(\d+)]""")
  val matchResult = regex.find(text) ?: return Pair(text, "0")
  val (regexText, index) = matchResult.destructured
  return Pair(regexText, index)
}

@Serializable
public data class ClickWithIndex(val index: Int) : ArbigentAgentAction {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Click on index: $index"
  }

  override fun runDeviceAction(runInput: ArbigentAgentAction.RunInput) {
    val elements = runInput.elements
    // Bounds-check instead of indexing blindly: a cached/stale index replayed
    // against a shorter (or empty, vision-only) live element list would otherwise
    // throw an uncaught IndexOutOfBoundsException and abort the whole step. An
    // IllegalStateException IS caught by executeActions and surfaced to the model
    // as feedback, steering it to ClickAtCoordinates when the tree lacks the target.
    val element = elements.elements.getOrNull(index)
      ?: throw IllegalStateException(
        "Index $index is not available in ELEMENTS (size=${elements.elements.size}). " +
          "If the target is visible in the screenshot but missing from ELEMENTS, use ClickAtCoordinates."
      )
    runInput.device.executeActions(
      actions = listOf(
        MaestroCommand(
          tapOnPointV2Command = TapOnPointV2Command(
            point = "${element.rect.centerX()},${element.rect.centerY()}"
          )
        )
      ),
    )
  }

  public companion object : AgentActionType {
    override val actionName: String = "ClickWithIndex"

    override fun arguments(): List<AgentActionType.Argument> =
      listOf(
        AgentActionType.Argument(
          name = "text",
          type = "string",
          description = "The index of the ELEMENTS to click on. Should be a number like 1 or 2, NOT text or ID."
        )
      )

    override fun actionDescription(): String = "Click on an element by its index in the ELEMENTS"
  }
}

/**
 * Vision-grounded tap. Coordinates are NORMALIZED fractions of the screenshot in
 * [0,100] percent (xPercent=0 left edge, 100 right edge; yPercent=0 top, 100 bottom).
 *
 * Why percent and not raw pixels: the model only ever sees the annotated screenshot,
 * which Arbigent rescales (ArbigentCanvas.load) and then caps to a max long edge
 * (capLongEdge). A pixel the model reads off that downscaled image is in the wrong
 * coordinate space and would land far from the intended target on a high-DPI device.
 * A fraction is invariant under uniform downscaling, so emitting "x%,y%" lets each
 * backend scale it against its OWN true tap space: Maestro's Orchestra routes a
 * percent point through tapOnRelative -> widthGrid/heightGrid, and the iOS mirror
 * backend's parsePoint scales it against the mirror window size. No new transform
 * code, correct on every backend, with or without a view tree.
 */
@Serializable
public data class ClickAtCoordinates(val xPercent: Int, val yPercent: Int) : ArbigentAgentAction {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Click at coordinates: (${xPercent}%, ${yPercent}%)"
  }

  override fun runDeviceAction(runInput: ArbigentAgentAction.RunInput) {
    val px = xPercent.coerceIn(0, 100)
    val py = yPercent.coerceIn(0, 100)
    runInput.device.executeActions(
      actions = listOf(
        MaestroCommand(
          tapOnPointV2Command = TapOnPointV2Command(
            point = "${px}%,${py}%"
          )
        )
      ),
    )
  }

  public companion object : AgentActionType {
    override val actionName: String = "ClickAtCoordinates"

    override fun arguments(): List<AgentActionType.Argument> =
      listOf(
        AgentActionType.Argument(
          name = "text",
          type = "string",
          description = "Normalized tap position as \"nx,ny\" where nx and ny are fractions in [0,1] of the screenshot (nx=0 left, 1 right; ny=0 top, 1 bottom). The center of the screen is \"0.5,0.5\". Use this when a target is VISIBLE in the screenshot but has NO matching index in ELEMENTS — e.g. iOS home-screen app icons, games/canvas UIs, or native system dialogs. ALWAYS prefer ClickWithIndex when the target appears in ELEMENTS."
        )
      )

    override fun actionDescription(): String = "Tap at a normalized screen position (fraction of the image). Use when the target is visible but absent from the ELEMENTS list."
  }
}

@Serializable
public data class ClickWithTextAgentAction(val textRegex: String) : ArbigentAgentAction {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Click on text: $textRegex"
  }

  override fun runDeviceAction(runInput: ArbigentAgentAction.RunInput) {
    val (textRegex, index) = getRegexToIndex(textRegex)
    val maestroCommand = MaestroCommand(
      tapOnElement = TapOnElementCommand(
        selector = ElementSelector(
          textRegex = textRegex, index = index
        ), waitToSettleTimeoutMs = 500, retryIfNoChange = false, waitUntilVisible = false
      )
    )
    try {
      runInput.device.executeActions(
        actions = listOf(
          maestroCommand
        ),
      )
    } catch (e: MaestroException) {
      runInput.device.executeActions(
        actions = listOf(
          maestroCommand.copy(
            tapOnElement = maestroCommand.tapOnElement!!.copy(
              selector = maestroCommand.tapOnElement!!.selector.copy(
                textRegex = ".*$textRegex.*"
              )
            )
          )
        ),
      )
    }
  }

  public companion object : AgentActionType {
    override val actionName: String = "ClickWithText"

    override fun actionDescription(): String = "Click on an element by its text content"

    override fun arguments(): List<AgentActionType.Argument> =
      listOf(
        AgentActionType.Argument(
          name = "text",
          type = "string",
          description = "The text with index should be clickable text, or content description. Should be in UI hierarchy, not a resource id. You can use Regex. If you want to click second button, you can use text[index] e.g.: \"text[0]\". Try different index if the first one doesn't work."
        )
      )
  }
}

@Serializable
public data class ClickWithIdAgentAction(val textRegex: String) : ArbigentAgentAction {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Click on id: $textRegex"
  }

  override fun runDeviceAction(runInput: ArbigentAgentAction.RunInput) {
    val (textRegex, index) = getRegexToIndex(textRegex)
    runInput.device.executeActions(
      actions = listOf(
        MaestroCommand(
          tapOnElement = TapOnElementCommand(
            selector = ElementSelector(
              idRegex = textRegex, index = index
            ), waitToSettleTimeoutMs = 500, waitUntilVisible = false
          )
        )
      ),
    )
  }

  public companion object : AgentActionType {
    override val actionName: String = "ClickWithId"

    override fun actionDescription(): String = "Click on an element by its ID"

    override fun arguments(): List<AgentActionType.Argument> =
      listOf(
        AgentActionType.Argument(
          name = "text",
          type = "string",
          description = "The text should be an ID that exists in the UI hierarchy. You can use Regex. If you want to click the second button, you can use \"button[1]\"."
        )
      )
  }
}

@Serializable
public data class DpadDownArrowAgentAction(val count: Int) : ArbigentAgentAction {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Press down arrow key $count times"
  }

  override fun runDeviceAction(runInput: ArbigentAgentAction.RunInput) {
    runInput.device.executeActions(
      actions = List(count) {
        MaestroCommand(
          pressKeyCommand = PressKeyCommand(
            code = KeyCode.REMOTE_DOWN
          )
        )
      },
    )
  }

  public companion object : AgentActionType {
    override val actionName: String = "DpadDownArrow"

    override fun actionDescription(): String = "Press the down arrow key on a D-pad"

    override fun arguments(): List<AgentActionType.Argument> =
      listOf(
        AgentActionType.Argument(
          name = "text",
          type = "string",
          description = "The number of times to press the down arrow key"
        )
      )
  }
}

@Serializable
public data class DpadUpArrowAgentAction(val count: Int) : ArbigentAgentAction {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Press up arrow key $count times"
  }

  override fun runDeviceAction(runInput: ArbigentAgentAction.RunInput) {
    runInput.device.executeActions(
      actions = List(count) {
        MaestroCommand(
          pressKeyCommand = PressKeyCommand(
            code = KeyCode.REMOTE_UP
          )
        )
      },
    )
  }

  public companion object : AgentActionType {
    override val actionName: String = "DpadUpArrow"

    override fun actionDescription(): String = "Press the up arrow key on a D-pad"

    override fun arguments(): List<AgentActionType.Argument> =
      listOf(
        AgentActionType.Argument(
          name = "text",
          type = "string",
          description = "The number of times to press the up arrow key"
        )
      )
  }
}

@Serializable
public data class DpadRightArrowAgentAction(val count: Int) : ArbigentAgentAction {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Press right arrow key $count times"
  }

  override fun runDeviceAction(runInput: ArbigentAgentAction.RunInput) {
    runInput.device.executeActions(
      actions = List(count) {
        MaestroCommand(
          pressKeyCommand = PressKeyCommand(
            code = KeyCode.REMOTE_RIGHT
          )
        )
      },
    )
  }

  public companion object : AgentActionType {
    override val actionName: String = "DpadRightArrow"

    override fun actionDescription(): String = "Press the right arrow key on a D-pad"

    override fun arguments(): List<AgentActionType.Argument> =
      listOf(
        AgentActionType.Argument(
          name = "text",
          type = "string",
          description = "The number of times to press the right arrow key"
        )
      )
  }
}

@Serializable
public data class DpadLeftArrowAgentAction(val count: Int) : ArbigentAgentAction {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Press left arrow key $count times"
  }

  override fun runDeviceAction(runInput: ArbigentAgentAction.RunInput) {
    runInput.device.executeActions(
      actions = List(count) {
        MaestroCommand(
          pressKeyCommand = PressKeyCommand(
            code = KeyCode.REMOTE_LEFT
          )
        )
      },
    )
  }

  public companion object : AgentActionType {
    override val actionName: String = "DpadLeftArrow"

    override fun actionDescription(): String = "Press the left arrow key on a D-pad"

    override fun arguments(): List<AgentActionType.Argument> =
      listOf(
        AgentActionType.Argument(
          name = "text",
          type = "string",
          description = "The number of times to press the left arrow key"
        )
      )
  }
}

@Serializable
public data class DpadCenterAgentAction(val count: Int) : ArbigentAgentAction {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Press center key $count times"
  }

  override fun runDeviceAction(runInput: ArbigentAgentAction.RunInput) {
    runInput.device.executeActions(
      actions = List(count) {
        MaestroCommand(
          pressKeyCommand = PressKeyCommand(
            code = KeyCode.REMOTE_CENTER
          )
        )
      },
    )
  }

  public companion object : AgentActionType {
    override val actionName: String = "DpadCenter"

    override fun actionDescription(): String = "Press the center key on a D-pad. Please refer to FOCUSED_TREE to know what will be clicked."

    override fun arguments(): List<AgentActionType.Argument> =
      listOf(
        AgentActionType.Argument(
          name = "text",
          type = "string",
          description = "The number of times to press the center key"
        )
      )
  }
}

@Serializable
public data class DpadAutoFocusWithIdAgentAction(val id: String) : ArbigentAgentAction {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Try to focus by id: $id"
  }

  override fun runDeviceAction(runInput: ArbigentAgentAction.RunInput) {
    val tvCompatibleDevice = (runInput.device as? ArbigentTvCompatDevice)
      ?: throw NotImplementedError(message = "This action is only available for TV device")
    tvCompatibleDevice.moveFocusToElement(ArbigentTvCompatDevice.Selector.ById.fromId(id))
  }

  public companion object : AgentActionType {
    override val actionName: String = "DpadTryAutoFocusById"

    override fun actionDescription(): String = "Try to focus on an element by its ID using D-pad navigation"

    override fun arguments(): List<AgentActionType.Argument> =
      listOf(
        AgentActionType.Argument(
          name = "text",
          type = "string",
          description = "The ID of the element to focus on. Should be in UI hierarchy. You can use Regex. If you want to focus on the second button, you can use text[index] e.g.: \"text[0]\". Try different index if the first one doesn't work."
        )
      )
  }
}

@Serializable
public data class DpadAutoFocusWithTextAgentAction(val text: String) : ArbigentAgentAction {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Try to focus by text: $text"
  }

  override fun runDeviceAction(runInput: ArbigentAgentAction.RunInput) {
    val tvCompatibleDevice = (runInput.device as? ArbigentTvCompatDevice)
      ?: throw NotImplementedError(message = "This action is only available for TV device")
    tvCompatibleDevice.moveFocusToElement(ArbigentTvCompatDevice.Selector.ByText.fromText(text))
  }

  public companion object : AgentActionType {
    override val actionName: String = "DpadTryAutoFocusByText"

    override fun actionDescription(): String = "Try to focus on an element by its text content using D-pad navigation"

    override fun arguments(): List<AgentActionType.Argument> =
      listOf(
        AgentActionType.Argument(
          name = "text",
          type = "string",
          description = "The text content or content description of the element to focus on. Should be in UI hierarchy, not a resource ID. You can use Regex. If you want to focus on the second button, you can use text[index] e.g.: \"text[0]\". Try different index if the first one doesn't work."
        )
      )
  }
}

@Serializable
public data class DpadAutoFocusWithIndexAgentAction(val index: Int) : ArbigentAgentAction {
  override val actionName: String = Companion.actionName

  public override fun stepLogText(): String {
    return "Try to focus by index: $index"
  }

  public override fun runDeviceAction(runInput: ArbigentAgentAction.RunInput) {
    val elements = runInput.elements
    val tvCompatibleDevice = (runInput.device as? ArbigentTvCompatDevice)
      ?: throw NotImplementedError(message = "This action is only available for TV device")
    val element = elements.elements.getOrNull(index)
      ?: throw IllegalStateException(
        "Index $index is not available in ELEMENTS (size=${elements.elements.size})."
      )
    tvCompatibleDevice.moveFocusToElement(element)
  }

  public companion object : AgentActionType {
    override val actionName: String = "DpadTryAutoFocusByIndex"

    override fun actionDescription(): String =
      "Try to focus on an element by its index in the ELEMENTS using D-pad navigation"

    override fun arguments(): List<AgentActionType.Argument> =
      listOf(
        AgentActionType.Argument(
          name = "text",
          type = "string",
          description = "The index of the ELEMENTS to focus on. Should be a number like 1 or 2, NOT text or ID."
        )
      )
  }
}

@Serializable
public data class InputTextAgentAction(val text: String) : ArbigentAgentAction {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Input text: $text"
  }

  override fun runDeviceAction(runInput: ArbigentAgentAction.RunInput) {
    runInput.device.executeActions(
      actions = listOf(
        MaestroCommand(
          inputTextCommand = InputTextCommand(
            text
          )
        )
      ),
    )
  }

  public companion object : AgentActionType {
    override val actionName: String = "InputText"

    override fun actionDescription(): String = "Input text into a text field"

    override fun arguments(): List<AgentActionType.Argument> =
      listOf(
        AgentActionType.Argument(
          name = "text",
          type = "string",
          description = "The text to input. You must click on a text field before sending this action."
        )
      )
  }
}

@Serializable
public class BackPressAgentAction : ArbigentAgentAction {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Press back button"
  }

  override fun runDeviceAction(runInput: ArbigentAgentAction.RunInput) {
    runInput.device.executeActions(
      actions = listOf(
        MaestroCommand(
          backPressCommand = BackPressCommand()
        )
      )
    )
  }

  public companion object : AgentActionType {
    override val actionName: String = "BackPress"

    override fun actionDescription(): String = "Press the back button on the device"

    override fun arguments(): List<AgentActionType.Argument> = emptyList()

    override fun isSupported(deviceOs: ArbigentDeviceOs): Boolean {
      return !deviceOs.isIos()
    }
  }
}

/**
 * Return to the device home screen / launcher. Cross-platform via the HOME key:
 * on iOS this triggers XCUIDevice.shared.press(.home) (the only reliable way to
 * leave an app, since iOS BackPress is an in-app edge swipe and a large Swipe-UP
 * does not consistently reach SpringBoard); on Android it sends KEYCODE_HOME.
 * Critical for recovery: without it, an agent that taps into the wrong app gets
 * trapped with no way back to a known starting point.
 */
@Serializable
public class GoHomeAgentAction : ArbigentAgentAction {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Go to home screen"
  }

  override fun runDeviceAction(runInput: ArbigentAgentAction.RunInput) {
    runInput.device.executeActions(
      actions = listOf(
        MaestroCommand(
          pressKeyCommand = PressKeyCommand(KeyCode.HOME)
        )
      ),
    )
  }

  public companion object : AgentActionType {
    override val actionName: String = "GoHome"

    override fun actionDescription(): String =
      "Return to the device home screen / launcher. Use this to escape an app you opened by mistake, or to get back to a known starting point before navigating again."

    override fun arguments(): List<AgentActionType.Argument> = emptyList()
  }
}

/**
 * Launch (or switch to) an app directly by its platform id — Android package
 * name or iOS bundle identifier. Skips the GoHome -> hunt-the-icon navigation
 * entirely (UI-TARS open_app / AutoGLM Launch / Mobile Use launch_app all ship
 * this for the same reason). Non-destructive by design: stopApp=false activates
 * a running app instead of restarting it, no state/keychain clearing, and
 * permissions are left untouched (Maestro's launch default would otherwise
 * rewrite them to all-allow). A wrong id throws MaestroException, which the
 * action loop converts into step feedback so the model falls back to manual
 * navigation.
 */
@Serializable
public data class LaunchAppAgentAction(val appId: String) : ArbigentAgentAction {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Launch app: $appId"
  }

  override fun runDeviceAction(runInput: ArbigentAgentAction.RunInput) {
    try {
      runInput.device.executeActions(
        actions = listOf(
          MaestroCommand(
            launchAppCommand = LaunchAppCommand(
              appId = appId,
              stopApp = false,
              permissions = emptyMap(),
            )
          )
        ),
      )
    } catch (e: NotImplementedError) {
      // A backend whose launch path hits a Maestro TODO() stub throws
      // NotImplementedError — an Error, which would otherwise escape every
      // Exception handler and kill the run. Convert it into the catchable
      // failure the agent loop turns into step feedback.
      throw IllegalStateException(
        "LaunchApp is not supported on this device backend. Navigate to the app manually instead.", e
      )
    }
  }

  public companion object : AgentActionType {
    override val actionName: String = "LaunchApp"

    override fun actionDescription(): String =
      "Launch or switch to an app directly by its platform app id. Much faster than navigating the home screen. If the launch fails (unknown id), fall back to navigating via the launcher."

    override fun arguments(): List<AgentActionType.Argument> =
      listOf(
        AgentActionType.Argument(
          name = "text",
          type = "string",
          description = "The app id: an Android package name (e.g. \"com.android.vending\") or an iOS bundle identifier (e.g. \"com.apple.AppStore\"). Use a well-known id you are confident about; do NOT guess obscure ids — navigate manually instead."
        )
      )
  }
}

@Serializable
public class ScrollAgentAction : ArbigentAgentAction {
  override val actionName: String = "Scroll"

  override fun stepLogText(): String {
    return "Scroll"
  }

  override fun runDeviceAction(runInput: ArbigentAgentAction.RunInput) {
    runInput.device.executeActions(
      actions = listOf(
        MaestroCommand(
          scrollCommand = ScrollCommand()
        )
      ),
    )
  }

  public companion object : AgentActionType {
    override val actionName: String = "Scroll"

    override fun actionDescription(): String =
      "Scroll down through the current scrollable content (lists, reviews, long pages). PREFER this over Swipe when reading or advancing through content: it stays within the page and will not dismiss a modal sheet."

    override fun arguments(): List<AgentActionType.Argument> = emptyList()
  }
}

@Serializable
public data class SwipeAgentAction(val direction: SwipeDirection) : ArbigentAgentAction {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Swipe: ${direction.name}"
  }

  override fun runDeviceAction(runInput: ArbigentAgentAction.RunInput) {
    runInput.device.executeActions(
      actions = listOf(
        MaestroCommand(
          swipeCommand = SwipeCommand(direction = direction)
        )
      ),
    )
  }

  public companion object : AgentActionType {
    override val actionName: String = "Swipe"

    override fun actionDescription(): String =
      "Swipe in a direction for paging/carousels or to reveal off-screen UI. To scroll through content (lists, reviews, long pages) prefer Scroll instead: on iOS a large Swipe inside a modal sheet (e.g. an App Store product page) can dismiss the sheet and lose your place. UP moves content up; DOWN moves back up."

    override fun arguments(): List<AgentActionType.Argument> =
      listOf(
        AgentActionType.Argument(
          name = "text",
          type = "string",
          description = "Swipe direction: UP, DOWN, LEFT, or RIGHT. Prefer Swipe DOWN when content was overscrolled past the target; prefer Swipe UP to reveal lower content."
        )
      )
  }
}

/**
 * Drag/swipe from a start point to an end point — arbitrary start position,
 * direction, and length. Coordinates are NORMALIZED percent [0,100] of the
 * screenshot, emitted as Maestro startRelative/endRelative ("x%,y%") so each
 * backend scales against its own grid (resolution-independent, cap-invariant).
 * Use when the 4-direction Swipe is too coarse: sliders, dragging a specific
 * item, reordering, or a short/precise scroll.
 */
@Serializable
public data class DragAgentAction(
  val startXPercent: Int,
  val startYPercent: Int,
  val endXPercent: Int,
  val endYPercent: Int,
) : ArbigentAgentAction {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Drag: (${startXPercent}%, ${startYPercent}%) -> (${endXPercent}%, ${endYPercent}%)"
  }

  override fun runDeviceAction(runInput: ArbigentAgentAction.RunInput) {
    fun clamp(v: Int) = v.coerceIn(0, 100)
    runInput.device.executeActions(
      actions = listOf(
        MaestroCommand(
          swipeCommand = SwipeCommand(
            startRelative = "${clamp(startXPercent)}%,${clamp(startYPercent)}%",
            endRelative = "${clamp(endXPercent)}%,${clamp(endYPercent)}%",
          )
        )
      ),
    )
  }

  public companion object : AgentActionType {
    override val actionName: String = "Drag"

    override fun actionDescription(): String =
      "Drag/swipe from a start point to an end point (any direction, length, and position). Use when the 4-direction Swipe is too coarse: sliders, dragging a specific item, or a precise short scroll."

    override fun arguments(): List<AgentActionType.Argument> =
      listOf(
        AgentActionType.Argument(
          name = "text",
          type = "string",
          description = "Start and end as normalized fractions in [0,1]: \"startX,startY,endX,endY\" (top-left origin; 0,0 = top-left, 1,1 = bottom-right). Example: drag from center upward = \"0.5,0.5,0.5,0.2\"."
        )
      )
  }
}

@Serializable
public data class KeyPressAgentAction(val keyName: String) : ArbigentAgentAction {
  override val actionName: String = "KeyPress"

  override fun stepLogText(): String {
    return "Press key: $keyName"
  }

  override fun runDeviceAction(runInput: ArbigentAgentAction.RunInput) {
    val code = KeyCode.getByName(keyName)
      ?: throw MaestroException.InvalidCommand(message = "Unknown key: $keyName")
    runInput.device.executeActions(
      actions = listOf(
        MaestroCommand(
          pressKeyCommand = PressKeyCommand(
            code
          )
        )
      ),
    )
  }

  public companion object : AgentActionType {
    override val actionName: String = "KeyPress"

    override fun actionDescription(): String = "Press a specific key on the device"

    override fun arguments(): List<AgentActionType.Argument> =
      listOf(
        AgentActionType.Argument(
          name = "text",
          type = "string",
          description = "The name of the key to press (e.g., ENTER, TAB, etc.)"
        )
      )
  }
}

@Serializable
public class WaitAgentAction(private val timeMs: Int) : ArbigentAgentAction {
  override val actionName: String = "Wait"

  override fun stepLogText(): String {
    return "Wait for $timeMs ms"
  }

  override fun runDeviceAction(runInput: ArbigentAgentAction.RunInput) {
    Thread.sleep(timeMs.toLong())
  }

  public companion object : AgentActionType {
    override val actionName: String = "Wait"

    override fun actionDescription(): String = "Wait for a specified amount of time"

    override fun arguments(): List<AgentActionType.Argument> =
      listOf(
        AgentActionType.Argument(
          name = "text",
          type = "string",
          description = "Time to wait in milliseconds (e.g., \"1000\" for 1 second)"
        )
      )
  }
}

@Serializable
public class GoalAchievedAgentAction : ArbigentAgentAction {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Goal achieved"
  }

  override fun runDeviceAction(runInput: ArbigentAgentAction.RunInput) {
  }

  public companion object : AgentActionType {
    override val actionName: String = "GoalAchieved"

    override fun actionDescription(): String =
      "Indicate that the goal has been achieved only after every specific goal constraint has visible or previously recorded evidence"

    override fun arguments(): List<AgentActionType.Argument> = emptyList()
  }
}

@Serializable
public class FailedAgentAction : ArbigentAgentAction {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Failed"
  }

  override fun runDeviceAction(runInput: ArbigentAgentAction.RunInput) {
  }

  public companion object : AgentActionType {
    override val actionName: String = "Failed"

    override fun actionDescription(): String = "Indicate that the test scenario has failed"

    override fun arguments(): List<AgentActionType.Argument> =
      listOf(
        AgentActionType.Argument(
          name = "text",
          type = "string",
          description = "The reason why the test scenario failed"
        )
      )
  }
}

@Serializable
public data class ExecuteMcpToolAgentAction(
  val tool: MCPTool,
  val executeToolArgs: ExecuteToolArgs
) : ArbigentAgentAction {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Execute MCP tool: ${tool.name} with args: ${executeToolArgs.arguments}"
  }

  override fun runDeviceAction(runInput: ArbigentAgentAction.RunInput) {
    // This is a no-op for device actions, as tool execution is handled separately
  }

  public companion object : AgentActionType {
    override val actionName: String = "ExecuteTool"

    override fun actionDescription(): String = "Execute a tool via MCP"

    override fun arguments(): List<AgentActionType.Argument> =
      listOf(
        AgentActionType.Argument(
          name = "tool",
          type = "object",
          description = "The tool to execute"
        ),
        AgentActionType.Argument(
          name = "args",
          type = "object",
          description = "The arguments for the tool"
        )
      )
  }
}
