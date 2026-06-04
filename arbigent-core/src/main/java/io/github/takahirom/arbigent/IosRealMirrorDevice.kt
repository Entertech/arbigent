package io.github.takahirom.arbigent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.github.takahirom.arbigent.result.ArbigentUiTreeStrings
import maestro.KeyCode
import maestro.TreeNode
import maestro.orchestra.MaestroCommand
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

public data class IosRealMirrorDeviceConfig(
  public val deviceId: String?,
  public val mcpCommand: String,
  public val timeoutMs: Long,
) {
  public companion object {
    public const val BACKEND_ENV: String = "ARBIGENT_IOS_REAL_BACKEND"
    public const val DEVICE_ID_ENV: String = "ARBIGENT_IOS_REAL_DEVICE_ID"
    public const val MCP_COMMAND_ENV: String = "ARBIGENT_IOS_MIRROR_MCP_COMMAND"
    public const val MCP_TIMEOUT_ENV: String = "ARBIGENT_IOS_MIRROR_MCP_TIMEOUT_MS"

    private const val MAESTRO_DEVICE_ID_ENV = "MAESTRO_IOS_MIRROR_DEVICE_ID"
    private const val MAESTRO_MCP_COMMAND_ENV = "MAESTRO_IOS_MIRROR_MCP_COMMAND"
    private const val MAESTRO_MCP_TIMEOUT_ENV = "MAESTRO_IOS_MIRROR_MCP_TIMEOUT_MS"

    public fun isEnabled(): Boolean {
      return System.getenv(BACKEND_ENV)?.lowercase() in setOf("mirror", "mirroir")
    }

    public fun fromEnvironment(): IosRealMirrorDeviceConfig {
      return IosRealMirrorDeviceConfig(
        deviceId = env(DEVICE_ID_ENV, MAESTRO_DEVICE_ID_ENV),
        mcpCommand = env(MCP_COMMAND_ENV, MAESTRO_MCP_COMMAND_ENV) ?: defaultMcpCommand(),
        timeoutMs = env(MCP_TIMEOUT_ENV, MAESTRO_MCP_TIMEOUT_ENV)?.toLongOrNull() ?: 60_000L,
      )
    }

    private fun env(primary: String, fallback: String): String? {
      return System.getenv(primary)?.takeIf { it.isNotBlank() }
        ?: System.getenv(fallback)?.takeIf { it.isNotBlank() }
    }

    private fun defaultMcpCommand(): String {
      return if (commandExists("mirroir-mcp")) {
        "mirroir-mcp"
      } else {
        "npx -y mirroir-mcp@0.33.3"
      }
    }

    private fun commandExists(command: String): Boolean {
      return runCatching {
        val process = ProcessBuilder("/bin/zsh", "-lc", "command -v $command >/dev/null 2>&1")
          .start()
        process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0
      }.getOrDefault(false)
    }
  }
}

