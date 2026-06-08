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

Codex is not responsible for deciding whether Arbigent should trust a completed task. It returns a structured action like any other provider. `GoalAchieved` acceptance is handled in the core agent loop through `ArbigentGoalCompletionVerifier`, after image assertions and before the step is recorded as successful.

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
export ARBIGENT_CODEX_SESSION_CACHE=auto
export ARBIGENT_CODEX_PROFILE=default
export ARBIGENT_CODEX_SANDBOX=read-only
export ARBIGENT_CODEX_APPROVAL_POLICY=never
export ARBIGENT_CODEX_TIMEOUT_MS=300000
```

Codex session cache modes:

- `auto` (default): the first step creates a persisted Codex exec session; later steps use `codex exec resume` with a smaller incremental prompt. If the installed Codex CLI supports `resume --output-schema`, Arbigent keeps schema enforcement on resumed turns. If not, Arbigent resumes without CLI schema enforcement and still validates the returned JSON action in-process.
- `schema-only`: resumes only when the installed Codex CLI supports `resume --output-schema`; otherwise it keeps the older stateless `codex exec --output-schema` behavior.
- `off`: always uses stateless `codex exec --ephemeral --output-schema`.

Each Codex decision writes `durationMs`, timestamps, model, reasoning effort, session cache mode, Codex session id, whether the turn was resumed, whether schema was enforced by the CLI, screenshot path, schema path, process log path, and final JSON response into the step API log under `arbigent-result/jsonls/`. The CLI also writes `arbigent-result/summary.txt` and prints a final `SUCCESS` or `FAILED` conclusion with step counts, duration, last action, and result paths.

Performance notes:

- With `--codex-session-cache=auto`, each step still starts a local Codex CLI process, but Arbigent resumes the same Codex session so the model can reuse conversation history and the prompt can avoid resending full step history. This is convenient because it reuses local Codex authentication, but it is still slower than a long-lived direct API adapter.
- iOS real-device runs also spend time capturing a screenshot, fetching the XCTest view hierarchy, drawing element annotations, and building the prompt.
- When a task is close to completion but hits `--max-step`, Arbigent retries from the current device state. For long App Store or media browsing tasks, prefer a higher limit such as `--max-step=20` before increasing retries.
- Do not treat `Decision cache: 0/N hits` as proof that Codex session caching failed. The decision cache is a replay cache keyed by UI tree plus prompt/history context, while Codex session caching is reported separately as `Codex session: mode=..., resumed=..., schema=...`.
- The default visual action set includes `Swipe` in addition to `Scroll`. Use `Swipe DOWN` to move back up after overscrolling and `Swipe UP` to reveal lower content; this avoids multi-step recovery when a target is only partially visible near the top or bottom edge.
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

## Goal Completion

Completion validation is provider-agnostic. When any provider returns `GoalAchieved`, the core executor runs the configured `ArbigentGoalCompletionVerifier` before marking the task successful.

The default verifier accepts the provider decision for backward compatibility. Stricter runners can install a verifier that checks current UI evidence, screenshot-derived evidence, previous steps, or another model-backed judgment. A rejected completion is recorded as a feedback step and the agent continues instead of returning a false success.
