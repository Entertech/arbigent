@file:OptIn(ArbigentInternalApi::class)

package io.github.takahirom.arbigent.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.types.choice
import io.github.takahirom.arbigent.ArbigentDeviceOs
import io.github.takahirom.arbigent.ArbigentInternalApi
import io.github.takahirom.arbigent.fetchAvailableDevicesByOs

class ArbigentDevicesCommand : CliktCommand(name = "devices") {
  override fun help(context: Context): String =
    "List connected devices and the IDs accepted by --device"

  private val os by defaultOption(
    "--os",
    help = "Only list devices for this OS (android, ios, web). Defaults to android + ios."
  ).choice("android", "ios", "web")

  override fun run() {
    val targets = os?.let { listOf(parseDeviceOs(it)) }
      ?: listOf(ArbigentDeviceOs.Android, ArbigentDeviceOs.Ios)

    echo(String.format("%-8s  %-40s  %s", "OS", "DEVICE ID", "NAME"))
    var total = 0
    targets.forEach { target ->
      val osLabel = target.name.lowercase()
      // Discovery must show everything: include not-currently-connectable iOS
      // devices and ignore ANDROID_SERIAL/ARBIGENT_* env pins (a stale pin would
      // otherwise blank the listing exactly when the user is hunting for ids).
      val devices = runCatching {
        fetchAvailableDevicesByOs(target, includeUnconnectable = true, honorEnvironmentPins = false)
      }
        .getOrElse { throwable ->
          echo("$osLabel: failed to list devices (${throwable.message})", err = true)
          emptyList()
        }
      devices.forEach { device ->
        echo(String.format("%-8s  %-40s  %s", osLabel, device.id, device.description))
        total++
      }
    }
    if (total == 0) {
      echo("No devices found.")
    } else {
      echo("")
      echo("Use with: arbigent run --os=<os> --device=<DEVICE ID> ...")
    }
  }
}
