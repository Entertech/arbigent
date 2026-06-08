# AI Provider Architecture

Arbigent keeps the agent loop independent from the concrete model runtime. The public boundary is `ArbigentAiProvider`, which exposes provider identity, runtime transport, capabilities, and a factory for the `ArbigentAi` implementation used by the existing agent loop.

This follows the same design direction used by OpenClaw and HermesAgent at a smaller scale:

- Provider identity and model selection should be stable user-facing concepts.
- Runtime transport is a separate concern. An OpenAI-compatible HTTP endpoint, a native API adapter, and a local agent CLI are different runtimes even when they serve similar models.
- Capability metadata should make unsupported paths explicit instead of failing later with unclear errors.

## Core Types

- `ArbigentAiProvider`: provider boundary. It owns metadata and creates an `ArbigentAi` runtime.
- `ArbigentAiProviderMetadata`: provider id, label, runtime, and capability set.
- `ArbigentAiRuntime`: runtime id, transport, model name, and source.
- `ArbigentAiTransport`: concrete transport family. Current values are `OpenAiCompatibleHttp` and `CodexCliExec`.
- `ArbigentAiCapability`: supported Arbigent surfaces such as `AgentDecision`, `ScenarioGeneration`, `ImageAssertion`, and `VisionInput`.

## Built-in Providers

### OpenAI-compatible HTTP

`OpenAICompatibleAiProvider` wraps the existing `OpenAIAi` implementation. It is used for:

- `--ai-type=openai`
- `--ai-type=gemini`
- `--ai-type=azureopenai`
- OpenAI-compatible endpoints such as local or proxy APIs when configured through the OpenAI options

This provider supports agent decisions, scenario generation, image assertions, and image input.

### Codex CLI

`CodexCliAiProvider` is a local agent runtime backed by `codex exec`.

It does not use an OpenAI API key option. Authentication and model defaults are handled by the local Codex CLI configuration. Arbigent invokes Codex only to decide the next Arbigent action; Arbigent still owns device connection, screenshots, UI tree retrieval, action execution, retries, result files, and reports.

Arbigent explicitly sets `model_reasoning_effort` for Codex CLI decisions. The default is `low`, because mobile UI loops make many small decisions and should not inherit a slow global Codex setting such as `xhigh`. Use `--codex-reasoning-effort=medium|high|xhigh` only when a task needs deeper reasoning and the extra latency is acceptable.

Example:

```bash
arbigent run task \
  --os=ios \
  --ai-type=codex \
  --codex-model-name=gpt-5.5 \
  --codex-reasoning-effort=low \
  --max-step=20 \
  "In Apple Music, play Ado's second top song"
```

Optional environment variables:

```bash
export ARBIGENT_CODEX_COMMAND=codex
export ARBIGENT_CODEX_MODEL=gpt-5.5
export ARBIGENT_CODEX_REASONING_EFFORT=low
export ARBIGENT_CODEX_PROFILE=default
export ARBIGENT_CODEX_SANDBOX=read-only
export ARBIGENT_CODEX_APPROVAL_POLICY=never
export ARBIGENT_CODEX_TIMEOUT_MS=300000
```

Each Codex decision writes `durationMs`, timestamps, model, reasoning effort, screenshot path, schema path, process log path, and final JSON response into the step API log under `arbigent-result/jsonls/`. The CLI also writes `arbigent-result/summary.txt` and prints a final `SUCCESS` or `FAILED` conclusion with step counts, duration, last action, and result paths.

Performance notes:

- A Codex CLI step starts a local `codex exec` process and performs one vision decision. This is convenient because it reuses local Codex authentication, but it is slower than a long-lived direct API adapter.
- iOS real-device runs also spend time capturing a screenshot, fetching the XCTest view hierarchy, drawing element annotations, and building the prompt.
- When a task is close to completion but hits `--max-step`, Arbigent retries from the current device state. For long App Store or media browsing tasks, prefer a higher limit such as `--max-step=20` before increasing retries.
- If a workflow must consistently hit low single-digit seconds per step, use an OpenAI-compatible HTTP provider or add a dedicated direct API provider instead of routing every decision through Codex CLI.

See `docs/ios-codex-performance.md` for the iPhone 12 mini App Store task timing breakdown and provider-switch threshold.

Current Codex capabilities:

- `AgentDecision`
- `VisionInput`

Unsupported by the Codex provider for now:

- `ScenarioGeneration`
- `ImageAssertion`

Use an OpenAI-compatible provider for those flows until dedicated Codex implementations are added.

## Decision Contract

The Codex runtime receives:

- The current goal and prior steps.
- The screenshot, annotated with Arbigent element indexes.
- The optimized UI tree and element list.
- The available Arbigent actions and MCP tools.
- A strict JSON output schema.

Codex returns one structured action:

```json
{
  "action": "ClickWithIndex",
  "text": "2",
  "arguments": {},
  "arbigent-memo": "The second top song is visible as element 2.",
  "arbigent-image-description": "Apple Music top songs list is visible."
}
```

OpenAI function-call responses and Codex JSON responses both go through the shared `AgentActionJsonParser`, so provider-specific response formats do not fork Arbigent action semantics.
