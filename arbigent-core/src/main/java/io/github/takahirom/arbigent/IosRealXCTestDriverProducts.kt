package io.github.takahirom.arbigent

import maestro.cli.api.CliVersion
import maestro.cli.driver.DriverBuildConfig
import maestro.cli.driver.DriverBuilder
import java.io.File
import java.nio.file.Paths

internal fun resolveRealIosDriverProducts(config: IosRealXCTestDeviceConfig): File {
  config.driverProductsDir?.let { explicitPath ->
    return File(explicitPath).also { productsDir ->
      require(productsDir.hasXctestRun()) {
        "ARBIGENT_IOS_XCTEST_DRIVER_PRODUCTS_DIR must point to a Build/Products directory containing an .xctestrun: ${productsDir.absolutePath}"
      }
    }
  }

  val defaultProductsDir = Paths.get(
    System.getProperty("user.home"),
    ".maestro",
    "maestro-iphoneos-driver-build",
    "driver-iphoneos",
    "Build",
    "Products",
  ).toFile()
  if (defaultProductsDir.hasXctestRun()) {
    return defaultProductsDir
  }

  if (!config.buildDriver) {
    return materializeIosDriverResource("driver-iphoneos")
  }

  val teamId = config.appleTeamId
    ?: throw IllegalStateException(
      "A real iOS XCTest driver must be signed for this device. Set ${IosRealXCTestDeviceConfig.APPLE_TEAM_ID_ENV} " +
        "or ${IosRealXCTestDeviceConfig.DRIVER_PRODUCTS_DIR_ENV}; the bundled Maven driver is only a fallback and may not install on real devices."
    )

  val productsPath = DriverBuilder().buildDriver(
    DriverBuildConfig(
      teamId = teamId,
      derivedDataPath = "driver-iphoneos",
      destination = "platform=iOS,id=${config.deviceId}",
      sourceCodePath = "driver/ios",
      sourceCodeRoot = System.getProperty("user.home"),
      architectures = "arm64",
      cliVersion = CliVersion(2, 6, 0),
    )
  )
  return productsPath.toFile()
}

private fun File.hasXctestRun(): Boolean {
  return isDirectory && walkTopDown().any { it.isFile && it.extension == "xctestrun" }
}