internal class IosRealMirrorDevice(
  private val config: IosRealMirrorDeviceConfig,
  private val mcpClient: ArbigentMirroirMcpClient = ArbigentMirroirMcpClient(
    command = config.mcpCommand,
    timeoutMs = config.timeoutMs,
  ),
  private val devicectl: ArbigentDevicectlClient? = config.deviceId?.let(::ArbigentDevicectlClient),
) : ArbigentDevice {
  private var closed: Boolean = false
  private var cachedScreen: IosMirrorScreen? = null

  init {
    val health = mcpClient.callTool("check_health").text
    if (health.contains("[FAIL]") || health.contains("Issues detected")) {
      throw IllegalStateException(
        "iOS real mirror backend is not ready.\n$health\n" +
          "Unlock the Mac and iPhone, open iPhone Mirroring, and resume the mirrored session."
      )
    }
  }

  override fun deviceName(): String = "iOS real mirror"

  override fun executeActions(actions: List<MaestroCommand>) {
    actions.forEach { command ->
      command.takeScreenshotCommand?.let {
        val screenshotFile = File(ArbigentFiles.screenshotsDir, "${it.path}.png")
        screenshotFile.parentFile?.mkdirs()
        screenshotFile.writeBytes(screenshotBytes())
        cachedScreen = null
        return@forEach
      }
      command.tapOnPointV2Command?.let {
        val (x, y) = parsePoint(it.point, screen())
        if (it.longPress == true) {
          mcpClient.callTool("long_press", mapOf("x" to x, "y" to y, "duration_ms" to 800, "cursor_mode" to "direct"))
        } else {
          mcpClient.callTool("tap", mapOf("x" to x, "y" to y, "cursor_mode" to "direct"))
        }
        cachedScreen = null
        return@forEach
      }
      command.inputTextCommand?.let {
        mcpClient.callTool("type_text", mapOf("text" to it.text))
        cachedScreen = null
        return@forEach
      }
      command.scrollCommand?.let {
        val screen = screen()
        mcpClient.callTool(
          "swipe",
          mapOf(
            "from_x" to screen.width / 2,
            "from_y" to (screen.height * 0.78).toInt(),
            "to_x" to screen.width / 2,
            "to_y" to (screen.height * 0.28).toInt(),
            "duration_ms" to 450,
            "cursor_mode" to "direct",
          )
        )
        cachedScreen = null
        return@forEach
      }
      command.pressKeyCommand?.let {
        mcpClient.callTool("press_key", mapOf("key" to it.code.toMirroirKeyName()))
        cachedScreen = null
        return@forEach
      }
      command.backPressCommand?.let {
        mcpClient.callTool("press_back")
        cachedScreen = null
        return@forEach
      }
      command.launchAppCommand?.let {
        if (it.clearState == true) {
          devicectl?.clearAppState(it.appId)
        }
        launchApp(it.appId)
        cachedScreen = null
        return@forEach
      }
      command.openLinkCommand?.let {
        openLink(it.link)
        cachedScreen = null
        return@forEach
      }
      command.clearStateCommand?.let {
        devicectl?.clearAppState(it.appId)
          ?: throw UnsupportedOperationException("clearState requires $DEVICE_ID_HINT")
        cachedScreen = null
        return@forEach
      }
      throw UnsupportedOperationException("Unsupported command for iOS real mirror backend: $command")
    }
  }

  override fun viewTreeString(): ArbigentUiTreeStrings {
    val screen = screen()
    val tree = screen.elements.joinToString("\n") { element ->
      "MirrorElement(text=${element.text}, x=${element.x}, y=${element.y})"
    }
    return ArbigentUiTreeStrings(
      allTreeString = tree,
      optimizedTreeString = tree,
    )
  }

  override fun focusedTreeString(): String = ""

  override fun elements(): ArbigentElementList {
    val screen = screen()
    return ArbigentElementList(
      elements = screen.elements.mapIndexed { index, element ->
        val rect = element.bounds(screen.width, screen.height)
        ArbigentElement(
          index = index,
          textForAI = "MirrorElement(text=${element.text}, center=${element.x.toInt()},${element.y.toInt()})",
          rawText = element.text,
          identifierData = ArbigentElement.IdentifierData(listOf(element.text, index), 0),
          treeNode = TreeNode(
            attributes = mutableMapOf(
              "text" to element.text,
              "content-desc" to element.text,
              "class" to "MirrorElement",
              "enabled" to "true",
              "clickable" to "true",
            ),
            children = emptyList(),
            clickable = true,
            enabled = true,
          ),
          x = rect.left,
          y = rect.top,
          width = rect.width(),
          height = rect.height(),
          isVisible = true,
        )
      },
      screenWidth = screen.width,
    )
  }

  override fun waitForAppToSettle(appId: String?) {
    Thread.sleep(750)
  }

  override fun os(): ArbigentDeviceOs = ArbigentDeviceOs.Ios

  override fun close() {
    if (closed) return
    closed = true
    mcpClient.close()
  }

  override fun isClosed(): Boolean = closed

  private fun launchApp(appIdOrName: String) {
    val launchedByDevicectl = if (appIdOrName.contains(".")) {
      devicectl?.let { runCatching { it.launch(appIdOrName) }.isSuccess } ?: false
    } else {
      false
    }
    if (!launchedByDevicectl) {
      mcpClient.callTool("launch_app", mapOf("name" to appIdOrName))
    }
  }

  private fun openLink(link: String) {
    if (link.startsWith("music://") || link.startsWith("musics://")) {
      devicectl?.openUrl("com.apple.Music", link)
        ?: mcpClient.callTool("launch_app", mapOf("name" to "Music"))
      return
    }
    val openedByDevicectl = devicectl?.let {
      runCatching { it.openUrl("com.apple.mobilesafari", link) }.isSuccess
    } ?: false
    if (!openedByDevicectl) {
      mcpClient.callTool("open_url", mapOf("url" to link))
    }
  }

  private fun screen(): IosMirrorScreen {
    return cachedScreen ?: fetchScreen().also { cachedScreen = it }
  }

  private fun fetchScreen(): IosMirrorScreen {
    val targetInfo = mcpClient.callTool("list_targets").text
    val windowSize = IosMirrorScreenParser.parseWindowSize(targetInfo)
      ?: IosMirrorScreenParser.parseWindowSize(mcpClient.callTool("get_orientation").text)
      ?: error("Unable to read iOS mirror window size: $targetInfo")
    val description = mcpClient.callTool("describe_screen", mapOf("omit_screenshot" to true)).text
    return IosMirrorScreen(
      width = windowSize.width,
      height = windowSize.height,
      elements = IosMirrorScreenParser.parseElements(description),
      description = description,
    )
  }

  private fun screenshotBytes(): ByteArray {
    val result = mcpClient.callTool("screenshot")
    val imageBase64 = result.imageBase64
      ?: throw IllegalStateException("mirroir-mcp screenshot returned no image data: ${result.text}")
    return Base64.getDecoder().decode(imageBase64)
  }

  private fun parsePoint(point: String, screen: IosMirrorScreen): Pair<Int, Int> {
    val parts = point.split(",").map { it.trim() }
    require(parts.size == 2) { "Point must be x,y or x%,y%, got: $point" }
    fun parsePart(raw: String, total: Int): Int {
      return if (raw.endsWith("%")) {
        ((raw.removeSuffix("%").toDouble() / 100.0) * total).toInt()
      } else {
        raw.toDouble().toInt()
      }
    }
    return parsePart(parts[0], screen.width) to parsePart(parts[1], screen.height)
  }

  private fun KeyCode.toMirroirKeyName(): String {
    return when (this) {
      KeyCode.ENTER -> "return"
      KeyCode.BACKSPACE -> "delete"
      KeyCode.TAB -> "tab"
      else -> name.lowercase()
    }
  }

  private data class Rect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
  ) {
    fun width(): Int = right - left
    fun height(): Int = bottom - top
  }

  private fun IosMirrorElement.bounds(width: Int, height: Int): Rect {
    val left = max(0, x.toInt() - ELEMENT_HALF_SIZE)
    val top = max(0, y.toInt() - ELEMENT_HALF_SIZE)
    val right = min(width, x.toInt() + ELEMENT_HALF_SIZE)
    val bottom = min(height, y.toInt() + ELEMENT_HALF_SIZE)
    return Rect(
      left = left,
      top = top,
      right = max(left + 1, right),
      bottom = max(top + 1, bottom),
    )
  }

  private companion object {
    const val ELEMENT_HALF_SIZE = 24
    const val DEVICE_ID_HINT = "ARBIGENT_IOS_REAL_DEVICE_ID or MAESTRO_IOS_MIRROR_DEVICE_ID"
  }
}

