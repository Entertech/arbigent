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
  public val appleTeamId: String?,
  public val driverProductsDir: String?,
  public val buildDriver: Boolean,
) {
  public companion object {
    public const val BACKEND_ENV: String = "ARBIGENT_IOS_REAL_BACKEND"
    public const val DEVICE_ID_ENV: String = "ARBIGENT_IOS_REAL_DEVICE_ID"
    public const val HOST_ENV: String = "ARBIGENT_IOS_XCTEST_HOST"
    public const val PORT_ENV: String = "ARBIGENT_IOS_XCTEST_PORT"
    public const val AUTO_IPROXY_ENV: String = "ARBIGENT_IOS_XCTEST_AUTO_IPROXY"
    public const val PREBUILT_RUNNER_ENV: String = "ARBIGENT_IOS_XCTEST_PREBUILT_RUNNER"
    public const val REINSTALL_DRIVER_ENV: String = "ARBIGENT_IOS_XCTEST_REINSTALL_DRIVER"
    public const val XCTESTRUN_ENV: String = "ARBIGENT_IOS_XCTEST_XCTESTRUN"
    public const val APPLE_TEAM_ID_ENV: String = "ARBIGENT_IOS_XCTEST_APPLE_TEAM_ID"
    public const val DRIVER_PRODUCTS_DIR_ENV: String = "ARBIGENT_IOS_XCTEST_DRIVER_PRODUCTS_DIR"
    public const val BUILD_DRIVER_ENV: String = "ARBIGENT_IOS_XCTEST_BUILD_DRIVER"

    public const val DEVICE_ID_SETTING: String = "ios-real-device-id"
    public const val HOST_SETTING: String = "ios-xctest-host"
    public const val PORT_SETTING: String = "ios-xctest-port"
    public const val AUTO_IPROXY_SETTING: String = "ios-xctest-auto-iproxy"
    public const val PREBUILT_RUNNER_SETTING: String = "ios-xctest-prebuilt-runner"
    public const val REINSTALL_DRIVER_SETTING: String = "ios-xctest-reinstall-driver"
    public const val XCTESTRUN_SETTING: String = "ios-xctest-xctestrun"
    public const val APPLE_TEAM_ID_SETTING: String = "ios-xctest-apple-team-id"
    public const val DRIVER_PRODUCTS_DIR_SETTING: String = "ios-xctest-driver-products-dir"
    public const val BUILD_DRIVER_SETTING: String = "ios-xctest-build-driver"
    // Post-action settle timeout (ms) for the XCTest screen-static wait. Lower it
    // for animation/video-heavy screens that never go byte-identical static and
    // would otherwise burn the full default (3000ms) every action.
    public const val SETTLE_TIMEOUT_SETTING: String = "ios-settle-timeout-ms"

    public const val SETTLE_TIMEOUT_ENV: String = "ARBIGENT_IOS_SETTLE_TIMEOUT_MS"
    // JVM system property the looktech Maestro fork reads at settle call-time.
    private const val MAESTRO_SETTLE_PROPERTY = "maestro.ios.screenSettleTimeoutMs"

    private const val MAESTRO_DEVICE_ID_ENV = "MAESTRO_IOS_MIRROR_DEVICE_ID"
    private const val DEVELOPMENT_TEAM_ENV = "DEVELOPMENT_TEAM"
    private val mirrorOnlyBackends = setOf("mirror", "mirroir")

    public fun isSuppressedByMirrorBackend(): Boolean {
      return System.getenv(BACKEND_ENV)?.lowercase() in mirrorOnlyBackends
    }

    public fun fromEnvironment(): IosRealXCTestDeviceConfig {
      val device = IosRealDeviceCatalog.resolveDevice(requestedDeviceIdFromEnvironment())
      return fromEnvironment(device)
    }

    internal fun requestedDeviceIdFromEnvironment(): String? {
      return settingOrEnv(DEVICE_ID_SETTING, DEVICE_ID_ENV)
        ?: System.getenv(MAESTRO_DEVICE_ID_ENV)?.takeIf { it.isNotBlank() }
    }

    internal fun fromEnvironment(device: IosRealDevice): IosRealXCTestDeviceConfig {
      applyScreenSettleTimeout()
      return IosRealXCTestDeviceConfig(
        deviceId = device.udid,
        deviceName = device.name,
        host = settingOrEnv(HOST_SETTING, HOST_ENV) ?: "127.0.0.1",
        port = settingOrEnv(PORT_SETTING, PORT_ENV)?.toIntOrNull() ?: 22087,
        autoStartIproxy = settingOrEnv(AUTO_IPROXY_SETTING, AUTO_IPROXY_ENV)?.toBooleanStrictOrNull() ?: true,
        preBuiltRunner = settingOrEnv(PREBUILT_RUNNER_SETTING, PREBUILT_RUNNER_ENV)?.toBooleanStrictOrNull() ?: false,
        reinstallDriver = settingOrEnv(REINSTALL_DRIVER_SETTING, REINSTALL_DRIVER_ENV)?.toBooleanStrictOrNull() ?: false,
        xctestrunFile = settingOrEnv(XCTESTRUN_SETTING, XCTESTRUN_ENV),
        appleTeamId = settingOrEnv(APPLE_TEAM_ID_SETTING, APPLE_TEAM_ID_ENV)
          ?: System.getenv(DEVELOPMENT_TEAM_ENV)?.takeIf { it.isNotBlank() }
          ?: IosCodeSigningTeamResolver.autoDetectTeamId(),
        driverProductsDir = settingOrEnv(DRIVER_PRODUCTS_DIR_SETTING, DRIVER_PRODUCTS_DIR_ENV),
        buildDriver = settingOrEnv(BUILD_DRIVER_SETTING, BUILD_DRIVER_ENV)?.toBooleanStrictOrNull() ?: true,
      )
    }

    private fun settingOrEnv(settingKey: String, envKey: String): String? {
      return ArbigentHostConfig.get(settingKey)
        ?: System.getenv(envKey)?.takeIf { it.isNotBlank() }
    }

    // Propagate the configured iOS settle timeout to the JVM system property the
    // looktech Maestro fork reads at settle call-time. Done here (before connect /
    // first action) so it takes effect for all subsequent actions. Invalid values
    // are ignored, leaving the Maestro default (3000ms).
    private fun applyScreenSettleTimeout() {
      val value = settingOrEnv(SETTLE_TIMEOUT_SETTING, SETTLE_TIMEOUT_ENV)?.toLongOrNull() ?: return
      System.setProperty(MAESTRO_SETTLE_PROPERTY, value.toString())
      arbigentInfoLog("iOS settle timeout set to ${value}ms ($MAESTRO_SETTLE_PROPERTY)")
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
    val devices = availableDevices(requestedDeviceId)
    return devices.firstOrNull()
      ?: throwNoDevice(requestedDeviceId)
  }

  fun availableDevices(requestedDeviceId: String?): List<IosRealDevice> {
    return selectDevices(pairedDevices(), requestedDeviceId)
  }

  internal fun selectDevices(
    pairedDevices: List<IosRealDevice>,
    requestedDeviceId: String?,
  ): List<IosRealDevice> {
    if (requestedDeviceId != null) {
      val selected = pairedDevices.firstOrNull { device ->
        device.udid == requestedDeviceId || device.coreDeviceIdentifier == requestedDeviceId
      } ?: throw IllegalArgumentException("No paired iOS real device matches $requestedDeviceId")
      // The user explicitly chose this device, so don't hard-fail on a transient
      // canConnect=false (the CoreDevice capability flag flaps while a physically
      // connected device negotiates). Warn and let connectToDevice() be the source
      // of truth — it surfaces a real error if the device is genuinely unreachable.
      if (!selected.canConnect) {
        arbigentWarnLog(
          "iOS real device $requestedDeviceId reports not-currently-connectable; attempting anyway. " +
            "If connection fails, unlock and reconnect the device (USB/Wi-Fi)."
        )
      }
      return listOf(selected)
    }
    // Only surface connectable devices when auto-selecting. Otherwise an offline
    // paired iPhone can shadow a booted simulator: DeviceFinder orders real
    // devices before simulators and the CLI takes the first candidate without
    // trying the next one, so it would connect-fail on the offline phone.
    return pairedDevices
      .filter { it.canConnect }
      .sortedWith(compareBy<IosRealDevice> { it.name }.thenBy { it.udid })
  }

  private fun pairedDevices(): List<IosRealDevice> {
    return listDevices()
      .filter { it.pairingState == "paired" }
  }

  private fun throwNoDevice(requestedDeviceId: String?): Nothing {
    if (requestedDeviceId != null) {
      throw IllegalArgumentException("No paired iOS real device matches $requestedDeviceId")
    }
    throw IllegalArgumentException("No paired iOS real device found by devicectl")
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
    // Redirect stderr to a file rather than an undrained PIPE: a large stderr
    // read only after waitFor can fill the pipe buffer and block the child,
    // turning a real failure into a misleading timeout.
    val errorFile = File.createTempFile("arbigent-devicectl-stderr", ".log")
    try {
      val process = ProcessBuilder(listOf("xcrun", "devicectl") + args)
        .redirectOutput(File(if (System.getProperty("os.name").startsWith("Windows")) "NUL" else "/dev/null"))
        .redirectError(errorFile)
        .start()
      if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        throw IllegalStateException("devicectl timed out: $args")
      }
      if (process.exitValue() != 0) {
        throw IllegalStateException("devicectl failed: $args\n${errorFile.readText()}")
      }
    } finally {
      errorFile.delete()
    }
  }

  private const val PROCESS_TIMEOUT_SECONDS = 120L
}

