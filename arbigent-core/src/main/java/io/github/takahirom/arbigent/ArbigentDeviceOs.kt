package io.github.takahirom.arbigent

import dadb.Dadb
import ios.LocalIOSDevice
import ios.simctl.SimctlIOSDevice
import ios.xctest.XCTestIOSDevice
import maestro.Maestro
import maestro.drivers.AndroidDriver
import maestro.drivers.IOSDriver
import util.SimctlList
import util.XCRunnerCLIUtils
import xcuitest.XCTestClient
import xcuitest.XCTestDriverClient
import xcuitest.installer.LocalXCTestInstaller
import xcuitest.installer.XCTestInstaller
import java.io.File
import java.util.concurrent.TimeUnit

public enum class ArbigentDeviceOs {
  Android, Ios, Web;

  public fun isAndroid(): Boolean = this == Android
  public fun isIos(): Boolean = this == Ios
  public fun isWeb(): Boolean = this == Web
}

public sealed interface ArbigentAvailableDevice {
  public val deviceOs: ArbigentDeviceOs
  public val name: String

  // Do not use data class because dadb return true for equals
  public class Android(private val dadb: Dadb) : ArbigentAvailableDevice {
    override val deviceOs: ArbigentDeviceOs = ArbigentDeviceOs.Android
    override val name: String = dadb.toString()
    override fun connectToDevice(): ArbigentDevice {
      val driver = AndroidDriver(
        dadb,
      )
      val maestro = try {
        Maestro.android(
          driver
        )
      } catch (e: java.util.concurrent.TimeoutException) {
        driver.close()
        dadb.close()
        throw RuntimeException("Arbigent can not connect to device in time. The likely reason why we can't connect is that you have multiple instance of Arbigent like UI and CLI of Arbigent", e)
      } catch (e: Exception) {
        driver.close()
        dadb.close()
        throw e
      }
      return MaestroDevice(maestro, availableDevice = this)
    }
  }

  public class IOS(
    private val device: SimctlList.Device,
    private val port: Int = 8080,
    // local host
    private val host: String = "[::1]",
  ) : ArbigentAvailableDevice {
    override val deviceOs: ArbigentDeviceOs = ArbigentDeviceOs.Ios
    override val name: String = device.name
    override fun connectToDevice(): ArbigentDevice {
      val port = port
      val host = host

      val xcTestInstaller = LocalXCTestInstaller(
        deviceId = device.udid, // Use the device's UDID
        host = host,
        defaultPort = port,
        enableXCTestOutputFileLogging = true,
      )

      val xcTestDriverClient = XCTestDriverClient(
        installer = xcTestInstaller,
        client = XCTestClient(host, port), // Use the same host and port as above
      )

      val xcTestDevice = XCTestIOSDevice(
        deviceId = device.udid,
        client = xcTestDriverClient,
        getInstalledApps = { XCRunnerCLIUtils.listApps(device.udid) },
      )

      return MaestroDevice(
        Maestro.ios(
          IOSDriver(
            LocalIOSDevice(
              deviceId = device.udid,
              xcTestDevice = xcTestDevice,
              simctlIOSDevice = SimctlIOSDevice(device.udid)
            )
          )
        ),
        availableDevice = this
      )
    }
  }

  public class IOSRealMirror(
    private val config: IosRealMirrorDeviceConfig,
  ) : ArbigentAvailableDevice {
    override val deviceOs: ArbigentDeviceOs = ArbigentDeviceOs.Ios
    override val name: String = "iOS real mirror${config.deviceId?.let { " ($it)" }.orEmpty()}"

    public override fun connectToDevice(): ArbigentDevice {
      return IosRealMirrorDevice(config)
    }
  }

