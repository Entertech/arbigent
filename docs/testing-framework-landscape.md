# AI mobile-testing framework landscape (vs arbigent), June 2026

Research notes: arbigent vs the UI-TARS ecosystem for *automated testing*, and a
survey of Android+iOS real-device AI test frameworks. Companion to
`agent-memory-research.md`.

## Framing: UI-TARS is a model, not a test framework

UI-TARS itself has no assertions, scenarios, caching, or reports — comparing it
to arbigent is a category error. ByteDance's official testing vehicle for it is
**Midscene.js** (web-infra-dev/midscene, 13.6k★, MIT). Notably, Midscene's own
docs **no longer recommend UI-TARS as the default model** — they steer users to
Doubao Seed / Qwen3-VL / Gemini 3.x / GLM-4.6V, with UI-TARS relegated to the
self-host option. So "use UI-TARS for testing" in practice means "Midscene + a
commercial VLM" — the same shape as "arbigent + gemini-3-flash".

## Midscene.js vs arbigent (the real comparison)

Facts verified against midscenejs.com docs + npm (June 2026):

- **iOS real device: SUPPORTED since v0.29 (Sept 2025)** via Appium
  WebDriverAgent (XCTest underneath; Mac + Xcode signing + Developer Mode +
  iproxy — same burden class as our Maestro-fork XCTest runner, but riding the
  battle-tested Appium WDA stack). Android via adb (+scrcpy), HarmonyOS via hdc,
  desktop, web. Any claim that Midscene is web-only or lacks iOS is outdated.
- **Grounding: pure vision by doctrine** (>=1.0 removed DOM/a11y grounding
  entirely). Generalizes to Flutter/RN/canvas, but localization is bounded by
  the VLM and there is no set-of-marks tree fallback.
- **Caching gap on mobile (their weakest point for regression suites)**:
  element-location cache is XPath-based and **web-only**; on Android/iOS every
  locate is a fresh VLM call every run, and `aiAssert`/`aiQuery` ALWAYS call the
  model. No UI-state-keyed decision replay. A nightly device suite is slower,
  costlier, and less reproducible than arbigent's tree-hash decision cache.
- **No scenario dependency graph** — YAML tasks are linear per file; suite
  orchestration (shared login etc.) must be hand-built in Playwright.
- **Where it beats us**: DX and reporting (Playwright fixture, polished HTML
  visual-replay reports, Markdown export, MCP server, Studio app, playground),
  open-VLM support breadth, community velocity (175 releases, weekly cadence).

### Verdict for testing

| Dimension | arbigent (this fork) | Midscene / UI-TARS path |
|---|---|---|
| Deterministic replay (CI) | ✅ tree-hash decision cache | ❌ on mobile (every step = live VLM) |
| Scenario deps / suites | ✅ dependency graph + goal verification | ❌ linear YAML / DIY in Playwright |
| Grounding | ✅ hybrid a11y SoM + vision fallback | vision-only (better on canvas/Flutter, worse precision w/ tree) |
| iOS real device | ✅ Maestro-fork XCTest (proven here) | ✅ Appium WDA (since 2025-09) |
| Reports/DX/community | basic HTML/YAML | ✅ much stronger |
| Model freedom | ✅ any (OpenAI/Gemini/Codex/...) | ✅ broad incl. self-host VLMs |
| Step latency potential | 3–25s (API models) | same class w/ API VLMs; UI-TARS-2 self-host ~2.5s |

For *assistant-style task completion* a trained native agent model (UI-TARS-2,
~2.5s/step) is ahead. For *regression testing* — determinism, replay, suite
structure, assertions — arbigent's architecture is the stronger fit; the same
conclusion is validated commercially by GPT Driver (below).

## Open-source landscape (Android + iOS real device, test-oriented)

- **minitap/mobile-use** (2.6k★): 100% AndroidWorld agent; iOS **simulators
  only** ("Physical iOS devices are not yet supported"). Agent, not a test
  framework (no assertions/cache/reports).
- **droidrun → mobilerun** (8.5k★): Android via accessibility Portal; iOS
  real-device portal exists since ~v0.5.5 but is embryonic (50★, 42 commits).
  No assertion DSL / decision cache / reports.
- **mobile-next/mobile-mcp** (5.2k★): MCP plumbing with genuine iOS real-device
  support (WDA) — a building block for a host agent, zero test features.
- **Maestro upstream**: real-iOS local support **still unresolved after 3 years**
  (issue #686); AI features are experimental assertion helpers + paid cloud
  (Robin). Our Maestro fork's XCTest runner fills exactly this gap.
- **Alumnium** (905★): NL actions/assertions layered on Appium (inherits real
  devices), but step-granular — you author the skeleton; no goal-driven agent.
- Research agents (AppAgent, Mobile-Agent-v3, DroidAgent): Android-only, no
  test harness.

**Conclusion: no OSS framework today does Android+iOS real-device AI *testing*
strictly better than this design. Midscene is the one credible end-to-end
rival.**

## Commercial landscape (selected)

- **GPT Driver (MobileBoost)** — the architectural twin: deterministic
  commands-first against the UI hierarchy, AI vision fallback, zero temperature,
  pinned model snapshots, and a screen/prompt cache that skips re-deciding
  known-good states. Independently validates arbigent's design (SoM tree
  grounding + coordinate fallback + decision cache). Adds: SDK injection into
  existing XCUITest/Espresso/Appium suites, managed device clouds, on-prem.
- **Device-cloud incumbents** (BrowserStack 30k+ devices, LambdaTest KaneAI,
  Kobiton, Perfecto, Testsigma): AI at authoring/maintenance time (NL→tests,
  locator self-healing) compiled to conventional engines — deterministic and
  fast per run, but cannot absorb unscripted UI drift at runtime.
- **Runtime-agent startups** (Autify Aximo, Drizz, AskUI): vision-only agents
  on real devices; none document a decision-cache/replay mechanism.
- Marketing caveats: Waldo/Tricentis and Functionize and Momentic are
  virtual/emulated-device only despite "iOS and Android" claims.

## Strategic read for this fork

Defensible moat = the combination nobody else ships in OSS:
1. **UI-tree-hash decision cache → near-deterministic CI replay** (only GPT
   Driver has a commercial equivalent; Midscene structurally can't on mobile).
2. **Scenario dependency graph + goal verification.**
3. **Hybrid grounding** (tree SoM precision + vision fallback generality).
4. **Working iOS real-device path.**

Worth adopting from rivals:
- Midscene-grade **HTML visual-replay reports** (our biggest DX gap).
- An **open-VLM grounding tier** (Qwen3-VL / UI-TARS-1.5-7B self-host) for the
  latency floor, as already planned in `agent-memory-research.md`.
- Midscene's **instant-action split** (locate vs act as separate cheap calls).
- Watch droidrun's ios-portal as an alternative real-iOS actuation layer.

## Rejected alternatives

- Migrating to Midscene: loses decision-cache replay + scenario deps — the two
  properties that make agentic testing CI-viable; gains would be reports/DX,
  which are cheaper to build than to re-acquire determinism.
- Adopting UI-TARS-desktop as harness: assistant runtime, no test features,
  desktop/browser only — wrong layer.
