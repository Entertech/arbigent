package io.github.takahirom.arbigent

import maestro.utils.TempFileHandler
import util.XCRunnerCLIUtils
import xcuitest.XCTestClient
import xcuitest.installer.XCTestInstaller
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

internal class ArbigentExternalXCTestInstaller(
  private val deviceId: String,
  private val host: String,
  private val port: Int,
  private val xctestrunFile: File,
  private val reinstallDriver: Boolean,
) : XCTestInstaller {
  private val tempFileHandler = TempFileHandler()
  private val xcRunnerCLIUtils = XCRunnerCLIUtils(tempFileHandler)
  private var process: Process? = null

  override fun start(): XCTestClient {
    require(xctestrunFile.isFile) {
      "XCTest run file does not exist: ${xctestrunFile.absolutePath}"
    }
    if (reinstallDriver) {
      uninstall()
    }

    process?.destroy()
    process = xcRunnerCLIUtils.runXcTestWithoutBuild(
      deviceId = deviceId,
      xcTestRunFilePath = xctestrunFile.absolutePath,
      port = port,
      snapshotKeyHonorModalViews = null,
      logsDir = xctestLogsDir(),
    )

    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(startupTimeoutSeconds())
    var lastError: Throwable? = null
    while (System.nanoTime() < deadline) {
      if (isChannelAlive()) {
        return XCTestClient(host, port)
      }
      val runningProcess = process
      if (runningProcess != null && !runningProcess.isAlive) {
        throw IllegalStateException(
          "XCTest runner exited before the HTTP channel became ready. " +
            "Check ~/Library/Logs/maestro/xctest_runner_logs for xcodebuild logs."
        )
      }
      Thread.sleep(500)
      lastError = null
    }

    throw IllegalStateException(
      "iOS XCTest driver not ready in time after ${startupTimeoutSeconds()} seconds. " +
        "xctestrun=${xctestrunFile.absolutePath}",
      lastError,
    )
  }

  override fun uninstall(): Boolean {
    // This is a real-device installer, so the driver bundles must be removed via
    // `xcrun devicectl` (CoreDevice). The Maestro helper used `xcrun simctl`,
    // which only targets simulators and silently no-ops on a real UDID — making
    // reinstallDriver ineffective. Log failures instead of swallowing them.
    val devicectl = ArbigentDevicectlClient(deviceId)
    return runCatching {
      devicectl.uninstall("dev.mobile.maestro-driver-iosUITests.xctrunner")
      devicectl.uninstall("dev.mobile.maestro-driver-ios")
      true
    }.onFailure {
      arbigentInfoLog("Failed to uninstall XCTest driver via devicectl on $deviceId: ${it.message}")
    }.getOrDefault(false)
  }

  override fun isChannelAlive(): Boolean {
    return runCatching {
      val connection = URL("http://$host:$port/status").openConnection() as HttpURLConnection
      connection.connectTimeout = 1_000
      connection.readTimeout = 1_000
      connection.requestMethod = "GET"
      connection.responseCode in 200..299
    }.getOrDefault(false)
  }

  override fun close() {
    process?.destroy()
    if (process?.waitFor(2, TimeUnit.SECONDS) == false) {
      process?.destroyForcibly()
    }
    process = null
    if (reinstallDriver) {
      uninstall()
    }
    tempFileHandler.close()
  }

  private fun startupTimeoutSeconds(): Long {
    return System.getenv("MAESTRO_DRIVER_STARTUP_TIMEOUT")?.toLongOrNull() ?: 120L
  }
}
