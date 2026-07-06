@file:OptIn(ArbigentInternalApi::class)

package io.github.takahirom.arbigent.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.types.choice
import io.github.takahirom.arbigent.*
import io.ktor.client.request.*
import io.ktor.util.*
import java.io.File

const val defaultResultPath = "arbigent-result"
const val defaultCachePath = "arbigent-cache"

fun CliktCommand.projectFileOption() = defaultOption("--project-file", help = "Path to the project YAML file")

fun CliktCommand.workingDirectoryOption() = defaultOption("--working-directory", help = "Working directory for the project")

fun CliktCommand.logLevelOption() = defaultOption("--log-level", help = "Log level")
  .choice("debug", "info", "warn", "error")
  .default("info")

fun CliktCommand.logFileOption() = defaultOption("--log-file", help = "Log file path")
  .default("$defaultResultPath/arbigent.log")

fun resolveFile(workingDirectory: String?, fileName: String): File {
  return if (workingDirectory.isNullOrBlank()) {
    File(fileName)
  } else {
    File(workingDirectory, fileName)
  }
}

fun validateAiConfig(aiType: AiConfig) {
  when (aiType) {
    is OpenAIAiConfig -> {
      if (aiType.openAiApiKey.isNullOrBlank()) {
        throw CliktError("Missing OpenAI API key. Please provide via --openai-api-key, OPENAI_API_KEY environment variable, or in .arbigent/settings.local.yml")
      }
    }
    is GeminiAiConfig -> {
      if (aiType.geminiApiKey.isNullOrBlank()) {
        throw CliktError("Missing Gemini API key. Please provide via --gemini-api-key, GEMINI_API_KEY environment variable, or in .arbigent/settings.local.yml")
      }
    }
    is AzureOpenAiConfig -> {
      if (aiType.azureOpenAIEndpoint.isNullOrBlank()) {
        throw CliktError("Missing Azure OpenAI endpoint. Please provide via --azure-openai-endpoint or in .arbigent/settings.local.yml")
      }
      if (aiType.azureOpenAIKey.isNullOrBlank()) {
        throw CliktError("Missing Azure OpenAI API key. Please provide via --azure-openai-api-key, AZURE_OPENAI_API_KEY environment variable, or in .arbigent/settings.local.yml")
      }
    }
    is CodexAiConfig -> {
      if (aiType.codexCommand.isBlank()) {
        throw CliktError("Missing Codex command. Please provide via --codex-command or ARBIGENT_CODEX_COMMAND.")
      }
    }
  }
}

fun applyLogLevel(logLevel: String) {
  arbigentLogLevel =
    ArbigentLogLevel.entries.find { it.name.toLowerCasePreservingASCIIRules() == logLevel.toLowerCasePreservingASCIIRules() }
      ?: throw IllegalArgumentException(
        "Invalid log level. The log level should be one of ${
          ArbigentLogLevel.entries
            .joinToString(", ") { it.name.toLowerCasePreservingASCIIRules() }
        }")
}

data class ArbigentResultDirs(val resultDir: File, val resultFile: File)

fun setupArbigentFiles(workingDirectory: String?, logFile: String): ArbigentResultDirs {
  val resultDir = resolveFile(workingDirectory, defaultResultPath)
  resultDir.mkdirs()
  ArbigentFiles.parentDir = resultDir.absolutePath
  ArbigentFiles.screenshotsDir = File(resultDir, "screenshots")
  ArbigentFiles.jsonlsDir = File(resultDir, "jsonls")
  ArbigentFiles.logFile = resolveFile(workingDirectory, logFile)
  ArbigentFiles.cacheDir = resolveFile(workingDirectory, defaultCachePath + File.separator + BuildConfig.VERSION_NAME)
  ArbigentFiles.cacheDir.mkdirs()
  val resultFile = File(resultDir, "result.yml")
  return ArbigentResultDirs(resultDir, resultFile)
}

fun createAi(
  aiType: AiConfig,
  aiApiLoggingEnabled: Boolean,
  workingDirectory: String? = null,
): ArbigentAi {
  return createAiProvider(aiType, aiApiLoggingEnabled, workingDirectory).createAi()
}