  public class IOSRealXCTest(
    private val config: IosRealXCTestDeviceConfig,
  ) : ArbigentAvailableDevice {
    override val deviceOs: ArbigentDeviceOs = ArbigentDeviceOs.Ios
    override val name: String = "iOS real XCTest (${config.deviceName}, ${config.deviceId})"

    public override fun connectToDevice(): ArbigentDevice {
      val portForwarder = if (config.autoStartIproxy) {
        IosRealXCTestPortForwarder(deviceId = config.deviceId, localPort = config.port, devicePort = config.port).also {
          it.start()
        }
      } else {
        null
      }
      val xcTestInstaller: XCTestInstaller = config.xctestrunFile?.let { xctestrunFile ->
        ArbigentExternalXCTestInstaller(
          deviceId = config.deviceId,
          host = config.host,
          port = config.port,
          xctestrunFile = File(xctestrunFile),
          enableXCTestOutputFileLogging = true,
          reinstallDriver = config.reinstallDriver,
        )
      } ?: LocalXCTestInstaller(
        deviceId = config.deviceId,
        host = config.host,
        defaultPort = config.port,
        enableXCTestOutputFileLogging = true,
        preBuiltRunner = config.preBuiltRunner,
        reinstallDriver = config.reinstallDriver,
      )
      try {
        val xcTestDriverClient = XCTestDriverClient(
          installer = xcTestInstaller,
          client = XCTestClient(config.host, config.port),
        )
        val xcTestDevice = XCTestIOSDevice(
          deviceId = config.deviceId,
          client = xcTestDriverClient,
          getInstalledApps = { IosRealDeviceCatalog.installedBundleIds(config.deviceId) },
        )
        val maestroDevice = MaestroDevice(
          Maestro.ios(
            IOSDriver(xcTestDevice)
          ),
          availableDevice = this
        )
        return IosRealXCTestArbigentDevice(maestroDevice, portForwarder)
      } catch (throwable: Throwable) {
        runCatching { xcTestInstaller.close() }
        portForwarder?.close()
        throw throwable
      }
    }
  }

  public class Web : ArbigentAvailableDevice {
    override val deviceOs: ArbigentDeviceOs = ArbigentDeviceOs.Web
    override val name: String = "Chrome"
    public override fun connectToDevice(): ArbigentDevice {
      return MaestroDevice(
        Maestro.web(false, false),
        availableDevice = this
      )
    }
  }

  public class Fake : ArbigentAvailableDevice {
    override val deviceOs: ArbigentDeviceOs = ArbigentDeviceOs.Android
    override val name: String = "Fake"
    public override fun connectToDevice(): ArbigentDevice {
      // This is not called
      throw UnsupportedOperationException("Fake device is not supported")
    }
  }

  public fun connectToDevice(): ArbigentDevice
}

internal class IosRealXCTestArbigentDevice(
  private val delegate: ArbigentDevice,
  private val portForwarder: IosRealXCTestPortForwarder?,
) : ArbigentDevice by delegate {
  override fun close() {
    runCatching { delegate.close() }
    portForwarder?.close()
  }
}

internal class IosRealXCTestPortForwarder(
  private val deviceId: String,
  private val localPort: Int,
  private val devicePort: Int,
) : AutoCloseable {
  private var process: Process? = null

  fun start() {
    if (!commandExists("iproxy")) {
      arbigentWarnLog("iproxy is not available; XCTest real-device HTTP channel may be unreachable.")
      return
    }
    if (process?.isAlive == true) return
    process = ProcessBuilder(
      "iproxy",
      "--udid",
      deviceId,
      "$localPort:$devicePort",
    )
      .redirectOutput(ProcessBuilder.Redirect.PIPE)
      .redirectError(ProcessBuilder.Redirect.PIPE)
      .start()
    Thread.sleep(500)
  }

  override fun close() {
    process?.destroy()
    if (process?.waitFor(2, TimeUnit.SECONDS) == false) {
      process?.destroyForcibly()
    }
    process = null
  }

  private fun commandExists(command: String): Boolean {
    return runCatching {
      val probe = ProcessBuilder("/bin/zsh", "-lc", "command -v $command >/dev/null 2>&1").start()
      probe.waitFor(2, TimeUnit.SECONDS) && probe.exitValue() == 0
    }.getOrDefault(false)
  }
}
