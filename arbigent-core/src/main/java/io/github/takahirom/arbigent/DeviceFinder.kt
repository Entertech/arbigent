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
      if (IosRealMirrorDeviceConfig.isEnabled()) {
        val mirrors = listOf(ArbigentAvailableDevice.IOSRealMirror(IosRealMirrorDeviceConfig.fromEnvironment()))
        if (requestedDeviceId == null) {
          mirrors
        } else {
          mirrors.filter { it.id == requestedDeviceId }.ifEmpty {
            throw IllegalArgumentException("No iOS mirror device matches --device=$requestedDeviceId")
          }
        }
      } else if (requestedDeviceId == null) {
        // The env pin only narrows REAL-device selection (as before this parameter
        // existed); booted simulators stay visible alongside it.
        val envPin = IosRealXCTestDeviceConfig.requestedDeviceIdFromEnvironment()
          ?.takeIf { honorEnvironmentPins }
        realIosXCTestDevices(envPin, includeUnconnectable) + bootedIosSimulators()
      } else {
        // An explicit --device is exclusive. A UDID can name either a booted
        // simulator or a paired real device; prefer the simulator match because
        // the real-device catalog throws on ids it does not know.
        val simulators = bootedIosSimulators().filter { it.id == requestedDeviceId }
        simulators.ifEmpty { realIosXCTestDevices(requestedDeviceId, includeUnconnectable) }
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

private fun bootedIosSimulators(): List<ArbigentAvailableDevice.IOS> {
  return LocalSimulatorUtils(TempFileHandler()).list()
    .devices
    .flatMap { runtime ->
      runtime.value
        .filter { it.isAvailable && it.state == "Booted" }
    }
    .map { ArbigentAvailableDevice.IOS(it) }
}
