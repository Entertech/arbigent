package io.github.takahirom.arbigent.cli

import io.github.takahirom.arbigent.AnthropicAi
import io.github.takahirom.arbigent.ArbigentAi
import io.github.takahirom.arbigent.ArbigentAiCapability
import io.github.takahirom.arbigent.ArbigentAiProvider
import io.github.takahirom.arbigent.ArbigentAiProviderMetadata
import io.github.takahirom.arbigent.ArbigentAiRuntime
import io.github.takahirom.arbigent.ArbigentAiTransport

/**
 * Wraps upstream's native Anthropic Messages API client ([AnthropicAi]) in the fork's provider
 * boundary, so `run` / `run task` create a fresh client per agent run like every other provider.
 */
class AnthropicAiProvider(
  private val apiKey: String,
  private val baseUrl: String,
  private val modelName: String,
  private val loggingEnabled: Boolean,
) : ArbigentAiProvider {
  override val metadata: ArbigentAiProviderMetadata = ArbigentAiProviderMetadata(
    id = "anthropic",
    label = "Anthropic",
    runtime = ArbigentAiRuntime(
      id = "anthropic-messages",
      transport = ArbigentAiTransport.AnthropicMessagesHttp,
      modelName = modelName,
      source = baseUrl,
    ),
    capabilities = setOf(
      ArbigentAiCapability.AgentDecision,
      ArbigentAiCapability.ScenarioGeneration,
      ArbigentAiCapability.ImageAssertion,
      ArbigentAiCapability.VisionInput,
    ),
  )

  override fun createAi(): ArbigentAi = AnthropicAi(
    apiKey = apiKey,
    baseUrl = baseUrl,
    modelName = modelName,
    loggingEnabled = loggingEnabled,
  )
}
