package io.github.takahirom.arbigent.cli

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.YamlTaggedNode
import io.github.takahirom.arbigent.ArbigentHostConfig
import io.github.takahirom.arbigent.ArbigentInternalApi
import io.github.takahirom.arbigent.IosRealXCTestDeviceConfig
import java.io.File

internal val cliSettingsFileNames: List<String> = listOf(
  ".arbigent/settings.local.yml",
  ".arbigent/settings.local.yaml",
  ".arbigent/settings.yml",
  ".arbigent/settings.yaml",
)

@OptIn(ArbigentInternalApi::class)
internal fun configureHostSettings(commandPathCandidates: List<String>) {
  ArbigentHostConfig.replace(loadHostSettings(commandPathCandidates))
}

internal fun loadHostSettings(
  commandPathCandidates: List<String>,
  baseDir: File = File("."),
): Map<String, String> {
  return loadCliSettings(
    keys = iosHostSettingKeys,
    commandPathCandidates = commandPathCandidates,
    baseDir = baseDir,
  )
}

internal fun loadCliSettings(
  keys: Collection<String>,
  commandPathCandidates: List<String>,
  baseDir: File = File("."),
): Map<String, String> {
  val values = linkedMapOf<String, String>()
  existingCliSettingsFiles(baseDir).forEach { settingsFile ->
    val flattened = runCatching { flattenYamlToMap(Yaml.default.parseToYamlNode(settingsFile.readText())) }
      .getOrDefault(emptyMap())
    keys.forEach { key ->
      if (values.containsKey(key)) return@forEach
      val commandValue = commandPathCandidates
        .firstNotNullOfOrNull { commandPath -> flattened["$commandPath.$key"] }
        ?.takeIf { it.isNotBlank() }
      values[key] = commandValue
        ?: flattened[key]?.takeIf { it.isNotBlank() }
        ?: return@forEach
    }
  }
  return values
}

internal fun existingCliSettingsFiles(baseDir: File = File(".")): List<File> {
  return cliSettingsFileNames
    .map { File(baseDir, it) }
    .filter { it.exists() }
}

private val iosHostSettingKeys: List<String> = listOf(
  IosRealXCTestDeviceConfig.DEVICE_ID_SETTING,
  IosRealXCTestDeviceConfig.HOST_SETTING,
  IosRealXCTestDeviceConfig.PORT_SETTING,
  IosRealXCTestDeviceConfig.AUTO_IPROXY_SETTING,
  IosRealXCTestDeviceConfig.PREBUILT_RUNNER_SETTING,
  IosRealXCTestDeviceConfig.REINSTALL_DRIVER_SETTING,
  IosRealXCTestDeviceConfig.XCTESTRUN_SETTING,
  IosRealXCTestDeviceConfig.APPLE_TEAM_ID_SETTING,
  IosRealXCTestDeviceConfig.DRIVER_PRODUCTS_DIR_SETTING,
  IosRealXCTestDeviceConfig.BUILD_DRIVER_SETTING,
)

private fun flattenYamlToMap(node: YamlNode, prefix: String = ""): Map<String, String> {
  val result = mutableMapOf<String, String>()
  when (node) {
    is YamlMap -> {
      for ((key, value) in node.entries) {
        val keyString = key.content
        val fullKey = if (prefix.isEmpty()) keyString else "$prefix.$keyString"
        result.putAll(flattenYamlToMap(value, fullKey))
      }
    }

    is YamlList -> {
      result[prefix] = node.items.joinToString(",") { item ->
        when (item) {
          is YamlScalar -> item.content
          else -> item.toString()
        }
      }
    }

    is YamlScalar -> result[prefix] = node.content
    is YamlNull -> result[prefix] = ""
    is YamlTaggedNode -> result.putAll(flattenYamlToMap(node.innerNode, prefix))
  }
  return result
}
