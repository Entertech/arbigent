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

- `off` (**default**): always uses stateless `codex exec --ephemeral --output-schema`. Each decision sends a self-contained prompt (bounded text step-history + only the current screenshot) and starts no persistent session. This keeps per-step latency flat regardless of task length. See "Why `off` is the default" in `docs/ios-codex-performance.md`.
- `auto`: the first step creates a persisted Codex exec session; later steps use `codex exec resume` with a smaller incremental prompt. If the installed Codex CLI supports `resume --output-schema`, Arbigent keeps schema enforcement on resumed turns. If not, Arbigent resumes without CLI schema enforcement and still validates the returned JSON action in-process. Note: a resumed session retains every prior turn's screenshot + UI tree server-side, so per-step latency grows with task length — prefer `off` unless you specifically need server-side session continuity.
- `schema-only`: resumes only when the installed Codex CLI supports `resume --output-schema`; otherwise it keeps the older stateless `codex exec --output-schema` behavior.

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

## Model selection notes (June 2026 research)

- **"Codex Spark" (gpt-5.3-codex-spark) is text-only** — 1000+ tok/s on Cerebras,
  ChatGPT-Pro/Codex-only, no image input → cannot ground. gpt-5.5 stays the only
  vision-capable codex-backend option. Possible future role: fast text-only
  assertion/planner layer, never the per-step vision decider.
