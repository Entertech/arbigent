package io.github.takahirom.arbigent

import dadb.Dadb
import maestro.utils.TempFileHandler
import util.LocalSimulatorUtils

/**
 * Lists devices available for [deviceType].
 *
 * When [requestedDeviceId] is set, only the matching device is returned
 * (Android serial / iOS UDID); a non-match throws with the requested id in the
 * message. When unset, the environment fallbacks keep working: ANDROID_SERIAL /
 * ARBIGENT_ANDROID_DEVICE_ID for Android, ARBIGENT_IOS_REAL_DEVICE_ID for iOS.
 */
@ArbigentInternalApi
public fun fetchAvailableDevicesByOs(
  deviceType: ArbigentDeviceOs,
  requestedDeviceId: String? = null,
  // For listings: also include paired-but-not-currently-connectable iOS devices.
  includeUnconnectable: Boolean = false,
  // For listings: a discovery command must show everything, so it disables the
  // ANDROID_SERIAL / ARBIGENT_* env pins (a stale value would otherwise make the
  // listing fail or hide devices exactly when the user is looking for valid ids).
  honorEnvironmentPins: Boolean = true,
  // Upstream's UI passes true to list every connected iOS device at once; in this fork that
  // maps onto includeUnconnectable (discovery goes through the fork's iOS real-device catalog).
  includeAllIosDevices: Boolean = false,
  // Upstream's explicit iOS real-device config. Only its deviceId is honored here (as a
  // fallback for requestedDeviceId); team id / port come from the fork's IosRealXCTestDeviceConfig.
  iosConfig: ArbigentIosRealDeviceConfiguration = ArbigentIosRealDeviceConfiguration(),
): List<ArbigentAvailableDevice> {
  return when (deviceType) {
    ArbigentDeviceOs.Android -> {
      // dadb's Dadb.list() does not filter by serial, so without this the CLI's
      // firstOrNull() picks an arbitrary device when several Androids are attached.
      val envPin = (System.getenv("ANDROID_SERIAL")
        ?: System.getenv("ARBIGENT_ANDROID_DEVICE_ID"))?.takeIf { honorEnvironmentPins }
      val requested = (requestedDeviceId ?: envPin)?.takeIf { it.isNotBlank() }
      val all = Dadb.list()
      val selected = if (requested == null) {
        all
      } else {
        // Accept both the Android serial (what `arbigent devices` normally prints)
        // and the adb transport id (dadb.toString(), e.g. "emulator-5554" — also the
        // listing's fallback id, and the only way to tell apart emulators that share
        // one ro.serialno).
        val matched = all.filter { dadb ->
          dadb.toString() == requested ||
            runCatching { dadb.shell("getprop ro.serialno").output.trim() == requested }
              .getOrDefault(false)
        }
        if (matched.isEmpty()) {
          all.forEach { runCatching { it.close() } }
          throw IllegalArgumentException(
            "No connected Android device matches --device/ANDROID_SERIAL/ARBIGENT_ANDROID_DEVICE_ID=$requested " +
              "(${all.size} device(s) attached)."
          )
        }
        all.filterNot { it in matched }.forEach { runCatching { it.close() } }
        matched
      }
      selected.map { ArbigentAvailableDevice.Android(it) }
    }

    ArbigentDeviceOs.Ios -> {
      val requestedIosId = requestedDeviceId ?: iosConfig.deviceId?.takeIf { it.isNotBlank() }
      val includeAll = includeUnconnectable || includeAllIosDevices
      if (IosRealMirrorDeviceConfig.isEnabled()) {
        val mirrors = listOf(ArbigentAvailableDevice.IOSRealMirror(IosRealMirrorDeviceConfig.fromEnvironment()))
        if (requestedIosId == null) {
          mirrors
        } else {
          mirrors.filter { it.id == requestedIosId }.ifEmpty {
            throw IllegalArgumentException("No iOS mirror device matches --device=$requestedIosId")
          }
        }
      } else if (requestedIosId == null) {
        // The env pin only narrows REAL-device selection (as before this parameter
        // existed); booted simulators stay visible alongside it.
        val envPin = IosRealXCTestDeviceConfig.requestedDeviceIdFromEnvironment()
          ?.takeIf { honorEnvironmentPins }
        realIosXCTestDevices(envPin, includeAll) + fetchBootedIosSimulators()
      } else {
        // An explicit --device is exclusive. A UDID can name either a booted
        // simulator or a paired real device; prefer the simulator match because
        // the real-device catalog throws on ids it does not know.
        val simulators = fetchBootedIosSimulators().filter { it.id == requestedIosId }
        simulators.ifEmpty { realIosXCTestDevices(requestedIosId, includeAll) }
      }
    }

    else -> {
      val web = ArbigentAvailableDevice.Web()
      if (requestedDeviceId != null && requestedDeviceId != web.id) {
        throw IllegalArgumentException(
          "--device=$requestedDeviceId is not applicable to web (the only web device is \"${web.id}\")."
        )
      }
      listOf(web)
    }
  }
}

