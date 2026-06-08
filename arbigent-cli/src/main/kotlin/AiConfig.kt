@file:OptIn(ArbigentInternalApi::class)

package io.github.takahirom.arbigent.cli

import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.types.long
import io.github.takahirom.arbigent.ArbigentInternalApi
import io.github.takahirom.arbigent.CodexCliAiProvider
import io.github.takahirom.arbigent.OpenAIAi

sealed class AiConfig(name: String) : OptionGroup(name)

class OpenAIAiConfig : AiConfig("Options for OpenAI API AI") {
  private val defaultEndpoint = "https://api.openai.com/v1/"
  val openAiEndpoint by defaultOption("--openai-endpoint", help = "Endpoint URL (default: $defaultEndpoint)")
    .default(defaultEndpoint, defaultForHelp = defaultEndpoint)
  val openAiModelName by defaultOption("--openai-model-name", help = "Model name (default: ${OpenAIAi.DEFAULT_OPENAI_MODEL})")
    .default(OpenAIAi.DEFAULT_OPENAI_MODEL, OpenAIAi.DEFAULT_OPENAI_MODEL)
  val openAiApiKey by defaultOption("--openai-api-key", "--openai-key", envvar = "OPENAI_API_KEY", help = "API key")
}

class GeminiAiConfig : AiConfig("Options for Gemini API AI") {
  private val defaultEndpoint = "https://generativelanguage.googleapis.com/v1beta/openai/"
  val geminiEndpoint by defaultOption("--gemini-endpoint", help = "Endpoint URL (default: $defaultEndpoint)")
    .default(defaultEndpoint, defaultForHelp = defaultEndpoint)
  val geminiModelName by defaultOption("--gemini-model-name", help = "Model name (default: gemini-1.5-flash)")
    .default("gemini-1.5-flash", "gemini-1.5-flash")
  val geminiApiKey by defaultOption("--gemini-api-key", envvar = "GEMINI_API_KEY", help = "API key")
}

class AzureOpenAiConfig : AiConfig("Options for Azure OpenAI") {
  val azureOpenAIEndpoint by defaultOption("--azure-openai-endpoint", help = "Endpoint URL")
  val azureOpenAIApiVersion by defaultOption("--azure-openai-api-version", help = "API version")
    .default("2024-10-21")
  val azureOpenAIModelName by defaultOption("--azure-openai-model-name", help = "Deployment name (default: ${OpenAIAi.DEFAULT_OPENAI_MODEL})")
    .default(OpenAIAi.DEFAULT_OPENAI_MODEL, OpenAIAi.DEFAULT_OPENAI_MODEL)
  val azureOpenAIKey by defaultOption("--azure-openai-api-key", "--azure-openai-key", envvar = "AZURE_OPENAI_API_KEY", help = "API key")
}

class CodexAiConfig : AiConfig("Options for Codex CLI AI") {
  val codexCommand by defaultOption(
    "--codex-command",
    envvar = "ARBIGENT_CODEX_COMMAND",
    help = "Codex executable path or command name"
  ).default(CodexCliAiProvider.DEFAULT_CODEX_EXECUTABLE, CodexCliAiProvider.DEFAULT_CODEX_EXECUTABLE)
  val codexModelName by defaultOption(
    "--codex-model-name",
    envvar = "ARBIGENT_CODEX_MODEL",
    help = "Codex model name. If omitted, Codex CLI uses its configured default."
  )
  val codexReasoningEffort by defaultOption(
    "--codex-reasoning-effort",
    envvar = "ARBIGENT_CODEX_REASONING_EFFORT",
    help = "Codex model reasoning effort. Defaults to ${CodexCliAiProvider.DEFAULT_REASONING_EFFORT} so Arbigent does not inherit a slow global Codex setting."
  ).default(CodexCliAiProvider.DEFAULT_REASONING_EFFORT, CodexCliAiProvider.DEFAULT_REASONING_EFFORT)
  val codexProfile by defaultOption(
    "--codex-profile",
    envvar = "ARBIGENT_CODEX_PROFILE",
    help = "Codex config profile. If omitted, Codex CLI uses its configured default."
  )
  val codexSandbox by defaultOption(
    "--codex-sandbox",
    envvar = "ARBIGENT_CODEX_SANDBOX",
    help = "Codex sandbox mode"
  ).default(CodexCliAiProvider.DEFAULT_SANDBOX, CodexCliAiProvider.DEFAULT_SANDBOX)
  val codexApprovalPolicy by defaultOption(
    "--codex-approval-policy",
    envvar = "ARBIGENT_CODEX_APPROVAL_POLICY",
    help = "Codex approval policy"
  ).default(CodexCliAiProvider.DEFAULT_APPROVAL_POLICY, CodexCliAiProvider.DEFAULT_APPROVAL_POLICY)
  val codexTimeoutMs by defaultOption(
    "--codex-timeout-ms",
    envvar = "ARBIGENT_CODEX_TIMEOUT_MS",
    help = "Codex decision timeout in milliseconds"
  ).long().default(CodexCliAiProvider.DEFAULT_TIMEOUT_MS)
}
