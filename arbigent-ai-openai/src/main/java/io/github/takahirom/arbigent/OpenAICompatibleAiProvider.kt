package io.github.takahirom.arbigent

import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header

@OptIn(ExperimentalRoborazziApi::class)
public class OpenAICompatibleAiProvider(
  private val providerId: String,
  private val providerLabel: String,
  private val apiKey: String,
  private val baseUrl: String,
  private val modelName: String,
  private val loggingEnabled: Boolean,
  private val jsonSchemaType: ArbigentAi.JsonSchemaType = ArbigentAi.JsonSchemaType.OpenAI,
  private val requestBuilderModifier: HttpRequestBuilder.() -> Unit = {
    header("Authorization", "Bearer $apiKey")
  },
) : ArbigentAiProvider {
  override val metadata: ArbigentAiProviderMetadata = ArbigentAiProviderMetadata(
    id = providerId,
    label = providerLabel,
    runtime = ArbigentAiRuntime(
      id = "openai-compatible",
      transport = ArbigentAiTransport.OpenAiCompatibleHttp,
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

  override fun createAi(): ArbigentAi {
    return OpenAIAi(
      apiKey = apiKey,
      baseUrl = baseUrl,
      modelName = modelName,
      loggingEnabled = loggingEnabled,
      requestBuilderModifier = requestBuilderModifier,
      jsonSchemaType = jsonSchemaType,
    )
  }
}