internal class ArbigentMirroirMcpClient(
  private val command: String,
  private val timeoutMs: Long,
) : AutoCloseable {
  private val mapper: ObjectMapper = jacksonObjectMapper()
  private val nextId = AtomicInteger(1)
  private val pending = ConcurrentHashMap<Int, CompletableFuture<JsonNode>>()
  private var process: Process? = null
  private var writer: BufferedWriter? = null

  @Synchronized
  fun callTool(name: String, arguments: Map<String, Any?> = emptyMap()): MirroirToolResult {
    ensureStarted()
    val params = mapper.createObjectNode()
      .put("name", name)
      .set<ObjectNode>("arguments", mapper.valueToTree(arguments))
    val response = sendRequest("tools/call", params)
    val result = response["result"] ?: error("mirroir-mcp returned no result for tool '$name': $response")
    val toolResult = MirroirToolResult.fromJson(result)
    if (toolResult.isError) {
      throw IllegalStateException(toolResult.text.ifBlank { "mirroir-mcp tool '$name' failed" })
    }
    return toolResult
  }

  @Synchronized
  private fun ensureStarted() {
    if (process?.isAlive == true && writer != null) return
    val startedProcess = ProcessBuilder("/bin/zsh", "-lc", command)
      .redirectError(ProcessBuilder.Redirect.PIPE)
      .start()
    process = startedProcess
    writer = BufferedWriter(OutputStreamWriter(startedProcess.outputStream, StandardCharsets.UTF_8))

    thread(name = "arbigent-mirroir-mcp-stdout", isDaemon = true) {
      startedProcess.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
        lines.forEach(::handleOutputLine)
      }
    }
    thread(name = "arbigent-mirroir-mcp-stderr", isDaemon = true) {
      startedProcess.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
        lines.forEach { arbigentDebugLog("mirroir-mcp stderr: $it") }
      }
    }

    val initParams = mapper.createObjectNode()
      .put("protocolVersion", "2024-11-05")
      .set<ObjectNode>("capabilities", mapper.createObjectNode())
      .set<ObjectNode>(
        "clientInfo",
        mapper.createObjectNode()
          .put("name", "arbigent")
          .put("version", "ios-real-mirror")
      )
    sendRequest("initialize", initParams)
    sendNotification("notifications/initialized", mapper.createObjectNode())
  }

  private fun handleOutputLine(line: String) {
    val trimmed = line.trim()
    if (!trimmed.startsWith("{")) {
      arbigentDebugLog("mirroir-mcp: $line")
      return
    }
    val node = runCatching { mapper.readTree(trimmed) }.getOrNull() ?: return
    val id = node["id"]?.takeIf { it.isInt }?.asInt() ?: return
    pending.remove(id)?.complete(node)
  }

  private fun sendRequest(method: String, params: JsonNode): JsonNode {
    val id = nextId.getAndIncrement()
    val request = mapper.createObjectNode()
      .put("jsonrpc", "2.0")
      .put("id", id)
      .put("method", method)
      .set<ObjectNode>("params", params)
    val future = CompletableFuture<JsonNode>()
    pending[id] = future
    writeJsonLine(request)
    return try {
      future.get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
      pending.remove(id)
      throw IllegalStateException("Timed out waiting for mirroir-mcp method '$method'. Command: $command", e)
    } catch (e: ExecutionException) {
      pending.remove(id)
      throw IllegalStateException("mirroir-mcp method '$method' failed", e)
    }.also { response ->
      response["error"]?.let {
        throw IllegalStateException("mirroir-mcp request '$method' failed: $it")
      }
    }
  }

  private fun sendNotification(method: String, params: JsonNode) {
    val request = mapper.createObjectNode()
      .put("jsonrpc", "2.0")
      .put("method", method)
      .set<ObjectNode>("params", params)
    writeJsonLine(request)
  }

  private fun writeJsonLine(node: JsonNode) {
    val activeWriter = writer ?: error("mirroir-mcp process is not started")
    activeWriter.write(mapper.writeValueAsString(node))
    activeWriter.newLine()
    activeWriter.flush()
  }

  override fun close() {
    pending.values.forEach { it.cancel(true) }
    pending.clear()
    runCatching { writer?.close() }
    process?.destroy()
    if (process?.waitFor(2, TimeUnit.SECONDS) == false) {
      process?.destroyForcibly()
    }
    writer = null
    process = null
  }
}