/**
 * devicectl-backed deviceController for iOS REAL devices.
 *
 * Maestro's DeviceControlIOSDevice is a pure stub — every method is
 * TODO("Not yet implemented"), and TODO() throws NotImplementedError, which is
 * an Error: it sails through Orchestra's catch(Exception) and Arbigent's action
 * handling and kills the whole process. LocalIOSDevice routes launch /
 * setPermissions / uninstall / clearAppState (among others) to the
 * deviceController, so e.g. a LaunchApp command on a real device died inside
 * the pre-launch setPermissions call before even attempting the launch.
 *
 * This wrapper delegates to the stub but overrides what we can actually do via
 * `xcrun devicectl` (the same ArbigentDevicectlClient the mirror backend uses):
 * - launch: devicectl process launch (--terminate-existing, so on real iOS a
 *   LaunchApp of an already-running app RELAUNCHES it; devicectl cannot
 *   activate without terminating)
 * - setPermissions: no-op — devicectl cannot set permissions; LocalIOSDevice
 *   calls xcTestDevice.setPermissions right after, which handles what it can
 * - uninstall / clearAppState: devicectl equivalents
 */
internal class ArbigentDevicectlIOSDevice(
  deviceId: String,
  private val stub: device.IOSDevice,
) : device.IOSDevice by stub {
  private val client = ArbigentDevicectlClient(deviceId)

  override fun launch(id: String, launchArguments: Map<String, Any>) {
    client.launch(id)
  }

  override fun setPermissions(id: String, permissions: Map<String, String>) {
    arbigentDebugLog("setPermissions($id) ignored on real iOS device (devicectl cannot set permissions)")
  }

  override fun uninstall(id: String) {
    client.uninstall(id)
  }

  override fun clearAppState(id: String) {
    client.clearAppState(id)
  }
}
