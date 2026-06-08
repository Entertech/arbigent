# iOS Codex Task Performance Notes

## Reference Run

Command:

```bash
./arbigent-cli/build/install/arbigent/bin/arbigent run task \
  --os=ios \
  --ai-type=codex \
  "去应用商店找米哈游的第二个热门的游戏,查看第二条评论"
```

Older `arbigent-result/result.yml` artifacts can report success even when the final visual evidence is too weak. Treat `GoalAchieved` as valid only when the current screen or recorded steps prove every explicit goal constraint.

## Recent iPhone 12 mini Runs

The latest local artifacts showed these App Store task attempts:

- `1780899098399`: failed after 10 steps. It started from Looktech Lab, recovered to App Store through Home/Spotlight, typed `鹰角网络`, and stopped after submitting the search. The run did not reach the search results before the step limit.
- `1780900263382`: succeeded after 14 steps and 300.9s. It started from App Store search results, opened `明日方舟：终末地`, reached the full ratings/reviews page, and selected `GoalAchieved` when the second review `玩到57级实在玩不下去了` was centered and readable.
- `1780901671827`: reported `SUCCESS` after 21 steps and 427.5s while recovering from the wrong `鹰角网络` context into a `米哈游` search. The final summary said the requested second review was only exposed at the bottom of the screen, so this should be treated as insufficient completion evidence even though the result file says success.

The successful run still took too long:

- Codex CLI decision time: 202.6s total, 14.5s average, 49.1s max.
- Non-model time, including screenshot capture, XCTest hierarchy, annotation, action execution, and waits: about 98.3s.
- Codex session cache was active: `mode=auto`, `resumed=13/14`, `schema=14/14` on local `codex-cli 0.137.0`.
- Arbigent decision cache was `0/14` hits. This is expected for a live exploratory path because the cache key includes the current UI tree and the prompt/history context hash. It is mainly a replay/retry cache, not a per-run model-context cache.

The main quality issue in this run was action strategy, not iOS input failure: the agent tapped a second review title while it was partially visible near the bottom edge, hit the App Store tab bar, and spent multiple steps recovering. The default action set now includes `Swipe`, and the shared prompt tells the model to center partially visible edge targets before tapping.

Completion acceptance now has a provider-agnostic hook in core: `ArbigentGoalCompletionVerifier`. Codex remains a decision provider only; strict completion evidence should be enforced through this core verifier or scenario image assertions, not through Codex-specific guards.

## Timing Breakdown

The run had two histories:

- History 1: failed by step limit, 10 steps, about 237.0s total, 23.7s average per step.
- History 2: succeeded, 9 steps, about 217.3s total, 24.1s average per step.
- Combined: 19 steps, about 454s total.

Average per-step breakdown from file timestamps and Codex response logs:

- Codex CLI decision: about 15.9s.
- Screenshot, XCTest hierarchy fetch, annotation, and prompt preparation: about 7.6s.
- Action execution and post-action wait: about 0.4s.

The tap/input layer is not the meaningful bottleneck. The two high-cost areas are the Codex CLI decision boundary and iOS observation/prompt preparation.

## Back Navigation Cause

The first history reached the `原神` detail page and scrolled near reviews, but the default `--max-step=10` was exhausted before the goal was achieved. Arbigent then retried from the current device state.

During the second history, the model clicked the miHoYo developer row, landed on a broader App Store page, then clicked the top-left back button to return to search/detail navigation. That was recovery from a wrong branch after retry, not a device input failure.

For long App Store/media browsing tasks, prefer a larger single history such as:

```bash
--max-step=20 --max-retry=0
```

This reduces recovery behavior caused by restarting the agent while the device is already mid-flow.

## Current Optimization

The Codex provider now sets:

```toml
model_reasoning_effort = "low"
```

by default for every `codex exec` decision. This prevents Arbigent from inheriting a slow global Codex config such as `model_reasoning_effort = "xhigh"`.

Override when needed:

```bash
--codex-reasoning-effort=medium
```

Each Codex API log now records `durationMs`, timestamps, model, reasoning effort, session cache mode, Codex session id, whether the turn was resumed, whether schema was enforced by the CLI, screenshot path, schema path, process log path, and the final structured response. CLI exits also write and print `arbigent-result/summary.txt`, so every run has an explicit success or failure conclusion.

Codex session caching is enabled by default with:

```bash
--codex-session-cache=auto
```

In this mode, the first Arbigent step creates a persisted Codex exec session. Later steps run `codex exec resume` and send an incremental prompt containing the current UI state plus the last recorded step, relying on the resumed Codex session for earlier context. This avoids repeatedly sending the full step history through a fresh Codex session. It does not remove the local process-start cost for each `codex exec` turn.

Local `codex-cli 0.137.0` supports `codex exec resume --output-schema`, so `auto` keeps CLI schema enforcement on resumed turns. Older Codex CLI builds that lack resume schema support can still run `auto`; Arbigent then validates the returned JSON action in-process. Use this stricter mode if schema enforcement on every turn is required:

```bash
--codex-session-cache=schema-only
```

`summary.txt` now separates the two cache layers:

- `Decision cache`: Arbigent's replay cache keyed by UI tree plus prompt/history context.
- `Codex session`: Codex CLI session resume state, including resumed-turn count and schema-enforced count.

## When to Switch Providers

Codex CLI is still useful when the desired auth boundary is "use the local Codex login, no OpenAI API key". Session caching removes repeated full-history prompts, but it is not the lowest-latency provider because each step still starts a local `codex exec` process.

If optimized Codex CLI still stays above about 10s per step for routine UI decisions, the next implementation should be a direct API provider:

- Keep Arbigent's existing provider boundary.
- Add a long-lived OpenAI Responses/API transport or improve the existing OpenAI-compatible HTTP provider for this use case.
- Preserve the same structured action schema and screenshot/UI-tree inputs.
- Prefer direct app/domain APIs or deep links for deterministic tasks like App Store search when available, using the UI agent only for screens that must be visually inspected.
