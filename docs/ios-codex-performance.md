# iOS Codex Task Performance Notes

## Reference Run

Command:

```bash
./arbigent-cli/build/install/arbigent/bin/arbigent run task \
  --os=ios \
  --ai-type=codex \
  "去应用商店找米哈游的第二个热门的游戏,查看第二条评论"
```

Observed result from `arbigent-result/result.yml`: success. The final successful history reached the App Store review page for `原神·空月之歌` and selected `Goal achieved` when the second review was visible.

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

In this mode, the first Arbigent step creates a persisted Codex exec session. Later steps run `codex exec resume` and send an incremental prompt containing the current UI state plus the last recorded step, relying on the resumed Codex session for earlier context. This avoids repeatedly sending the full step history through a fresh Codex session.

The local `codex-cli 0.130.0` supports `codex exec --output-schema`, but its `codex exec resume --help` does not expose `--output-schema`. For that version, `auto` resumes without CLI schema enforcement and lets Arbigent's in-process parser validate the returned action. Use this stricter mode if that tradeoff is not acceptable:

```bash
--codex-session-cache=schema-only
```

## When to Switch Providers

Codex CLI is still useful when the desired auth boundary is "use the local Codex login, no OpenAI API key". Session caching removes repeated full-history prompts, but it is not the lowest-latency provider because each step still starts a local `codex exec` process.

If optimized Codex CLI still stays above about 10s per step for routine UI decisions, the next implementation should be a direct API provider:

- Keep Arbigent's existing provider boundary.
- Add a long-lived OpenAI Responses/API transport or improve the existing OpenAI-compatible HTTP provider for this use case.
- Preserve the same structured action schema and screenshot/UI-tree inputs.
- Prefer direct app/domain APIs or deep links for deterministic tasks like App Store search when available, using the UI agent only for screens that must be visually inspected.
