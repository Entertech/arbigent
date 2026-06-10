# GUI-agent memory & control survey (UI-TARS / Doubao / AutoGLM / academia)

Research notes informing arbigent's memory + control design. All load-bearing
claims verified against primary sources (papers / official repos); URLs inline.

## UI-TARS (ByteDance) — end-to-end VLM, text-forever + image-window memory

- Single VLM, no accessibility tree, no set-of-marks. Output = `Thought: ...\nAction: <DSL>`.
- **Memory (verified)**: full TEXT history of every step's thought+action kept
  verbatim forever; IMAGES are a sliding window of the **last 5 screenshots**
  (`MAX_IMAGE_LENGTH = 5` in UI-TARS-desktop SDK; paper sets N=5 for a 32k
  budget). Old steps degrade image→text-only, never disappear.
  [arxiv 2501.12326 §4.1/§5, github bytedance/UI-TARS-desktop]
- **UI-TARS-2**: two-tier memory — Working Memory (last k steps verbatim) +
  Episodic Memory (compressed summaries of older episodes). [arxiv 2509.02544]
- Progress tracking is *trained into thoughts* (milestone recognition, long-term
  consistency, reflection/error-correction via DPO), not a separate field.
- **Control**: native coordinates (Qwen2.5-VL convention: absolute on
  smart-resized image, factor-1000 parsing; v1 was 0-1 relative). Mobile actions:
  click / long_press / type / **scroll(point, direction)** (point-anchored!) /
  open_app(name) / drag / press_home / press_back / finished.
  A thought-free GROUNDING prompt variant exists for fast pure-grounding calls.
- **Latency**: UI-TARS-2 production = **~2.5s/step** (W4A8 quant, 47 tok/s);
  structural tricks: screenshot-only input (no tree fetch), short decode,
  5-image cap, no-thought mode for routine steps.

## Doubao phone (nubia M153) — cloud UI-TARS 2.0 + OEM system privileges

- Closed UI-TARS 2.0 variant in cloud; on-device TEE models only for sensitive
  data (OCR/NER/embeddings), "数据不离端".
- **Control is NOT accessibility**: OEM-only `INJECT_EVENTS` +
  `READ_FRAME_BUFFER` system permissions; virtual-display "shadow screen" runs
  tasks in the background while the user keeps the foreground.
- Two runtime modes: Standard (shallow/fast) vs Pro (deep reasoning) — model
  routing by task difficulty.
- Product-level persistent user-preference memory recalled across tasks.
- **No runtime replay caching** — successful trajectories are reused only as
  training data (flywheel). Users manually re-run precise prompts (>80% success).
- Enforces a fixed 1000–5000ms post-action wait (prompt-injected) to survive
  skeleton screens. Real-world: 8/23 popular apps blocked the agent
  (device-fingerprint + INJECT_EVENTS detection).

## Zhipu AutoGLM — one image in context + explicit Note actions

- Evolution: v1 (Nov 2024) = planner + grounder decoupled by the **intermediate
  interface** (planner emits a natural-language element description; a separate
  grounder resolves coordinates — worth **+9.1pts** for GPT-4o on
  WebArena-Lite). [arxiv 2411.00820] → Dec 2025 open release collapsed into ONE
  end-to-end RL-trained 9B VLM (MobileRL: AndroidWorld 75.8–80.2). [2509.18119]
- **Memory (Open-AutoGLM, verified in code)**: multi-turn conversation with
  **exactly ONE image in context** — after each step the framework strips the
  image from the old turn, keeping the model's full `<think>` text. Temporal
  continuity = retained thinking text only.
- **Aggregation across scrolls**: dedicated actions `Note` (checkpoint current
  page content into the transcript) + `Call_API` (summarize all noted content at
  the end) — the model checkpoints raw observations instead of recounting.
- **Control**: integer **0–999 normalized coordinates**; Tap (with
  sensitive-operation confirmation message), Type (auto-clears field first),
  Swipe(start,end), Long Press, Double Tap, **Launch(app by name)**, Back, Home,
  Wait, Note, Call_API, Interact (ask user to disambiguate), Take_over (hand to
  human at login/captcha). Actuation via AccessibilityService (production) / ADB
  (open framework). temperature=0, max_tokens 3000.
- **Prompt-level error-recovery rulebook** (no training needed): verify previous
  action took effect; tap no-effect → wait → retry adjusted → skip+report;
  swipe no-effect → move start point / increase distance / reverse = end of
  list; max 3 consecutive Waits then Back; never re-search a visited section.

## Academia / other systems

- **Minitap** (first 100% AndroidWorld): 6-role split (Planner / Orchestrator /
  Contextor / Cortex / Executor / Summarizer); only the Cortex needs a frontier
  model. Persistent **key-value scratchpad** (save_note/read_note) is the durable
  state, not chat history. Loop detection over action history (+9pts),
  post-action validation (+15pts). Hybrid grounding: tree primary, vision
  selective. ~31s/task, $1.07/task.
- **Mobile-Agent-v2/v3/E** (Alibaba): externalized working-memory schema — plan,
  current subgoal, progress, **notes (Notetaker: facts recorded on success
  steps)**, action history with outcome status, error history. Decision agent
  sees only current screenshot + this state, not raw history.
