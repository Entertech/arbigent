package io.github.takahirom.arbigent

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
  private val enableXCTestOutputFileLogging: Boolean,
  private val reinstallDriver: Boolean,
) : XCTestInstaller {
  private var process: Process? = null

  override val preBuiltRunner: Boolean = true

  override fun start(): XCTestClient {
    require(xctestrunFile.isFile) {
      "XCTest run file does not exist: ${xctestrunFile.absolutePath}"
    }
    if (reinstallDriver) {
      uninstall()
    }

    process?.destroy()
    process = XCRunnerCLIUtils.runXcTestWithoutBuild(
      deviceId,
      xctestrunFile.absolutePath,
      port,
      enableXCTestOutputFileLogging,
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
    return runCatching {
      XCRunnerCLIUtils.uninstall("dev.mobile.maestro-driver-iosUITests.xctrunner", deviceId)
      XCRunnerCLIUtils.uninstall("dev.mobile.maestro-driver-ios", deviceId)
      true
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
  }

  private fun startupTimeoutSeconds(): Long {
    return System.getenv("MAESTRO_DRIVER_STARTUP_TIMEOUT")?.toLongOrNull() ?: 120L
  }
}
