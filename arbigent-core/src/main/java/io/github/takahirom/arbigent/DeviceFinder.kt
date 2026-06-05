package io.github.takahirom.arbigent

import dadb.Dadb
import maestro.utils.TempFileHandler
import util.LocalSimulatorUtils

@ArbigentInternalApi
public fun fetchAvailableDevicesByOs(deviceType: ArbigentDeviceOs): List<ArbigentAvailableDevice> {
  return when (deviceType) {
    ArbigentDeviceOs.Android -> {
      Dadb.list().map { ArbigentAvailableDevice.Android(it) }
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