- **SecAgent (2026)**: **N=1 previous screenshot + maintained semantic-context
  summary ≈ N=5 raw screenshots** (72.8 vs 74.1 task acc) at **−62.7% input
  tokens and ½ time-to-first-token**; removing the semantic summary at N=1
  collapses accuracy to 56.4% — the *text summary* carries the memory, not the
  extra frames.
- **MemGUI-Bench (2026)**: memory hallucination = 58.9% of non-timeout failures;
  multi-turn conversation vs rebuild-prompt-each-step worth +18.8pp.
- **V-Droid** (Microsoft): enumerate candidate actions from the UI tree, score
  with prefill-only verifier — **0.7s/step**.
- **AppAgentX**: replay cached action chains keyed by visual element embeddings:
  −37% steps, −47% tokens on repeated flows.

## Consensus mechanisms (what everyone converges on)

1. **Externalize state; never recount from history.** Every system that solves
   counting-across-scrolls stores item IDENTIFIERS + running state in an
   explicit structure (Note actions / scratchpad / focus-content / to-do list).
   arbigent's `arbigent-progress-state` is this pattern (validated).
2. **Text is the durable memory; images are a small window.** Full text history
   + 1–5 image window. AutoGLM ships N=1 (like arbigent); SecAgent shows N=1 +
   good text summary ≈ N=5. Don't grow the image window past 2.
3. **Reflection: verify the previous action took effect** — trained (UI-TARS DPO)
   or prompted (AutoGLM rulebook) or in code (Minitap validation, screenshot
   hash diff). Code-level checks are free and effective (+15pts).
4. **Loop detection** with forced strategy pivot (+9pts).
5. **Launch-by-name beats launcher navigation** (UI-TARS open_app, AutoGLM
   Launch, Mobile Use launch_app).
6. **0–1000 integer coordinate space** is the de-facto grounding convention
   (Qwen-VL / CogAgent / AutoGLM / UI-TARS parsing factor).
7. **Replay of successful trajectories** is the biggest repeat-run latency win
   (AppAgentX), and uniquely suited to a *test* agent with named scenarios.

## arbigent adoption plan (ranked by leverage ÷ cost)

1. **Split progress_state → plan-checklist + append-only NOTES with item
   identifiers** (dedup overlapping scroll windows by name). Prompt-only change.
2. **Code-level post-action verification**: diff consecutive screenshot hashes →
   inject "your last action produced NO visible change"; after InputText,
   re-read the field from the tree and report actual vs expected. Zero LLM cost.
3. **AutoGLM error-recovery rulebook** in both Codex contracts (verify previous
   effect; tap/swipe no-effect escalation; end-of-list = reversed direction;
   anti-loop "never re-search a visited section").
4. **Loop detection in code**: hash (capped) screenshot per step; same state
   recurring within k steps without progress-state change → forced-pivot
   feedback message.
5. **LaunchApp(packageName/name) action** (+ foreground-app name injected as
   one-line screen_info each step). Kills 2–4 launcher-navigation steps.
   *Shipped (both halves)*: foreground app is injected as an AI hint each
   step — Android via one cached dumpsys call (Maestro drops the UIAutomator
   `package` attribute), iOS extracted free from the XCTest hierarchy (app
   AXElement label). Best-effort: omitted on iOS SpringBoard rather than
   guessed. Cache keys unaffected (hints are outside both hashes).
   LaunchApp action shipped too: non-destructive launch/switch by app id
   (stopApp=false, no state clearing, permissions untouched), prompt-steered
   to be used when the target app's id is known and the icon isn't visible.
   On iOS real devices this exposed and fixed a lethal Maestro stub:
   DeviceControlIOSDevice is all TODO()s and NotImplementedError (an Error)
   escaped every handler and killed the process — now wrapped by
   ArbigentDevicectlIOSDevice (devicectl-backed launch/uninstall/clearAppState,
   no-op setPermissions) plus an action-level Error->IllegalStateException
   guard. Verified: from inside Settings, "launch com.apple.AppStore" ran
   LaunchApp end-to-end in 2 steps / 11.7s.
6. **Trajectory replay cache** for scenario reruns: persist the successful
   action sequence keyed by per-step screen fingerprint; replay while
   preconditions hold, fall back to live agent on first divergence.
7. **Point-anchored Scroll(point, direction)** on top of Drag (inner
   lists/carousels).
8. **0–1000 integer coordinates** for ClickAtCoordinates/Drag (convention
   alignment with grounding-trained models).
9. Long-run history hygiene: when historicalStepLimit truncates, summarize
   evicted steps into one line instead of dropping silently; exclude
   failed/no-effect steps from rendered history (keep only their feedback).
10. (Later) per-scenario Tips memory distilled after each run → aiHints;
    self-hosted UI-TARS-1.5-7B / AutoGLM-Phone-9B as a fast grounding tier.

## Rejected alternatives

- Growing the image window to 5 (UI-TARS style): SecAgent shows ≈ +1pp for 2×
  TTFT and +62.7% tokens on API models; N≤2 + strong text state wins for us.
- End-to-end fine-tuned agent model: right long-term answer (every vendor
  converged on it) but requires training infra; framework-side mechanisms above
  capture most of the gap at zero training cost.
- OEM/system-permission control (Doubao INJECT_EVENTS): not available to us;
  XCTest/ADB remain the right actuation layer.