- **gemini-3-flash-preview stays the API baseline, but is at-risk**: still
  preview (never GA'd), community-reported grounding regressions (Jan 2026),
  and gemini-3.5-flash is NOT an upgrade path (3x price, Computer Use
  explicitly unsupported, no documented grounding gains).
- **Challengers worth a local eval** (replay ~50-100 logged steps; public
  leaderboards disagree across aggregators):
  1. **Doubao-Seed-2.0-lite** (Volcengine Ark, OpenAI-compatible, China-native,
     ~¥0.6/M in with 80%-off prompt-cache) — Midscene's field-tested default
     for exactly this workload.
  2. **Qwen3.7-Plus** (DashScope, GA 2026-06) — best budget-tier grounding
     report (ScreenSpot-Pro ~79 vs Gemini 3 Flash 69.1); emits absolute pixel
     coords → needs a per-model coordinate adapter (Midscene vlMode pattern).
- **Self-host grounding tier**: GUI-Owl-1.5-8B (Qwen3-VL base) beats Gemini 3
  Flash on ScreenSpot-Pro (71.1 vs 69.1), saturates mobile grounding (93.7
  ScreenSpot-v2), ~1-1.5s/step on a 4090 via vLLM FP8, and emits 0-1000
  normalized coords (= /1000 → our [0,1] contract). Do NOT build on UI-TARS:
  UI-TARS-2 weights remain closed; 1.5-7B (~50 SSP, absolute-pixel coords) is
  two generations behind. Two-stage zoom-in refinement adds +5-7 SSP points
  for one extra local call.

## Measured benchmark (June 2026, real devices, rigorous protocol)

Task: home screen -> open store -> search WeChat -> detail page -> report first
user review. Per run: force-stop store / kill foreground -> home, decision cache
wiped (it lives in `arbigent-cache/` and its key has NO model name — never
compare models without wiping it; CSV guard column must read 0 hits), single
attempt (max-retry 0). 3 runs per platform per model (`scripts/model_bench.sh`).
Answers cross-checked: every successful run independently reported the same
review (Android: Samuel Fang; iOS: A6^rikun. 提升用户体验) — protocol validated.

| model | success | mean duration | notes |
|---|---|---|---|
| doubao-seed-2.0-mini | 6/6 | 134.5s | fastest overall; plan-quota (no per-token cost) |
| glm-5.1 | 6/6 | 136.6s | fewest steps (best per-step decisions); plan-quota |
| gemini-3-flash-preview | 6/6 | 150.1s | prior baseline; pay-per-use |
| doubao-seed-2.0-pro | 6/6 | 151.3s | most rigorous goal-verification memos; thinking overhead on Android |
| doubao-seed-2.0-lite | 1/6 | — | eliminated: scroll-hunting loops; one FALSE success (opened WeCom, reported wrong app's review — also exposed a goal-verifier gap: app identity is not checked) |

Volcengine agent-plan endpoint: `https://ark.cn-beijing.volces.com/api/plan/v3/`
(OpenAI-compatible, Bearer ARK key) via `--ai-type openai --openai-endpoint ...`.
deepseek-v4/kimi/minimax on the plan are text-only or unverified for vision.

## Latest-model re-survey + API smoke tests (June 16 2026)

Direct China keys now held: Qwen(DashScope), Zhipu GLM, Moonshot Kimi, Xiaomi
MiMo, MiniMax, plus Volcengine Doubao (agent-plan) and Gemini. API-only image
smoke tests (one real screenshot -> JSON action) + a latest-versions web survey:

- **Verdict: no forced switch.** No API-accessible, China-reachable model clearly
  dominates the current doubao-seed-2.0-mini / glm-5.1 stack on neutral grounding
  benchmarks. arbigent's PRIMARY path is ClickWithIndex (set-of-marks), which
  needs reliable JSON + screen reading, NOT pixel-precise grounding — so mid-tier
  grounders perform fine; reserve the strongest grounder for ClickAtCoordinates.
- **Qwen3-VL (DashScope) is the one genuinely-new thing worth a bench — for
  LATENCY.** Smoke (raw API, short prompt): `qwen3-vl-flash` **0.7s**,
  `qwen3-vl-plus` **2.0s**, China-direct — vs the ~8-11s/call we measured for
  ARK-plan/Gemini. Published GUI-grounding specialist (ScreenSpot-Pro 61.8,
  AndroidWorld 63.7). Coordinate format = **0-1000 normalized** -> divide by 1000
  for arbigent's `ClickAtCoordinates` [0,1] (needs a per-model adapter; the index
  path is unaffected). Newer `qwen3.5-vl-plus` exists (SSP 65.6) but wasn't on the
  current key's /models list.
- **GLM — it's the access tier, not the model (corrected)**: `glm-5.1` via 火山
  ARK genuinely DOES vision — verified reading the clock off a screenshot (HTTP
  200), so the 6/6 glm-5.1 benchmark was real grounding. An earlier
  "glm-5.1 is text-first" note was WRONG: my probe used macOS `base64` (76-char
  line wrapping → embedded newlines break the data URL; even doubao-mini 400'd it).
  Clean (newline-free) base64 works. The real split:
  - **火山 ARK agent-plan key** → multimodal `glm-5.1` (and doubao) accept images,
    grounding works. **This is how to use GLM.**
  - **Zhipu-direct key (`ARBIGENT_GLM_CN_KEY`, open.bigmodel.cn)** → two distinct
    failure modes, don't conflate them:
    - When in balance: TEXT GLMs (4.6/4.7/5.1) reject image content with
      `400 / 1210 "content.type 取值范围 ['text']"` — a real model-level rejection
      (these models are text-only), not a tier thing. The VISION ids
      (`glm-5v-turbo`, `glm-4.6v`, `glm-4.5v`, `glm-4v-plus`) ARE valid on this key
      (not 404).
    - As of 2026-06-17 the account is OUT OF BALANCE: EVERY model — incl. plain
      TEXT `glm-4.6` with no image — returns `429 / 1113 "余额不足或无可用资源包,
      请充值"`. So `glm-5v-turbo` can't be empirically vision-tested here until the
      Zhipu account is recharged / a resource pack is bought. (The id resolves; the
      block is billing, not capability or permission.)
  - `glm-4.7` is the newest GLM on the Zhipu key, but the text-only tier blocks it
    too — so the GLM *version* isn't the issue; the key/endpoint is.
  - **PROVENANCE (important — the "tier" framing above is incomplete):** Zhipu's
    OFFICIAL doc lists GLM-5.1 as **text-only** (doc path literally
    `.../models/text/glm-5.1`; input modality = text). A text-only model has no
    vision encoder, so no "tier" can add image input. Therefore Volcano ARK's
    image-reading "glm-5.1" is **almost certainly NOT vanilla GLM-5.1** — ARK is a
    multi-vendor reseller and is serving *some* multimodal model under that label
    (likely GLM-5V-Turbo or a vision-wrapped variant; ARK does not disclose the
    backing model). The genuine vision GLM is the explicitly-named **GLM-5V-Turbo**.
    Implication: our "glm-5.1 6/6" result is on a **mislabeled black-box** model.
    For an unambiguous default prefer **doubao-seed-2.0-mini** (ByteDance model on
    ByteDance's own platform); if a GLM grounder is wanted, use a vision key with
    the real `glm-5v-turbo` id, not "glm-5.1".
  - **PROVENANCE — now PROVEN by a cross-platform control (2026-06-17):** the SAME
    id `glm-5.1`, SAME prompt + screenshot, behaves differently per platform:
    - **火山 ARK plan** `glm-5.1` → HTTP 200, correctly reads the lock-screen clock
      ("20:18"), `prompt_tokens=704` (image actually ingested). **Multimodal.**
    - **DashScope** `glm-5.1` → HTTP 400 `"The current model only supports text
      modality and does not support image input"`. **Text-only** (matches Zhipu's
      official spec).
    Same name, one sees images and one doesn't ⇒ **火山's "glm-5.1" is NOT vanilla
    GLM-5.1; it's a multimodal model routed/wrapped under that label.** (Caveat: a
    *1×1* probe PNG makes ARK's glm-5.1 return 400 too — that's image-validation
    failing on a degenerate image, NOT a text-only rejection; always test with a
    real screenshot.)
- **GLM-5V-Turbo availability — NEITHER 阿里云 NOR 火山 offers it (verified
  2026-06-17, empirical + catalog):** GLM-5V-Turbo is Zhipu's real vision model and
  is **exclusive to Zhipu's own platform** (open.bigmodel.cn / Z.ai, id
  `glm-5v-turbo`, 200K ctx, multimodal). As a managed API:
  - **DashScope/百炼**: `glm-5v-turbo` → `404 model_not_found`; ALL `glm-*v` ids 404.
    The `/models` catalog has only TEXT GLM (`glm-5.2/5.1/5/4.7/4.6/4.5/4.5-air`) —
    zero vision variants. Official 百炼 vision page classifies glm-5/glm-4.7 as
    纯文本模型. Control (`qwen3-vl-flash`) passed 200, so the key/endpoint are valid.
  - **火山 ARK**: `glm-5v-turbo` → `404 UnsupportedModel` on the plan endpoint (our
    `ARK_CODING_API_KEY` is plan-scoped only; it 401s the standard `/api/v3` for
    *every* model incl. doubao, so the standard endpoint is untestable with this
    key). Catalog research (multiple consistent sources): 火山 resells GLM as
    **text/coding/agent only** (GLM-4.7/5.1/5.2); vision in the bundles is Doubao /
    Kimi, never GLM. No GLM-*V variant found on 火山 anywhere. (Live JS-rendered
    模型广场 not machine-readable, but absence-of-evidence is strong + consistent.)
  - Practical upshot: want a real GLM vision model → call Zhipu bigmodel.cn directly
    with `glm-5v-turbo`. Want "GLM-ish" vision off ARK today → 火山's `glm-5.1`
    already reads images (but it's the wrapped/ambiguous model above, not officially
    GLM-5V-Turbo).
  - **VERIFIED working after recharge (2026-06-17):** with the Zhipu account back in
    balance, the genuine vision models on `ARBIGENT_GLM_CN_KEY` all read a real
    screenshot's lock-clock correctly ("20:18"), provenance-clean (Zhipu's own
    platform): `glm-5v-turbo` 200 in **~2.9s** (fastest, terse), `glm-4.6v` ~3.1s,
    `glm-4.5v` ~3.9s; prompt_tokens 600–655 confirm the image was ingested. So the
    real GLM-5V-Turbo is usable as a clean grounder — slower than `qwen3-vl-flash`
    (~0.7s) but unambiguous, unlike ARK's wrapped "glm-5.1".