private fun realIosXCTestDevices(
  requestedDeviceId: String?,
  includeUnconnectable: Boolean = false,
): List<ArbigentAvailableDevice.IOSRealXCTest> {
  if (IosRealXCTestDeviceConfig.isSuppressedByMirrorBackend()) return emptyList()
  return runCatching {
    IosRealDeviceCatalog.availableDevices(requestedDeviceId, includeUnconnectable)
      .map {
        ArbigentAvailableDevice.IOSRealXCTest(
          IosRealXCTestDeviceConfig.fromEnvironment(it),
          connectable = it.canConnect,
        )
      }
  }.getOrElse { throwable ->
    if (requestedDeviceId != null) throw throwable
    arbigentDebugLog("No iOS real XCTest device available: ${throwable.message}")
    emptyList()
  }
}

// LocalSimulatorUtils is now a class taking a TempFileHandler instead of an object. TempFileHandler
// is Closeable and we own this instance, so scope it with use {} to clean up any temp files.
private fun fetchBootedIosSimulators(): List<ArbigentAvailableDevice.IOS> =
  TempFileHandler().use { tempFileHandler ->
    LocalSimulatorUtils(tempFileHandler).list()
      .devices
      .flatMap { runtime ->
        runtime.value
          .filter { it.isAvailable && it.state == "Booted" }
      }
      .map { ArbigentAvailableDevice.IOS(it) }
  }

/**
 * Lists physical iPhones reachable over CoreDevice (`xcrun devicectl`), filtered to the ones with a
 * connected tunnel — the same criterion maestro uses. Returns empty (never throws) when devicectl
 * is unavailable or no device is connected, so simulator discovery keeps working without Xcode
 * command-line tools set up for real devices.
 *
 * CoreDevice only reports `tunnelState == connected` while a tunnel is actively held; a paired,
 * USB-connected iPhone otherwise lists as `disconnected` even though it is perfectly usable. When
 * the user has opted into real devices we first wake the tunnel with a read-only
 * `devicectl device info` (see [wakeIosRealDeviceTunnels]) so discovery is not flaky.
 */
@ArbigentInternalApi
public fun fetchConnectedIosRealDevices(
  wakeTunnels: Boolean = true,
  executor: ArbigentCommandExecutor = DefaultArbigentCommandExecutor(),
  config: ArbigentIosRealDeviceConfiguration = ArbigentIosRealDeviceConfiguration(),
): List<ArbigentAvailableDevice.IosReal> {
  val deviceIdFilter = ArbigentIosRealDeviceSettings.resolvedDeviceId(config)
  return try {
    val localDevice = util.LocalIOSDevice()
    var devices = localDevice.listDeviceViaDeviceCtl()
    fun isConnected(device: util.DeviceCtlResponse.Device) =
      device.connectionProperties?.tunnelState.equals("connected", ignoreCase = true)
    fun matchesFilter(device: util.DeviceCtlResponse.Device) =
      deviceIdFilter == null || device.hardwareProperties?.udid == deviceIdFilter
    // When a specific device is configured we only need THAT device's tunnel up, so it is enough
    // that it is connected — waking only when *nothing* is connected would strand a configured UDID
    // that is disconnected while some other iPhone happens to be connected. When no device is
    // configured (all-device discovery, e.g. the UI list), every paired-but-disconnected phone
    // should be surfaced, so we wake all of them rather than stopping at the first connected one.
    val needsWake = if (deviceIdFilter != null) {
      devices.none { isConnected(it) && matchesFilter(it) }
    } else {
      devices.any { matchesFilter(it) && !isConnected(it) }
    }
    if (needsWake && wakeTunnels) {
      val toWake = devices.filter { matchesFilter(it) && !isConnected(it) }.mapNotNull { it.identifier }
      wakeIosRealDeviceTunnels(toWake, executor)
      devices = localDevice.listDeviceViaDeviceCtl()
    }
    devices
      .filter { it.connectionProperties?.tunnelState.equals("connected", ignoreCase = true) }
      .mapNotNull { device ->
        val identifier = device.identifier ?: return@mapNotNull null
        val udid = device.hardwareProperties?.udid ?: return@mapNotNull null
        if (deviceIdFilter != null && udid != deviceIdFilter) return@mapNotNull null
        ArbigentAvailableDevice.IosReal(
          coreDeviceIdentifier = identifier,
          hardwareUdid = udid,
          name = device.deviceProperties?.name ?: udid,
          config = config,
        )
      }
  } catch (e: Exception) {
    arbigentInfoLog("iOS real device discovery skipped: ${e.message}")
    emptyList()
  }
}

// Reads device info to establish the CoreDevice tunnel (read-only; no device state changes), so the
// subsequent `list devices` reports the device as connected. Failures are ignored — a device that
// stays disconnected simply won't be surfaced.
private fun wakeIosRealDeviceTunnels(identifiers: List<String>, executor: ArbigentCommandExecutor) {
  identifiers.forEach { identifier ->
    arbigentInfoLog("iOS real device: waking CoreDevice tunnel for a paired device")
    executor.execute(
      listOf("xcrun", "devicectl", "device", "info", "details", "--device", identifier),
      timeoutMs = 20_000,
    )
  }
}

private fun isRealIosDeviceOptedIn(
  config: ArbigentIosRealDeviceConfiguration,
  env: (String) -> String? = System::getenv,
): Boolean = ArbigentIosRealDeviceSettings.isOptedIn(config, env)
