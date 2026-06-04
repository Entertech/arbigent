package io.github.takahirom.arbigent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

public data class IosRealXCTestDeviceConfig(
  public val deviceId: String,
  public val deviceName: String,
  public val host: String,
  public val port: Int,
  public val autoStartIproxy: Boolean,
  public val preBuiltRunner: Boolean,
  public val reinstallDriver: Boolean,
  public val xctestrunFile: String?,
) {
  public companion object {
    public const val DEVICE_KIND_ENV: String = "ARBIGENT_IOS_DEVICE_KIND"
    public const val BACKEND_ENV: String = "ARBIGENT_IOS_REAL_BACKEND"
    public const val DEVICE_ID_ENV: String = "ARBIGENT_IOS_REAL_DEVICE_ID"
    public const val HOST_ENV: String = "ARBIGENT_IOS_XCTEST_HOST"
    public const val PORT_ENV: String = "ARBIGENT_IOS_XCTEST_PORT"
    public const val AUTO_IPROXY_ENV: String = "ARBIGENT_IOS_XCTEST_AUTO_IPROXY"
    public const val PREBUILT_RUNNER_ENV: String = "ARBIGENT_IOS_XCTEST_PREBUILT_RUNNER"
    public const val REINSTALL_DRIVER_ENV: String = "ARBIGENT_IOS_XCTEST_REINSTALL_DRIVER"
    public const val XCTESTRUN_ENV: String = "ARBIGENT_IOS_XCTEST_XCTESTRUN"

    private const val MAESTRO_DEVICE_ID_ENV = "MAESTRO_IOS_MIRROR_DEVICE_ID"

    public fun isEnabled(): Boolean {
      val kind = System.getenv(DEVICE_KIND_ENV)?.lowercase()
      val backend = System.getenv(BACKEND_ENV)?.lowercase()
      return kind == "real" && backend !in setOf("mirror", "mirroir")
    }

    public fun fromEnvironment(): IosRealXCTestDeviceConfig {
      val requestedDeviceId = System.getenv(DEVICE_ID_ENV)?.takeIf { it.isNotBlank() }
        ?: System.getenv(MAESTRO_DEVICE_ID_ENV)?.takeIf { it.isNotBlank() }
      val device = IosRealDeviceCatalog.resolveDevice(requestedDeviceId)
      return IosRealXCTestDeviceConfig(
        deviceId = device.udid,
        deviceName = device.name,
        host = System.getenv(HOST_ENV)?.takeIf { it.isNotBlank() } ?: "127.0.0.1",
        port = System.getenv(PORT_ENV)?.toIntOrNull() ?: 22087,
        autoStartIproxy = System.getenv(AUTO_IPROXY_ENV)?.toBooleanStrictOrNull() ?: true,
        preBuiltRunner = System.getenv(PREBUILT_RUNNER_ENV)?.toBooleanStrictOrNull() ?: false,
        reinstallDriver = System.getenv(REINSTALL_DRIVER_ENV)?.toBooleanStrictOrNull() ?: false,
        xctestrunFile = System.getenv(XCTESTRUN_ENV)?.takeIf { it.isNotBlank() },
      )
    }
  }
}

internal data class IosRealDevice(
  val coreDeviceIdentifier: String,
  val udid: String,
  val name: String,
  val modelName: String,
  val pairingState: String,
  val tunnelState: String,
  val canConnect: Boolean,
)

internal object IosRealDeviceCatalog {
  private val mapper = jacksonObjectMapper()

  fun resolveDevice(requestedDeviceId: String?): IosRealDevice {
    val devices = listDevices()
      .filter { it.pairingState == "paired" }
    if (requestedDeviceId != null) {
      return devices.firstOrNull { device ->
        device.udid == requestedDeviceId || device.coreDeviceIdentifier == requestedDeviceId
      } ?: throw IllegalArgumentException("No paired iOS real device matches $requestedDeviceId")
    }
    return devices.firstOrNull { it.canConnect }
      ?: devices.firstOrNull()
      ?: throw IllegalArgumentException("No paired iOS real device found by devicectl")
  }

  fun installedBundleIds(deviceId: String): Set<String> {
    val result = runJson(
      "device",
      "info",
      "apps",
      "--device",
      deviceId,
      "--include-all-apps",
    )["result"] ?: return emptySet()
    return result["apps"]
      ?.mapNotNull { app ->
        app["bundleIdentifier"]?.asText()
          ?: app["bundleID"]?.asText()
          ?: app["bundleId"]?.asText()
          ?: bundleIdFromUrl(app["url"]?.asText())
      }
      ?.toSet()
      ?: emptySet()
  }

  private fun listDevices(): List<IosRealDevice> {
    val result = runJson("list", "devices")["result"] ?: return emptyList()
    return result["devices"]?.mapNotNull(::parseDevice) ?: emptyList()
  }

  internal fun parseDevice(node: JsonNode): IosRealDevice? {
    val hardware = node["hardwareProperties"] ?: return null
    if (hardware["reality"]?.asText() != "physical") return null
    val udid = hardware["udid"]?.asText()?.takeIf { it.isNotBlank() } ?: return null
    val connection = node["connectionProperties"]
    val deviceProperties = node["deviceProperties"]
    return IosRealDevice(
      coreDeviceIdentifier = node["identifier"]?.asText().orEmpty(),
      udid = udid,
      name = deviceProperties?.get("name")?.asText() ?: udid,
      modelName = hardware["marketingName"]?.asText().orEmpty(),
      pairingState = connection?.get("pairingState")?.asText().orEmpty(),
      tunnelState = connection?.get("tunnelState")?.asText().orEmpty(),
      canConnect = node["capabilities"]?.any {
        it["featureIdentifier"]?.asText() == "com.apple.coredevice.feature.connectdevice"
      } ?: false,
    )
  }

  private fun bundleIdFromUrl(url: String?): String? {
    if (url == null) return null
    return runCatching {
      URI(url).path.substringAfterLast('/').removeSuffix(".app").takeIf { it.isNotBlank() }
    }.getOrNull()
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

  private const val PROCESS_TIMEOUT_SECONDS = 120L
}
