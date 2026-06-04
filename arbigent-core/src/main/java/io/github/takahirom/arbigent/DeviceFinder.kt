package io.github.takahirom.arbigent

import dadb.Dadb
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
      } else if (IosRealXCTestDeviceConfig.isEnabled()) {
        listOf(ArbigentAvailableDevice.IOSRealXCTest(IosRealXCTestDeviceConfig.fromEnvironment()))
      } else {
        LocalSimulatorUtils.list()
          .devices
          .flatMap { runtime ->
            runtime.value
              .filter { it.isAvailable && it.state == "Booted" }
          }
          .map { ArbigentAvailableDevice.IOS(it) }
      }
    }

    else -> {
      listOf(ArbigentAvailableDevice.Web())
    }
  }
}