internal data class MirroirToolResult(
  val text: String,
  val imageBase64: String?,
  val isError: Boolean,
) {
  companion object {
    fun fromJson(result: JsonNode): MirroirToolResult {
      val content = result["content"] ?: error("mirroir-mcp result has no content: $result")
      val text = buildString {
        content.forEach { item ->
          if (item["type"]?.asText() == "text") {
            if (isNotEmpty()) appendLine()
            append(item["text"]?.asText().orEmpty())
          }
        }
      }
      val imageBase64 = content.firstOrNull { it["type"]?.asText() == "image" }
        ?.let { it["data"]?.asText() ?: it["image"]?.asText() }
      return MirroirToolResult(
        text = text,
        imageBase64 = imageBase64,
        isError = result["isError"]?.asBoolean(false) ?: false,
      )
    }
  }
}

internal class ArbigentDevicectlClient(
  private val deviceId: String,
) {
  private val mapper = jacksonObjectMapper()

  fun launch(bundleId: String) {
    runPlain("device", "process", "launch", "--terminate-existing", "--device", deviceId, bundleId)
  }

  fun openUrl(bundleId: String, url: String) {
    runPlain(
      "device",
      "process",
      "launch",
      "--terminate-existing",
      "--device",
      deviceId,
      bundleId,
      "--payload-url",
      url,
    )
  }

  fun clearAppState(bundleId: String) {
    terminate(bundleId)
    val emptyDirectory = Files.createTempDirectory("arbigent-ios-empty-app-data-")
    try {
      runPlain(
        "device",
        "copy",
        "to",
        "--device",
        deviceId,
        "--source",
        emptyDirectory.toAbsolutePath().toString(),
        "--destination",
        "/",
        "--domain-type",
        "appDataContainer",
        "--domain-identifier",
        bundleId,
        "--remove-existing-content",
        "true",
      )
    } finally {
      emptyDirectory.toFile().deleteRecursively()
    }
  }

  private fun terminate(bundleId: String) {
    runningPids(bundleId).forEach { pid ->
      runPlain("device", "process", "terminate", "--device", deviceId, "--pid", pid.toString())
    }
  }

  private fun runningPids(bundleId: String): List<Int> {
    val appBundleName = appBundleName(bundleId) ?: return emptyList()
    val appSegment = "/$appBundleName.app/"
    val result = runJson("device", "info", "processes", "--device", deviceId)["result"] ?: return emptyList()
    return result["runningProcesses"]
      ?.filter { process ->
        val executable = process["executable"]?.asText().orEmpty()
        executable.contains(appSegment) || executable.endsWith("/$appBundleName.app/$appBundleName")
      }
      ?.mapNotNull { it["processIdentifier"]?.asInt() }
      ?: emptyList()
  }

  private fun appBundleName(bundleId: String): String? {
    val result = runJson(
      "device",
      "info",
      "apps",
      "--device",
      deviceId,
      "--include-all-apps",
      "--bundle-id",
      bundleId,
    )["result"] ?: return null
    val appUrl = result["apps"]?.firstOrNull()?.get("url")?.asText() ?: return null
    val path = URI(appUrl).path.trimEnd('/')
    return path.substringAfterLast('/').removeSuffix(".app").ifBlank { null }
  }

  private fun runJson(vararg args: String): JsonNode {
    val jsonOutput = File.createTempFile("arbigent-devicectl", ".json")
    return try {
      runPlain(listOf("--json-output", jsonOutput.absolutePath) + args)
      mapper.readTree(jsonOutput)
    } finally {
      jsonOutput.delete()
    }
  }

  private fun runPlain(vararg args: String) {
    runPlain(args.toList())
  }

  private fun runPlain(args: List<String>) {
    val process = ProcessBuilder(listOf("xcrun", "devicectl") + args)
      .redirectOutput(File(if (System.getProperty("os.name").startsWith("Windows")) "NUL" else "/dev/null"))
      .redirectError(ProcessBuilder.Redirect.PIPE)
      .start()
    if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      throw IllegalStateException("devicectl timed out: $args")
    }
    if (process.exitValue() != 0) {
      val error = process.errorStream.bufferedReader().readText()
      throw IllegalStateException("devicectl failed: $args\n$error")
    }
  }

  private companion object {
    const val PROCESS_TIMEOUT_SECONDS = 120L
  }
}

