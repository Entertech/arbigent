package io.github.takahirom.arbigent

/**
 * Stable provider boundary for AI backends.
 *
 * This keeps provider identity and runtime transport separate. A provider can
 * be backed by an OpenAI-compatible HTTP endpoint, a native API adapter, or a
 * local agent runtime such as Codex CLI while still exposing the same
 * [ArbigentAi] surface to the agent loop.
 */
public interface ArbigentAiProvider {
  public val metadata: ArbigentAiProviderMetadata

  public fun createAi(): ArbigentAi
}

public data class ArbigentAiProviderMetadata(
  public val id: String,
  public val label: String,
  public val runtime: ArbigentAiRuntime,
  public val capabilities: Set<ArbigentAiCapability>,
)

public data class ArbigentAiRuntime(
  public val id: String,
  public val transport: ArbigentAiTransport,
  public val modelName: String? = null,
  public val source: String? = null,
)

public enum class ArbigentAiTransport {
  OpenAiCompatibleHttp,
  CodexCliExec,
  AnthropicMessagesHttp,
}

public enum class ArbigentAiCapability {
  AgentDecision,
  ScenarioGeneration,
  ImageAssertion,
  VisionInput,
}
