package io.github.takahirom.arbigent

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ArbigentInternalApi::class)
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
  fun `parseTeamIds extracts unique Apple Team IDs`() {
    val output = """
        1) CF5CB1CD7C2C87E64364885372866FA2DA362C41 "Apple Development: Example (C29C23WKU8)"
        2) 02DA0804B5496F479BF5B4F7E3BCF296E675CC0D "Apple Distribution: Looktech Inc. (B6Y9D6S4KK)"
        3) 5FD3E2C08922871A4CB0E984BD2A4950C9E31F43 "Apple Development: Example (C29C23WKU8)"
           3 valid identities found
    """.trimIndent()

    assertEquals(
      setOf("C29C23WKU8", "B6Y9D6S4KK"),
      IosCodeSigningTeamResolver.parseTeamIds(output),
    )
  }

  @Test
  fun `fromEnvironment reads persisted host settings`() {
    ArbigentHostConfig.replace(
      mapOf(
        IosRealXCTestDeviceConfig.HOST_SETTING to "localhost",
        IosRealXCTestDeviceConfig.PORT_SETTING to "23000",
        IosRealXCTestDeviceConfig.APPLE_TEAM_ID_SETTING to "B6Y9D6S4KK",
        IosRealXCTestDeviceConfig.BUILD_DRIVER_SETTING to "false",
      )
    )
    try {
      val config = IosRealXCTestDeviceConfig.fromEnvironment(
        IosRealDevice(
          coreDeviceIdentifier = "COREDEVICE",
          udid = "UDID",
          name = "iPhone",
          modelName = "iPhone",
          pairingState = "paired",
          tunnelState = "connected",
          canConnect = true,
        )
      )

      assertEquals("localhost", config.host)
      assertEquals(23000, config.port)
      assertEquals("B6Y9D6S4KK", config.appleTeamId)
      assertFalse(config.buildDriver)
    } finally {
      ArbigentHostConfig.replace(emptyMap())
    }
  }

  @Test
  fun `selectDevices skips offline paired devices when auto-selecting`() {
    val offlinePhone = IosRealDevice(
      coreDeviceIdentifier = "CD1", udid = "UDID-OFFLINE", name = "iPhone 12 mini",
      modelName = "iPhone", pairingState = "paired", tunnelState = "unavailable", canConnect = false,
    )
    val onlinePhone = IosRealDevice(
      coreDeviceIdentifier = "CD2", udid = "UDID-ONLINE", name = "iPhone 15",
      modelName = "iPhone", pairingState = "paired", tunnelState = "connected", canConnect = true,
    )

    assertEquals(
      listOf(onlinePhone),
      IosRealDeviceCatalog.selectDevices(listOf(offlinePhone, onlinePhone), requestedDeviceId = null),
    )
    // Only the offline phone is paired -> auto-select yields nothing, so a booted
    // simulator can win instead of connect-failing on the offline phone.
    assertTrue(
      IosRealDeviceCatalog.selectDevices(listOf(offlinePhone), requestedDeviceId = null).isEmpty()
    )
  }

  @Test
  fun `selectDevices proceeds with an explicitly requested device even if it reports not-connectable`() {
    // An explicitly requested device must not be hard-rejected on a transient
    // canConnect=false (the flag flaps while a connected device negotiates);
    // connectToDevice() is the source of truth.
    val flappingPhone = IosRealDevice(
      coreDeviceIdentifier = "CD1", udid = "UDID-FLAP", name = "iPhone",
      modelName = "iPhone", pairingState = "paired", tunnelState = "unavailable", canConnect = false,
    )
    assertEquals(
      listOf(flappingPhone),
      IosRealDeviceCatalog.selectDevices(listOf(flappingPhone), requestedDeviceId = "UDID-FLAP"),
    )
  }

  @Test
  fun `selectDevices still fails when an explicitly requested device is not paired`() {
    val phone = IosRealDevice(
      coreDeviceIdentifier = "CD1", udid = "UDID-A", name = "iPhone",
      modelName = "iPhone", pairingState = "paired", tunnelState = "connected", canConnect = true,
    )
    assertFailsWith<IllegalArgumentException> {
      IosRealDeviceCatalog.selectDevices(listOf(phone), requestedDeviceId = "UDID-MISSING")
    }
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
      appleTeamId = System.getenv(IosRealXCTestDeviceConfig.APPLE_TEAM_ID_ENV)?.takeIf { it.isNotBlank() }
        ?: System.getenv("DEVELOPMENT_TEAM")?.takeIf { it.isNotBlank() },
      driverProductsDir = System.getenv(IosRealXCTestDeviceConfig.DRIVER_PRODUCTS_DIR_ENV)?.takeIf { it.isNotBlank() },
      buildDriver = System.getenv(IosRealXCTestDeviceConfig.BUILD_DRIVER_ENV)?.toBooleanStrictOrNull() ?: true,
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
