# iOS Codex Task Performance Notes

## Android vs iOS device-layer comparison (measured)

Same Codex model (`gpt-5.5` @ low, `off` mode) on iPhone 12 mini vs Pixel 4
(Android 13) isolates what is device-specific vs model-specific.

| | iOS (App Store, off) | Android (Settings, off) |
|---|---|---|
| Codex decision avg | 22.5s | **48.6s** |
| Screenshot sent to model | ~375px wide | **1080px wide (full res)** |
| Prompt text | ~10k chars | ~5–6k chars |
| Non-model gap / step | ~9s (settle ~7s) | **~4.1s (settle ~3s)** |

Two opposite, actionable findings:

1. **Device layer: iOS is slower.** iOS post-action settle is ~7s vs Android ~3s.
   Maestro's iOS driver does `waitForAppToSettle` → `waitUntilScreenIsStatic(3000)`
   with `SCREEN_SETTLE_TIMEOUT_MS = 3000` (hardcoded `private const` in
   `IOSDriver.kt:597`, **not env-configurable**) and, if the screen never reports
   static, a ~2s fallback that polls the full view hierarchy 10× at 200ms. Pages
   with any persistent animation/live content pay the full ~5s every action.
   *iOS-specific lever (delivered, modest):* the settle timeout is now configurable
   via `MAESTRO_IOS_SCREEN_SETTLE_TIMEOUT_MS` (Maestro `looktech.3`, default 3000
   unchanged). **Measured:** dropping it to 1200ms cut the action→screenshot gap
   only ~7s→~6s — so on these screens the 3000ms cap was *not* fully consumed (the
   screen went static earlier) and the ~6s floor is dominated by the XCTest
   tap/screenshot/view-hierarchy round-trips, not the settle. The knob helps most
   on screens that never report static (persistent animation/video — e.g. Apple
   Music, the originally-documented case). Cutting the floor further means speeding
   the XCTest I/O itself (harder). Android's `input keyevent` + lighter UIAutomator
   settle is inherently faster (~3s).
   Also delivered in `looktech.3`: iOS `backPress()` now performs the left-edge
   back-swipe (was a silent no-op — see the back-nav comparison below).

2. **Model layer: Android was slower, but NOT because of image resolution
   (tested).** Android initially shipped the screenshot at full 1080×2280 vs iOS
   ~375px. Hypothesis was that ~9× the pixels caused the ~2× model time. A
   controlled re-run **refuted this**: capping the annotated image to 485×1024
   (`ARBIGENT_MODEL_IMAGE_MAX_LONG_EDGE`) left Codex time essentially unchanged
   (44.9s vs 48.6s avg) and the task still succeeded. So with `gpt-5.5` the model
   time is dominated by **reasoning over the screen content** (which differs by
   app/task — the iOS runs were App Store/Music/闲鱼, the Android run was
   Settings), not by image prefill. The "Android 2× iOS" figure is a
   task-content confound, not a platform or resolution effect.

   **Implication:** image downscaling is a *token/cost* reduction now, and a
   *latency prerequisite* for later — once a fast model replaces `gpt-5.5` (the
   real lever), image prefill + non-model settle become the bottleneck, so the cap
   matters then. arbigent already uses set-of-marks (numbered boxes +
   `ClickWithIndex` returns an *index*, not pixel coordinates) and
   `ClickAtCoordinates` is opt-in/off by default, so the cap is safe for the
   default action set. Implemented: `ArbigentCanvas.save` caps the long edge
   (default 1024, env-overridable; iOS already under it → unchanged).

> Device selection: to target one Android among several attached, set
> `ANDROID_SERIAL` (or `ARBIGENT_ANDROID_DEVICE_ID`) to its `ro.serialno`. Without
> it the CLI picks an arbitrary device.

## Session cache mode: stateless `off` is the default (measured)

The biggest, most general Codex latency lever is the session cache mode. As of
this change the default is **`off`** (was `auto`).

### Measurement — iPhone 12 mini, `gpt-5.5` @ `reasoning_effort=low`

Same App Store task, `auto` vs `off`, plus a second app to check generality:

| run | mode | steps | codex avg | codex max | codex total | per-step trend |
|---|---|---|---|---|---|---|
| Task 1 (App Store) | `auto` | 14 | 45.5s | 89.0s | 636.7s | **grows 27s→89s** |
| Task 1 (App Store) | `off`  | 13 | 23.1s | 33.5s | 299.8s | **flat ~23s** |
| Task 2 (Apple Music) | `off` | 13 | 22.5s | 26.5s | 292.1s | **flat ~22s** |

`off` is ~2x faster total, ~2.7x lower peak, and does not degrade with task length.

### Mechanism (why `auto` grows and `off` does not)

A resumed Codex session (`codex exec resume`) retains **every prior turn's
screenshot + UI tree server-side**. The model pays attention/reasoning cost over
that growing multimodal context, so each step gets slower as the task proceeds.

The smoking gun: in `auto` the per-call **prompt stayed flat (~10k chars)** across
all 14 steps, yet latency climbed 27s→89s. The growth is therefore the server-side
session, not what Arbigent sends. `off` (`codex exec --ephemeral`) starts no
session: each decision is a self-contained prompt = bounded text step-history +
**only the current screenshot**. One image per call, no accumulation → flat latency.

### Why this is general, not a single-case tune

The accumulation is a property of *any* long multimodal resumed session and is
app-independent. It was confirmed flat on two different apps (App Store, Apple
Music), and the gap *widens* with task length (step 12: 19s `off` vs 53s `auto`),
so longer/harder tasks benefit more, not less.

### Trade-offs and knobs

- `off` resends the text step-history each call. Text tokens are cheap versus
  image tokens, and the resumed prompt previously carried only the single
  `LAST_RECORDED_STEP` — so `off` actually gives the model *richer* explicit recent
  context per decision, which can improve navigation (Task 2 succeeded under `off`).
- For very long runs, bound the text history with `historicalStepLimit` to keep
  `off` flat indefinitely (it is unbounded by default; the measured 13–14 step
  tasks did not need it).
- `auto`/`schema-only` remain available via `--codex-session-cache` for callers who
  specifically want server-side session continuity.
- Model choice is the remaining floor: `gpt-5.5` at `low` effort is ~22s/step even
  stateless. A faster Codex model for routine UI decisions would lower that further;
  Arbigent intentionally does not override the user's configured model, only the
  reasoning effort.



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