fun createAiProvider(
  aiType: AiConfig,
  aiApiLoggingEnabled: Boolean,
  workingDirectory: String? = null,
): ArbigentAiProvider {
  return when (aiType) {
    is OpenAIAiConfig -> OpenAICompatibleAiProvider(
      providerId = "openai",
      providerLabel = "OpenAI",
      apiKey = aiType.openAiApiKey!!,
      baseUrl = aiType.openAiEndpoint,
      modelName = aiType.openAiModelName,
      loggingEnabled = aiApiLoggingEnabled,
    )

    is GeminiAiConfig -> OpenAICompatibleAiProvider(
      providerId = "gemini",
      providerLabel = "Gemini",
      apiKey = aiType.geminiApiKey!!,
      baseUrl = aiType.geminiEndpoint,
      modelName = aiType.geminiModelName,
      loggingEnabled = aiApiLoggingEnabled,
      jsonSchemaType = ArbigentAi.JsonSchemaType.GeminiOpenAICompatible
    )

    is AzureOpenAiConfig -> OpenAICompatibleAiProvider(
      providerId = "azureopenai",
      providerLabel = "Azure OpenAI",
      apiKey = aiType.azureOpenAIKey!!,
      baseUrl = aiType.azureOpenAIEndpoint!!,
      modelName = aiType.azureOpenAIModelName,
      loggingEnabled = aiApiLoggingEnabled,
      requestBuilderModifier = {
        parameter("api-version", aiType.azureOpenAIApiVersion)
        header("api-key", aiType.azureOpenAIKey!!)
      }
    )

    is CodexAiConfig -> if (aiType.codexDirect) {
      CodexResponsesAiProvider(
        modelName = aiType.codexModelName ?: CodexResponsesAiProvider.DEFAULT_MODEL,
        reasoningEffort = aiType.codexReasoningEffort,
        timeoutMs = aiType.codexTimeoutMs,
      )
    } else {
      CodexCliAiProvider(
        codexExecutable = aiType.codexCommand,
        modelName = aiType.codexModelName,
        reasoningEffort = aiType.codexReasoningEffort,
        sessionCacheMode = aiType.codexSessionCache,
        profile = aiType.codexProfile,
        sandbox = aiType.codexSandbox,
        approvalPolicy = aiType.codexApprovalPolicy,
        timeoutMs = aiType.codexTimeoutMs,
        workingDirectory = workingDirectory,
      )
    }
  }
}

fun CliktCommand.deviceOption() = defaultOption(
  "--device",
  help = "Device ID to run on (Android serial or iOS UDID). Run `arbigent devices` to list connected devices. Defaults to the first available device."
)

fun parseDeviceOs(os: String): ArbigentDeviceOs =
  ArbigentDeviceOs.entries.find { it.name.toLowerCasePreservingASCIIRules() == os.toLowerCasePreservingASCIIRules() }
    ?: throw IllegalArgumentException(
      "Invalid OS. The OS should be one of ${
        ArbigentDeviceOs.entries
          .joinToString(", ") { it.name.toLowerCasePreservingASCIIRules() }
      }")

fun connectDevice(os: String, deviceId: String? = null): ArbigentDevice {
  val deviceOs = parseDeviceOs(os)
  val requested = deviceId?.takeIf { it.isNotBlank() }
  val devices = fetchAvailableDevicesByOs(deviceOs, requested)
  val selected = if (requested == null) {
    devices.firstOrNull()
  } else {
    // Every OS branch of fetchAvailableDevicesByOs throws when `requested` matches
    // nothing, so this list is already filtered; the fallback only covers matches
    // via an alternate id form (adb transport id, iOS coreDeviceIdentifier).
    devices.firstOrNull { it.id == requested } ?: devices.firstOrNull()
  }
  return selected?.connectToDevice()
    ?: throw IllegalArgumentException(
      if (requested != null) {
        "No available $os device matches --device=$requested. Run `arbigent devices` to list connected devices."
      } else {
        "No available $os device found. Run `arbigent devices` to list connected devices."
      }
    )
}