- **OpenAI non-thinking vision models (June 2026, for grounding)** — two routes:
  1. **Pure non-reasoning** (no reasoning tokens, no effort knob): `gpt-4.1`
     (~0.79s, "smartest non-reasoning model"), `gpt-4.1-mini` ($0.40/$1.60,
     ~1.0s — best speed/$ balance), `gpt-4.1-nano` (~0.71s, $0.05/$0.20, fastest/
     cheapest), `gpt-4o`/`gpt-4o-mini` (legacy). 4.1 family is retired from ChatGPT
     (2026-02-13) but still callable in the API. `gpt-5-chat-latest` is the GPT-5
     "Instant" non-reasoning sibling but DEPRECATED (API shutdown 2026-07-23); there
     is NO `gpt-5.5-chat-latest`.
  2. **Current official path = GPT-5.x reasoning model with thinking OFF** via
     `reasoning.effort=none` (the new disabling value; old gpt-5 used `minimal`,
     removed on 5.1+). `gpt-5.4-mini` (marked "Default", OpenAI positions it for
     computer-use/subagents = UI grounding) + `effort=none` is the on-target current
     id; `gpt-5.4-nano` cheapest; `gpt-5.5` frontier ($5/$30). o-series (o3/o4-mini)
     are always-reasoning + deprecated — avoid.
  - **Grounding caveat**: OpenAI general models are weak at PRECISE pixel coords
    (GPT-4o ~18% ScreenSpot, 0.8% ScreenSpot-Pro vs Qwen2.5-VL/UI-TARS ~87-90%). So
    use them to PICK a Set-of-Mark index (= arbigent's `ClickWithIndex` path), never
    for raw xy. Practical pick if wiring OpenAI: `gpt-4.1-mini`, or `gpt-5.4-mini`
    +effort=none. But none beat the China-direct `qwen3-vl-flash` (~0.7s) /
    `doubao-seed-2.0-mini` on latency/cost/access; OpenAI's edge is index-pick
    judgment + clean JSON, not grounding.
- **MiMo (Xiaomi) — reinstated, it's actually capable**: endpoint
  `https://api.xiaomimimo.com/v1/` (OpenAI-compatible, `ARBIGENT_MIMO_CN_KEY`), model
  `mimo-v2.5` (also `-pro/-omni/-flash`, and `mimo-v2-*`). MEASURED: grounds correctly
  (`{"x":616,"y":591}` for Chrome, GT ~618,601) in ~3.5s; it IS a reasoning model
  (emits reasoning_tokens), natively multimodal (1100 image tokens). On the hard
  multi-step store task it went 1/2 (Android ✅, iOS ❌ ran out of steps) — better than
  doubao/qwen there. Earlier "skip / no grounding score" note was wrong.
- **Skip**: kimi vision (smoke: lazy `[0.5,0.5]` center-guess; SSP 52.8 < Qwen);
  MiniMax-M3 (multimodal input but NOT GUI-coordinate-tuned, thinking model, slow);
  gemini-3.5-flash (GA DROPPED Computer Use -> downgrade; keep gemini-3-flash-preview).
- **Multi-step agentic bench — MEASURED 2026-06-17, BOTH real devices** (Pixel 4 +
  iPhone 12 mini; NL task: from home → open store → 2nd popular free app → report its
  5th review; `arbigent run task`, max-step 20, single attempt). Success = arbigent
  judged the goal reached:
  | model | Android | iOS | verdict |
  |---|---|---|---|
  | **gemini-3-flash-preview** | ✅ 7st/324s | ✅ 8st/91s | **2/2 most reliable** |
  | **glm-5v-turbo** | ✅ 7st/128s | ✅ 19st/267s | **2/2** (fastest-success on Android) |
  | mimo-v2.5 | ✅ 11st/140s | ❌ 20st (found 2nd app, out of steps) | 1/2 |
  | doubao-seed-2.0-mini | ❌ 20st (lost) | ❌ 21st (lost) | 0/2 |
  | qwen3-vl-flash | ❌ 20st (stuck on reviews nav) | ❌ 21st (stuck on rankings) | 0/2 |
  - **KEY INSIGHT — opposite of the single-step grounding bench**: on a HARD multi-step
    agentic task the REASONING models (gemini/glm/mimo) win — thinking plans the
    navigation and escapes loops — while the FAST non-reasoning models (doubao/qwen)
    get lost despite quick per-step latency. So: simple locate-a-coordinate → use the
    fast `qwen3.6-flash`; complex multi-step navigation → use `gemini-3-flash-preview`
    or `glm-5v-turbo`. Task shape decides the model, not a single leaderboard.
  - **iOS hybrid grounding confirmed live**: models tapped the App Store icon (absent
    from the XCTest AX tree) via `ClickAtCoordinates (15%,48%)` — the coordinate
    fallback — then drove the store. The earlier "iOS can't launch the store" gap is
    closed by the hybrid path.
- **Grounding bench — MEASURED 2026-06-17** (real Android app-drawer screenshot
  1080×2400; ground truth = uiautomator element bounds; 4 spread icon targets
  Spotify/Calendar/Chrome/Gmail; ask normalized 0-1000 center, hit = point inside
  the true box):
  | model | hits | mean norm-err | mean sec | note |
  |---|---|---|---|---|
  | **qwen3-vl-flash** | 4/4 | **0.007** | **1.1** | fastest + most accurate; DashScope direct. WINNER |
  | **glm-5v-turbo** | 4/4 | **0.007** | 1.9 | ties accuracy, but a REASONING model — MUST send `thinking:{type:disabled}` + `max_tokens≥512`, else reasoning eats the budget and the JSON truncates (`{"x":619,"592}`) → all fail |
  | **gemini-3-flash-preview** | 4/4 | 0.012 | 2.3 | `thinkingConfig.thinkingBudget:0`; solid; ~2× qwen's error |
  | **doubao-seed-2.0-mini** | 4/4 | 0.016 | **13.0** | accurate but VERY slow on the ARK *plan* endpoint (agent wrapper + heavy reasoning) — the tree-driven benchmark champ is a POOR pure grounder |
  - Takeaways: (1) **qwen3-vl-flash is the default grounder** (latency+precision both
    best; model call dominates step time). (2) All 4 used 0-1000 + named `{x,y}`
    correctly (no Gemini `[y,x]` swap with named fields), so the arbigent adapter is
    just: parse `{x,y}` → `/1000` → `ClickAtCoordinates`. (3) glm-5v-turbo's
    disable-thinking requirement is a real integration gotcha. (4) "tree-driven champ"
    (doubao-mini) ≠ "good grounder".
  - Honesty: app icons are LARGE/easy targets → everyone hits 4/4; this separates on
    latency/precision, NOT capability ceiling (no small-target / ScreenSpot-Pro
    stress). N=4, single screen — directional, not definitive.
- **Self-hosting the qwen3-vl grounder (verified 2026-06-17):** `qwen3-vl-flash`
  itself is NOT self-deployable — it's a closed API-only tier on Alibaba Model
  Studio (snapshots `...-2025-10-15`, `...-2026-01-22`; `qwen3-vl-plus` also closed);
  no downloadable weights. But the OPEN Qwen3-VL family IS self-hostable (**Apache-2.0,
  commercial OK, no MAU cap**): dense 2B/4B/8B/32B + MoE 30B-A3B/235B-A22B, each
  Instruct/Thinking + FP8 + GGUF. Open SKUs are NOT flash's weights (separately tuned)
  — pick by VRAM, not "flash-equivalence". **Grounding is in the open weights** — same
  `bbox_2d [x1,y1,x2,y2]` 0-1000 convention as the API, so our `/1000 → ClickAtCoordinates`
  adapter works unchanged on a self-hosted endpoint.
  - Pick: **`Qwen3-VL-8B-Instruct`** (FP8 on a 24GB card ~10-12GB, or int4 GGUF on
    12-16GB; ScreenSpot ~94%, card markets "operates PC/mobile GUIs") = best
    grounding-per-VRAM. Cheaper: `4B-Instruct` (int4 on 6-8GB, ~93%). MoE flash-like
    (3B active=fast) but big memory: `30B-A3B-Instruct` (int4 ~18-21GB / FP8 ~30GB —
    must fit ALL 30B). Avoid 32B for a flash replacement (all-active = slow).
  - Serve: vLLM≥0.11.0 (best) / SGLang~0.5.6; `transformers>=4.57.0` +
    `qwen-vl-utils==0.0.14`. `vllm serve Qwen/Qwen3-VL-8B-Instruct --served-model-name
    qwen3-vl --max-model-len 128000 --limit-mm-per-prompt '{"image":1,"video":0}'` →
    OpenAI-compatible `/v1/chat/completions`, drop-in for the current DashScope call.
  - Gotchas: version-pin (#1 footgun); cap image `max_pixels` (~1-1.5M; too low blurs
    small targets); don't serve full 256K ctx (set 128K, KV-cache); GGUF needs the
    separate `mmproj` vision file; MoE must hold all experts. (#1576's <2% ScreenSpot-Pro
    was a coord-normalization deploy bug, not model weakness — calibrate /1000 scaling.)
- **Qwen "-VL" line is being RETIRED → migrate to the unified mainline (verified
  2026-06-17, live DashScope catalog + measured):** Qwen folded vision INTO the main
  models, so the standalone `-VL` SKUs are winding down and there is NO `qwen3.5-vl-*`
  / `qwen3.6-vl-*` / `qwen3.7-vl-*` (all 404). Retirement: qwen2.5-vl already gone
  (2026-05-13); qwen-vl-max/plus (2026-07-13); qwen3-vl-8b API (2026-07-08);
  **`qwen3-vl-flash` ~2026-09-08 → official replacement `qwen3.6-flash`** (date per
  the deprecation page; treat as tentative). `qwen3-vl-plus` still live.
  - **Measured grounding successor bench** (same screenshot, 3 targets, normalized
    0-1000): `qwen3-vl-flash` 3/3 @0.8s (retiring) → **`qwen3.6-flash` 3/3 @1.2s,
    natively multimodal, same ~1180 in / ~13 out tokens, same accuracy** = the
    drop-in. `qwen3.5-flash` 3/3 @1.2s and ~6× CHEAPER (¥0.2 vs ¥1.2 /Mtok in) but
    slightly less precise (0.013 vs 0.008). `qwen3.6-plus` 3/3 but 2.4s (grounding
    overkill). Per-call cost is a rounding error either way (~¥0.0015).
  - **`qwen3.7-plus`** (GA 2026-06, live): BEST published grounder — ScreenSpot-Pro
    **79.0** / AndroidWorld 81.0 (beats GPT-5.4 67.4) — BUT emits **absolute pixel
    coords** (needs a per-model adapter, not the VL 0-1000 path) and is slower/pricier.
    `qwen3.7` has NO flash tier yet (only plus/max). For a fast grounder stay on
    qwen3.6-flash.
  - **`gui-plus`**: a computer-use AGENT model (screenshot→structured `left_click`/
    `type`/`scroll` actions via its native tool schema, MAI-UI lineage). With a generic
    "{x,y} 0-1000" prompt it FAILS (0/3, rambles/truncates) — it's NOT a drop-in point
    grounder; only use it with the full computer-use action protocol. ~¥1.5/¥4.5 /Mtok.
  - **Self-host got a new option**: Qwen3.5/3.6 OPEN weights are now natively
    multimodal too (Apache-2.0): `Qwen3.5-4B/27B`, `Qwen3.5-35B-A3B`, `Qwen3.6-27B`,
    `Qwen3.6-35B-A3B` (Alibaba claims 3.5 multimodal > Qwen3-VL). But `Qwen3-VL-8B-Instruct`
    remains the most practical small self-host grounder (smallest 3.5 unified is 4B;
    3.6 open starts at 27B).
- **Apple-Silicon local deploy (verified 2026-06-17 on M5 Pro / 48GB):** `qwen3.6-flash`
  has NO 1:1 open weight (closed API tier); the same-gen open multimodal is
  `Qwen3.6-35B-A3B` (MoE ~3B active) / `Qwen3.6-27B` (dense), no small 3.6.
  Architecture note: `Qwen3.6-35B-A3B`'s `model_type` is literally `qwen3_5_moe`
  (3.6 folded into the 3.5 unified-MoE-VL arch).
  - **Mac engine split (Qwen3.6 vision):** runs ONLY on **`mlx-vlm ≥ v0.6.0`** (the
    "image tokens:0" bug #1057 was fixed by PR #1179 / v0.6.0, 2026-06-01). **LM Studio
    ❌** (mlx-engine fork lag, issue #325 open), **Ollama ❌** (missing `qwen35moe`
    arch), **llama.cpp** rough (segfaults, needs batch 256; no official 3.6 GGUF). By
    contrast **Qwen3-VL** vision is stable on ALL engines (MLX/llama.cpp/Ollama/LM Studio).
  - **48GB fit (MLX 4-bit):** Qwen3-VL-4B ~4GB (45-70 t/s), **Qwen3-VL-8B ~6-8GB
    (28-35 t/s) = recommended, fits under default GPU-wired cap, no tuning, any engine**,
    Qwen3-VL-32B ~20GB tight, Qwen3.6-35B-A3B ~18-20GB (18-25 t/s, MoE fast) tight,
    Qwen3.6-27B ~18-21GB tight.
  - **Commands:** safest = `pip install -U mlx-vlm; python -m mlx_vlm.server --model
    mlx-community/Qwen3-VL-8B-Instruct-4bit --port 8000` (OpenAI-compatible → the
    `/1000 → ClickAtCoordinates` adapter is unchanged). Closest-to-flash =
    `mlx-community/Qwen3.6-35B-A3B-4bit` via `mlx-vlm>=0.6.0` ONLY (raise
    `sudo sysctl iogpu.wired_limit_mb=44000` if memory-tight).
  - **Download via ModelScope, not HF** (China-direct, no proxy): `pip install -U
    modelscope; python -c "from modelscope import snapshot_download as d;
    print(d('mlx-community/Qwen3-VL-8B-Instruct-4bit'))"` → pass the returned local
    path to `mlx_vlm.load(...)`. Measured: 8B ~5GB in 179s, 35B-A3B ~18GB in 216s.
  - **MEASURED on M5 Pro/48GB (2026-06-17, same 3-target grounding bench):** both work
    via mlx-vlm 0.6.3. `Qwen3-VL-8B-4bit`: 3/3, err 0.011, ~2.2s/call steady (first
    4.8s), load 3.3s, ~6GB. `Qwen3.6-35B-A3B-4bit`: 3/3, **err 0.008 = identical to
    cloud qwen3.6-flash** (no quant accuracy loss), ~2.4s/call (first 6.4s), load 8.7s,
    ~18-20GB; MoE so as fast as the 8B despite 3× size. Cloud qwen3.6-flash is 1.2s, so
    local is ~2× slower but offline / zero-API-cost / no-proxy. CAVEAT: the 35B emits
    inconsistent coord formats (`[145,217]`, `{"x":618,600}` missing-quote) — constrain
    the JSON harder or parse loosely; the 8B's output is cleaner.
  - **WIRED INTO ARBIGENT — no Kotlin needed (verified end-to-end 2026-06-17):**
    `mlx_vlm.server` already exposes an OpenAI-compatible `/v1/chat/completions` that
    accepts `image_url`, and arbigent's CLI already has `--ai-type=openai
    --openai-endpoint --openai-model-name --openai-api-key`. So a self-hosted grounder
    is pure config:
    1. `./scripts/local-vl-server.sh` (starts mlx-vlm OpenAI server on :8080).
    2. `arbigent run --project-file=<p>.yaml --ai-type=openai
       --openai-endpoint=http://localhost:8080/v1/ --openai-model-name=<model-or-path>
       --openai-api-key=dummy-local --scenario-ids=<id>`.
    Proven on a real Pixel: a Settings→Battery scenario ran 🟢 SUCCESS, 3 steps,
    34.6s, fully driven by local Qwen3-VL-8B (the 8B handled arbigent's full
    ~5-7K-token uitree+context prompt in ~7-9s/step — slower than its ~2.5s
    grounding-only call because the prompt is much larger, but completely usable
    offline / zero-API-cost). Stop the server with `pkill -f mlx_vlm.server`.

## Pricing: glm-5v-turbo vs gemini-3-flash-preview (checked 2026-07-03)

- **glm-5v-turbo** (bigmodel.cn open platform, CN, input-length <32K tier — arbigent
  steps are ~5K, always this tier): **¥5/M input, ¥22/M output**, cache-hit ¥1.2/M
  (cache storage free for now). The ≥32K tier is ¥7/¥26.
- **gemini-3-flash-preview** (Google AI): **$0.50/M input, $3.00/M output** ≈ ¥3.6/¥21.6
  at 7.2 CNY/USD. Sticker price is slightly BELOW GLM — but it is a reasoning model
  whose thinking tokens bill as OUTPUT, so real per-run cost lands above GLM.
- **Measured arbigent consumption** (glm-5v-turbo, real run 2026-07-03): avg
  **~5.2K prompt + ~0.55K completion per step** (screenshot included). Per step
  ≈ ¥0.04; a typical 7-10-step scenario ≈ **¥0.3–0.4**. Gemini estimate for the same
  scenario: input comparable, output 3-6× larger from thinking (its 324s-for-7-steps
  Android run vs GLM's 128s implies heavy thought) → **≈ ¥0.6–1.0 per scenario**.
- **Verdict**: glm-5v-turbo is the cost/latency default (CN-direct, no proxy, cheaper
  per run, fastest Android success); keep gemini-3-flash-preview for hard multi-step
  or iOS-critical cases (most reliable 2/2, fastest on iOS) where +几毛 per run is
  irrelevant. Bulk regression → local Qwen3-VL-8B at zero API cost.

## qwen3.6-flash DOES work in arbigent — extraBody unlock (verified 2026-07-03)

- The earlier blocker (`400 ... tool_choice ... not support ... thinking mode`) is
  just DashScope's default-on thinking, and arbigent ALREADY has the plumbing to fix
  it: `ArbigentAiOptions.extraBody` merges arbitrary JSON into the request body
  (protected fields: model/messages/tools/tool_choice). In the project YAML it MUST
  be nested under `settings:` (a root-level `aiOptions:` is silently ignored):
  ```yaml
  settings:
    aiOptions:
      extraBody:
        enable_thinking: false
  ```
- Verified by replaying arbigent's exact logged request (strict tools + screenshot +
  `enable_thinking:false` + `tool_choice:required`) against DashScope: **200 in 1.6s**
  with a valid `perform_*` tool call and empty `reasoning_content`. The on-device
  green run is pending only a device unlock (both bench phones PIN-locked; a dozing
  Pixel 8 stalls the loop device-side, which also produced a red-herring "hang" during
  this investigation).
- Cost angle: ¥1.2/M input ≈ ¼ of glm-5v-turbo's ¥5/M → a 7-10-step scenario lands
  around ¥0.1 vs GLM's ¥0.3-0.4. CAVEAT: with thinking off this is a fast
  NON-reasoning agent — its sibling qwen3-vl-flash went 0/2 on the hard multi-step
  store task. Position it for simple/short scenarios; keep glm-5v-turbo/gemini for
  complex navigation. Head-to-head on the team's real scenario suite still TODO.

## qwen3.6-flash multi-step store bench — MEASURED 2026-07-06 (Pixel 8 + iPhone 12 mini)

Same task as the 2026-06-17 bench (home → store → 2nd popular free app → 5th review),
maxStep=20, maxRetry=0, thinking disabled via settings.aiOptions.extraBody.

| run | result | trajectory |
|---|---|---|
| Android #1 | ❌ 13st/56s | reached the CORRECT app's reviews page, then emitted a non-numeric ClickWithIndex arg → fatal in single-attempt mode |
| Android #2 | ❌ 20st/124s | correct reviews page, exhausted steps while counting to the 5th review |
| iOS #1 | ❌ 1st/5s | "App Store icon is not visible" → declared Failed at step 1, ignoring the LaunchApp tool |
| iOS #2 | ❌ 20st/116s | reached reviews by step 19, overscrolled into the App-Privacy section, out of steps |

**Verdict: 0/4 on the hard task — the no-thinking doctrine holds** (June: gemini 2/2,
glm 2/2, qwen3-vl-flash 0/2). BUT qualitatively far better than qwen3-vl-flash: it now
consistently REACHES the right screens (navigation solved); it loses on step economy
(13-20 steps where glm needs 7), impulse decisions (iOS step-1 give-up), and occasional
malformed tool args. ~4-6s/step vs glm ~18s/step. Recommendation unchanged: qwen3.6-flash
for simple/short scenarios (4× cheaper, fastest), glm-5v-turbo/gemini for multi-step.

### Infra findings from this bench (real-device iOS)

- **iOS device pick**: the CLI takes the FIRST iOS device; a wirelessly-paired iPhone
  (15 Pro) shadowed the USB 12 mini → xcodebuild installed to an unreachable device →
  `IOSDriverTimeoutException`. Pin with `--device=<hex UDID>` (xcodebuild-style id,
  e.g. 00008101-…; list ids with `arbigent devices`). The
  `ARBIGENT_IOS_REAL_DEVICE_ID` env var remains as a fallback.
- **Zombie xcodebuildmcp processes** (respawned by other agent sessions) again caused
  `not-currently-connectable`; `pkill -f xcodebuildmcp` → driver up in 14s. Check this
  BEFORE rebooting the phone — this round's reboot was probably unnecessary.
- **Multiple Apple Team IDs** only matters when the driver must be REBUILT
  (`~/.maestro/maestro-iphoneos-driver-build` products missing). With valid prebuilt
  products (ours: team B6Y9D6S4KK, profile valid to 2027-06, device included) no team
  id is needed. Don't uninstall the on-device runner casually.
- **Scenario YAML field names**: `maxStep` (NOT maxStepCount — unknown keys are
  silently ignored, default 10) and `maxRetry: 0` for single-attempt benches.

## Local fleet options: Ali vs ByteDance — MEASURED 2026-07-06 (M5 Pro 48G)

User fleet: 48G M5 Pro + 16G M4 + 16G M2 + RTX 3060 12G. Both vendors' viable
open weights tested locally (ModelScope, mlx-vlm 0.6.3, real moto g ground truth,
4 app-drawer targets; all three servers resident during tests — absolute latencies
are conservative).

| model (all 4bit) | grounding | protocol compliance | arbigent smoke (real device) |
|---|---|---|---|
| **Qwen3-VL-30B-A3B** (~17G) | 3/4 @~9.7s | ✅ follows 0-1000 JSON | ✅ **SUCCESS 3 steps/82s** — zero-code via mlx server |
| Qwen3-VL-8B (~5.4G) | 3/4 @~9.8s | ✅ cleanest JSON | ❌ today 0/4 attempts (June: ✅ 3st/34.6s on Pixel; memory pressure suspected — 3 servers resident) |
| **UI-TARS-1.5-7B** (ByteDance, ~4.6G) | 2-3/4 @~7s, pixel-exact when right | ❌ IGNORES format instructions — always answers `<|box_start|>(x,y)<|box_end|>` absolute pixels | ✖ not attempted: needs a native-action adapter (same wall as gui-plus) |

- **Verdict**: Ali is the deployable line. 48G Mac → **Qwen3-VL-30B-A3B-4bit** (MoE,
  3B active; navigates more decisively than 8B). 16G Macs → 8B-4bit (M2: consider 4B).
  3060 12G → 8B AWQ via vLLM or GGUF via Ollama (untested here — no access yet).
  ByteDance ships NO deployable general VLM: Doubao/Seed closed; UI-TARS-2 paper-only
  (weights unreleased, gh issue #213); UI-TARS-1.5-7B is open+strong at GUI pointing
  but protocol-locked to its own action format → adapter project, not a drop-in.
- ModelScope has ready mlx conversions for all of the above (incl. UI-TARS 4bit) —
  no HF/proxy traffic needed. Serve: `scripts/local-vl-server.sh` (pass the
  modelscope cache path as MODEL; pass the SAME path as --openai-model-name, the
  mlx server resolves the request's model field).