internal object IosMirrorScreenParser {
  private val windowSizePattern = Regex("""window:\s*(\d+)x(\d+)""")
  private val targetSizePattern = Regex("""\([^)]*?(\d+)x(\d+)[^)]*?\)""")
  private val elementPattern = Regex("""^-\s+"(.*)"\s+at\s+\((-?\d+(?:\.\d+)?),\s*(-?\d+(?:\.\d+)?)\)""")

  fun parseWindowSize(text: String): IosMirrorWindowSize? {
    val match = windowSizePattern.find(text)
      ?: targetSizePattern.find(text)
      ?: return null
    return IosMirrorWindowSize(
      width = match.groupValues[1].toInt(),
      height = match.groupValues[2].toInt(),
    )
  }

  fun parseElements(text: String): List<IosMirrorElement> {
    return text
      .lineSequence()
      .mapNotNull { line ->
        val match = elementPattern.find(line.trim()) ?: return@mapNotNull null
        IosMirrorElement(
          text = match.groupValues[1],
          x = match.groupValues[2].toFloat(),
          y = match.groupValues[3].toFloat(),
        )
      }
      .toList()
  }
}

internal data class IosMirrorWindowSize(
  val width: Int,
  val height: Int,
)

internal data class IosMirrorScreen(
  val width: Int,
  val height: Int,
  val elements: List<IosMirrorElement>,
  val description: String,
)

internal data class IosMirrorElement(
  val text: String,
  val x: Float,
  val y: Float,
)
