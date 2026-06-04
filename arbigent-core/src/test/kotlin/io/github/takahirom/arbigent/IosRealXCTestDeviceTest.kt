package io.github.takahirom.arbigent

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IosRealXCTestDeviceTest {
  private val mapper = jacksonObjectMapper()

  @Test
  fun `parseDevice accepts paired physical CoreDevice output`() {
    val json = """
      {
        "capabilities": [
          {
            "featureIdentifier": "com.apple.coredevice.feature.connectdevice",
            "name": "Connect to Device"
          }
        ],
        "connectionProperties": {
          "pairingState": "paired",
          "tunnelState": "disconnected"
        },
        "deviceProperties": {
          "name": "Borealin's iPhone 12 mini"
        },
        "hardwareProperties": {
          "marketingName": "iPhone 12 mini",
          "reality": "physical",
          "udid": "00008101-001D29020E42001E"
        },
        "identifier": "1D533177-5153-5A0C-9102-8D0A8ADDAEFB"
      }
    """.trimIndent()

    val device = IosRealDeviceCatalog.parseDevice(mapper.readTree(json))

    assertNotNull(device)
    assertEquals("1D533177-5153-5A0C-9102-8D0A8ADDAEFB", device.coreDeviceIdentifier)
    assertEquals("00008101-001D29020E42001E", device.udid)
    assertEquals("Borealin's iPhone 12 mini", device.name)
    assertEquals("iPhone 12 mini", device.modelName)
    assertEquals("paired", device.pairingState)
    assertEquals("disconnected", device.tunnelState)
    assertTrue(device.canConnect)
  }

  @Test
  fun `parseDevice ignores simulators`() {
    val json = """
      {
        "hardwareProperties": {
          "reality": "virtual",
          "udid": "SIMULATOR-UDID"
        },
        "identifier": "SIMULATOR-COREDEVICE"
      }
    """.trimIndent()

    val device = IosRealDeviceCatalog.parseDevice(mapper.readTree(json))

    assertEquals(null, device)
  }

  @Test
  fun `real device XCTest smoke`() {
    if (System.getenv("ARBIGENT_IOS_REAL_XCTEST_SMOKE") != "1") return

    val deviceId = System.getenv(IosRealXCTestDeviceConfig.DEVICE_ID_ENV)
      ?: "00008101-001D29020E42001E"
    val config = IosRealXCTestDeviceConfig(
      deviceId = deviceId,
      deviceName = "iOS real device smoke",
      host = System.getenv(IosRealXCTestDeviceConfig.HOST_ENV) ?: "127.0.0.1",
      port = System.getenv(IosRealXCTestDeviceConfig.PORT_ENV)?.toIntOrNull() ?: 22087,
      autoStartIproxy = System.getenv(IosRealXCTestDeviceConfig.AUTO_IPROXY_ENV)?.toBooleanStrictOrNull() ?: true,
      preBuiltRunner = System.getenv(IosRealXCTestDeviceConfig.PREBUILT_RUNNER_ENV)?.toBooleanStrictOrNull() ?: false,
      reinstallDriver = System.getenv(IosRealXCTestDeviceConfig.REINSTALL_DRIVER_ENV)?.toBooleanStrictOrNull() ?: false,
      xctestrunFile = System.getenv(IosRealXCTestDeviceConfig.XCTESTRUN_ENV)?.takeIf { it.isNotBlank() },
    )

    val device = ArbigentAvailableDevice.IOSRealXCTest(config).connectToDevice()
    try {
      val tree = device.viewTreeString()
      val elements = device.elements()

      assertFalse(tree.allTreeString.isBlank() && elements.elements.isEmpty())
    } finally {
      device.close()
    }
  }
}
