package io.github.takahirom.arbigent

import dadb.Dadb
import maestro.utils.TempFileHandler
import util.LocalSimulatorUtils

@ArbigentInternalApi
public fun fetchAvailableDevicesByOs(deviceType: ArbigentDeviceOs): List<ArbigentAvailableDevice> {
  return when (deviceType) {
    ArbigentDeviceOs.Android -> {
      // Opt-in device targeting: when ANDROID_SERIAL (or ARBIGENT_ANDROID_DEVICE_ID)
      // is set, select the matching device by `ro.serialno`. dadb's Dadb.list()
      // does not filter by serial, so without this the CLI's firstOrNull() picks an
      // arbitrary device when several Androids are attached. Default (env unset)
      // behavior is unchanged.
      val requested = (System.getenv("ANDROID_SERIAL")
        ?: System.getenv("ARBIGENT_ANDROID_DEVICE_ID"))?.takeIf { it.isNotBlank() }
      val all = Dadb.list()
      val selected = if (requested == null) {
        all
      } else {
        val matched = all.filter { dadb ->
          runCatching { dadb.shell("getprop ro.serialno").output.trim() == requested }
            .getOrDefault(false)
        }
        if (matched.isEmpty()) {
          all.forEach { runCatching { it.close() } }
          throw IllegalArgumentException(
            "No connected Android device matches ANDROID_SERIAL/ARBIGENT_ANDROID_DEVICE_ID=$requested " +
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
        listOf(ArbigentAvailableDevice.IOSRealMirror(IosRealMirrorDeviceConfig.fromEnvironment()))
      } else {
        realIosXCTestDevices() + bootedIosSimulators()
      }
    }

    else -> {
      listOf(ArbigentAvailableDevice.Web())
    }
  }
}

private fun realIosXCTestDevices(): List<ArbigentAvailableDevice.IOSRealXCTest> {
  if (IosRealXCTestDeviceConfig.isSuppressedByMirrorBackend()) return emptyList()
  val requestedDeviceId = IosRealXCTestDeviceConfig.requestedDeviceIdFromEnvironment()
  return runCatching {
    IosRealDeviceCatalog.availableDevices(requestedDeviceId)
      .map { ArbigentAvailableDevice.IOSRealXCTest(IosRealXCTestDeviceConfig.fromEnvironment(it)) }
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
