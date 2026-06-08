# Fork Review & Remediation Plan

This is the single source of truth for the Looktech arbigent fork review and the
remediation work that follows from it. It complements, and does not duplicate,
the existing design docs:

- `docs/ai-providers.md` — provider abstraction, Codex CLI runtime, goal completion hook.
- `docs/ios-real-device.md` — XCTest real-device backend and the mirror experiment.
- `docs/ios-codex-performance.md` — Codex timing and session-cache behavior.

Read those first for the "what it should do"; this doc records "what the review
found and what we are changing".

## 1. Fork Context & Version Lineage

### Why the version number looks strange (`maestro = "2.6.0-looktech.2"`)

Two independent version axes are in play:

- **Maestro's own release version is `2.6.0`.** Upstream Maestro
  (`mobile-dev-inc/maestro`) is already at the `2.6.0` release on its `main`
  (`6213e61d chore: prepare release 2.6.0`). This number is Maestro's, not ours.
- **`-looktech.2` is our republish suffix.** We forked Maestro at its `2.6.0`
  release, made fork-local changes, and republished the artifacts under our own
  Maven coordinates `ai.looktech:*` (instead of upstream `dev.mobile:*`). `.2` is
  our second internal republish build. The fork changes that forced a republish:
  - `ceb7e5d7 Fix iOS XCTest touch orientation fallback` (the `.faceUp` / `.faceDown`
    crash fix that made pure-XCTest input work on real devices).
  - `91306c0a Include iOS driver library source in CLI artifact` (so arbigent can
    rebuild a signed `driver-iphoneos` for real devices).
  - Maven publishing config under the `ai.looktech` group.

  We republished under our own coordinates because upstream Maestro does not
  publish these artifacts in the shape arbigent's real-device path needs, and we
  needed the orientation fix before upstream shipped one.

### What "Maestro 2.0.0" refers to

"Maestro 2.0.0" is **not** a thing happening in our fork — Maestro is already at
2.6. It refers to the **arbigent-side migration** from Maestro 1.x to Maestro 2.x:

- **Upstream arbigent `main` is still on `maestro = "1.40.0"` / group `dev.mobile`.**
- The arbigent author (`takahirom`) is migrating arbigent from Maestro 1.40 → 2.0
  on in-progress branches: `tm/upgrade-maestro-to-2.0.0`, `tm/maestro-2-upgrade`,
  `tm/restore-ios-support-maestro-2`, `companion_tm/maestro-2-upgrade`. Maestro 2.0
  was a breaking major (suspend API, `executeCommands` → `runFlow`, Orchestra and
  iOS-driver restructure — note `restore-ios-support-maestro-2`), which is why
  arbigent needs a migration at all.
- **We did not wait for that migration.** We jumped arbigent straight onto our
  republished Maestro 2.6.0-looktech and adapted `MaestroDevice` ourselves.

### Long-term merge risk (the real cost of the above)

Because upstream arbigent will eventually merge takahirom's Maestro **2.0**
migration into `main`, and we already carry a Maestro **2.6-looktech** migration,
the two migrations touch the same adapter surface and **cannot be resolved by
"pick one side"**. Highest-conflict files on every future `pull upstream`:

- `ArbigentDevice.kt` (`MaestroDevice`: suspend→`runBlocking` bridge,
  `executeCommands`→`runFlow`, `screenshotsDir.toPath()`).
- `gradle/libs.versions.toml` (version + group id).
- `ArbigentAgent.kt` (verifier field, `cacheKey` `-decision-r2-`).
- `ArbigentPrompts.kt` / `AgentCommands.kt` (prompt edits + inserted `SwipeAgentAction`).
- `OpenAIAi.kt`, `RunCommand.kt` / `main.kt`.

**Sync status at review time:** both forks are exactly in sync with their
upstreams (arbigent `main` = `upstream/main` 4a2dd5b + 8 local commits; Maestro
`main` = `upstream/main` f8dad660 + 7 local commits). Nothing to pull today.

### Comparison with upstream's own Maestro-2 migration (and how to de-risk)

takahirom's most complete migration branch is `upstream/tm/maestro-2-upgrade`
(2026-02, "Upgrade Maestro 1.40.0 → 2.1.0 with iOS support", only 19 commits
ahead of main). Comparing it to ours:

**The device-adapter strategy is essentially identical — we converged
independently.** Both `MaestroDevice` (`ArbigentDevice.kt`) migrations do the same
things: add `kotlinx.coroutines.runBlocking`, bridge the now-`suspend` Maestro API
with `runBlocking { … }`, change `Orchestra(screenshotsDir = …)` to
`screenshotsDir.toPath()`, and add the new third/fourth argument to
`MaestroException.ElementNotFound`. So the adapter logic is **not** a "pick one
side" conflict — it is a mechanical merge.

**Two places where ours is intentionally *ahead* of their 2.1 branch (keep ours):**

- **`runFlow` vs `executeCommands`.** Their 2.1 branch still calls
  `orchestra.executeCommands(actions, shouldReinitJsEngine = …)`. In Maestro 2.6
  `executeCommands` is now **private** (`Orchestra.kt`); the public entry point is
  `suspend fun runFlow(commands): Boolean`. Our switch to `runFlow` (and dropping
  the `jsEngine` reflection) is therefore *required* at 2.6, not gratuitous — when
  upstream bumps 2.1 → 2.6 they will be forced to make the same change.
- Additive fork hooks in the ctor: `closeHook`, `deviceName()` preferring
  `availableDevice?.name`. These are ours to preserve.

**The real divergence is the dependency coordinate, not the code:**

| | upstream `tm/maestro-2-upgrade` | our fork |
|---|---|---|
| Maestro version | `2.1.0` | `2.6.0-looktech.2` |
| Maven group | `dev.mobile:*` (official, Maven Central) | `ai.looktech:*` (private republish) |
| Maestro patches carried | none | `ceb7e5d7` orientation fix + `91306c0a` CLI driver source |

**Why we republish, re-examined against current upstream Maestro 2.6:**

- `ceb7e5d7` (XCTest touch orientation fix) is **still required** — upstream
  Maestro 2.6 `ScreenSizeHelper.orientationAwarePoint` *still* ends in
  `default: fatalError("Not implemented yet")`, and its `actualOrientation()`
  still returns `.faceUp/.faceDown` raw, so a flat-on-table iPhone still crashes
  the runner upstream. Our fix is a clean, general bug fix.
- `91306c0a` (driver source in the CLI artifact) exists for **our** real-device
  driver-rebuild path; upstream's migration only restores iOS *simulator* support,
  so upstream will not provide this for us. (Also drives the M8 fat-jar bloat.)
- The other 5 Maestro commits are pure Maven-publishing config that exist **only
  because** we republish.

**De-risking recommendation (ordered):**

1. **Upstream `ceb7e5d7` to mobile-dev-inc/maestro.** It is a general fix, not
   Looktech-specific. If accepted, official Maestro artifacts carry it and the
   orientation reason to republish disappears.
2. **Verify whether official `dev.mobile:maestro-cli` already ships `driver/ios`
   source.** If it does, `91306c0a` is droppable and we can depend on official
   `dev.mobile:*` directly — removing the republish and its 5 publishing commits.
   If not, keep only a minimal driver-source overlay rather than a full republish.
3. **Track upstream arbigent's target Maestro line** instead of maintaining an
   independent 2.6 fork. When `tm/maestro-2-upgrade` lands on arbigent `main`,
   rebase our *additive* features (Codex provider, real-device backends, mirror,
   goal verifier) on top of it; the adapter diff then collapses to near-zero and
   only our fork-specific files remain. Validate integration early by test-merging
   our feature commits onto a local copy of their branch now.

Net: there is no architectural reason to diverge on the adapter — we already match
upstream's shape (and lead it at 2.6). The merge cost is concentrated in the
republish + version skew, and items 1–2 above can remove most of it.

## 2. Architecture Decisions Under Review

### 2.1 Goal-completion verifier — wired but not energized

`commit edd39c4` added a provider-agnostic hook
`ArbigentGoalCompletionVerifier`, threaded through `AgentConfig` / `StepInput` /
`ExecuteInput` / Builder (~10 signatures). It runs in `verifyGoalCompletion()`
after a provider returns `GoalAchieved` and after image assertions pass, before
the step is recorded as success. On `Rejected` it nulls the action and records a
feedback step, so the loop continues instead of returning a false success.

**Current reality:** the only implementation is the no-op
`AcceptingArbigentGoalCompletionVerifier` (always `Accepted`), and the CLI never
installs a real one. So end-to-end, the verifier changes **nothing** today — all
actual "don't trust premature GoalAchieved" behavior comes from prompt-text
changes (`ArbigentPrompts`, `AgentCommands`, Codex OUTPUT_CONTRACT). It is dead
infrastructure exercised only by unit tests.

**Decision:** the verifier is the natural seam to reuse Codex/OpenAI for a
second-opinion judgment. Either (a) wire a real verifier (preferred — see goal
G-Arch1), or (b) if we will not, remove the abstraction from the upstream source
files to shrink the merge surface. Do not leave it half-connected long-term.

### 2.2 The `cacheKey` `-decision-r2-` change (the "conflict" change)

`ArbigentAgent.kt`:
`"v${VERSION_NAME}-decision-r2-uitree-${uiTreeHash}-context-${contextHash}"`.

**Why it was added:** the persisted decision cache (`aiDecisionCache`) is keyed
only by version + UI-tree + context. When we changed decision *semantics* (added
the verifier hook and the stricter GoalAchieved prompt/OUTPUT_CONTRACT), an old
cache entry could **replay a GoalAchieved that was produced under the looser old
rules**. The `-decision-r2-` token is a manual cache-generation bump that forces
all decisions to be recomputed under the new contract. The "conflict" it resolves
is between the on-disk decision cache and the new decision logic.

**Is it correct / does it need changing?** Correct, keep it. Its only cost is a
one-time invalidation of *all* pre-bump decision-cache entries (not just
GoalAchieved ones) — an efficiency hit on the first run after upgrade, not a
correctness problem.

**Note on the rejection loop:** when a real verifier rejects, the rejected step
is added to context, so the next step's `contextHash` differs → the `cacheKey`
differs → the same screen does **not** replay the same cached GoalAchieved. So
the loop converges as long as the rejection is recorded in context (it is). When
we wire a real verifier (G-Arch1), add `aiDecisionCache.remove(cacheKey)` on
rejection as belt-and-suspenders, and add a test that an unchanged screen reject
converges instead of churning to `maxStep`.

### 2.3 `devicectl` duplication

`xcrun devicectl` is Apple's CoreDevice CLI; arbigent uses it to (a) discover
paired real iPhones and (b) drive install / app-state for the real-device paths.
Two thin wrappers exist independently — `IosRealDeviceCatalog` /
`ArbigentDevicectlClient` — each re-implementing `runJson` / `runPlain` (same 120s
timeout, Windows `NUL` redirect, `destroyForcibly`), plus two `commandExists()`
and two `setOf("mirror","mirroir")`. They grew at different times. **Decision:**
extract one shared `DevicectlClient` (goal G-Arch2).

### 2.4 Mirror backend — keep, but isolate

`IosRealMirrorDevice` (~650 lines) drives `mirroir-mcp` over hand-written
JSON-RPC and fabricates a flat `TreeNode`/`ArbigentElement` list from screen text
(fixed 48px boxes, `clickable=true`). It satisfies `ArbigentDevice` but loses the
real UI hierarchy, focus tracking, and tree optimization (`focusedTreeString()`
returns `""`). **This was intentional**: it was built when XCTest could not run;
once XCTest real-device worked, mirror was parked as an internal experiment.

**Decision:** keep mirror working at a basic level (it is gated behind
`ARBIGENT_IOS_REAL_BACKEND=mirror`), but it should eventually move to its own
branch/module so its low-fidelity device path stops widening the core merge
surface. Until then, mirror must never crash a whole scenario on an unmapped
command (see H2).

## 3. Findings Register

Severity: 🔴 high / 🟠 medium / 🟡 low. Status: ✅ fixed · 🟢 confirmed (real,
not yet fixed) · ⏳ deferred/planned · ❌ refuted.

> M1–M7 and L1–L5 were independently re-confirmed by an adversarial Codex review
> pass (all CONFIRMED, none refuted) and are **now fixed** (code-only; verified by
> build + unit tests — the iOS real-device paths still owe an on-device smoke).
> M8 (jar slimming) is **deferred** (§4). M6's trigger ("an unplugged paired
> iPhone still appears in `devicectl list devices`") is partly platform-state
> dependent, but the code-level defect — never filtering by `canConnect` and
> never trying the next candidate after `firstOrNull()` — is confirmed and fixed.

| ID | Sev | File | Issue | Status |
|----|-----|------|-------|--------|
| H1 | 🔴 | `RunCommand.kt:155`, `RunTaskCommand.kt:85` | CLI shares one `CodexCliAi` via `aiFactory = { ai }`, so `sessionId` leaks across all scenarios + retries; scenario B resumes scenario A's Codex session. | ✅ fixed |
| H2 | 🔴 | `IosRealMirrorDevice.kt:170`, `ArbigentAgent.kt:903` | `SwipeAgentAction` is in the default visual action set, but mirror's `executeActions` has no `swipeCommand` branch → `UnsupportedOperationException` → not caught → whole scenario fails. | ✅ fixed |
| M1 | 🟠 | `AgentActionJsonParser.kt:23-37` | `normalizeArguments` does not strip structural keys `action`/`text`/`arguments`; for Codex + MCP tool calls they pollute tool arguments. | ✅ fixed |
| M2 | 🟠 | `CodexCliAiProvider.kt:387-395, 497-500` | Codex hard failure throws without recording a Failed step (loses screenshot/context); stdin write not wrapped → raw broken-pipe `IOException`. | ✅ fixed |
| M3 | 🟠 | `IosRealXCTestInstaller.kt:64-70` | Real-device `uninstall()` routes to `simctl uninstall` (simulator-only); swallowed error makes `reinstallDriver=true` a silent no-op. | ✅ fixed |
| M4 | 🟠 | `ArbigentDeviceOs.kt:251-267` (+ `IosRealMirrorDevice.kt:~582`, `IosRealXCTestDevice.kt:~196`) | iproxy/devicectl `runPlain` start: PIPE set, sleep, but `isAlive`/pipes never checked → startup failure surfaces later as misleading "driver not ready". | ✅ fixed |
| M5 | 🟠 | `IosRealMirrorDevice.kt:89-97` | init builds `mcpClient` (spawns `npx mirroir-mcp` + threads) before `check_health`; FAIL throws before object exists → `close()` never called → process/thread leak. | ✅ fixed |
| M6 | 🟠 | `IosRealXCTestDevice.kt:108-126` | Offline-but-paired iPhone shadows a booted simulator: `pairedDevices()` ignores connectivity, `availableDevices(null)` sorts but doesn't filter `canConnect`. | ✅ fixed |
| M7 | 🟠 | `ArbigentDeviceOs.kt:143-213` | iproxy started before installer construction, which is outside the cleanup `try` → installer-ctor throw leaks an orphan iproxy holding the port. | ✅ fixed |
| M8 | 🟠 | `arbigent-core/build.gradle.kts:34` | `maestro-cli` is a ~104MB fat jar (ffmpeg natives) for 3 classes + `driver/ios`; bloats `lib/` to ~380MB. | ⏳ deferred |
| L1 | 🟡 | `IosRealMirrorDevice.kt` | `ArbigentMirroirMcpClient.close()` not `@Synchronized` — shutdown vs in-flight `callTool` race. | ✅ fixed |
| L2 | 🟡 | `IosRealMirrorDevice.kt` | Mirror process death has no fast-fail; reader EOF doesn't `completeExceptionally` pending futures → wait full timeout. | ✅ fixed |
| L3 | 🟡 | `ArbigentAgent.kt` | `verifyGoalCompletion` `catch(Exception)` swallows `CancellationException` (latent until a real async verifier exists). | ✅ fixed |
| L4 | 🟡 | snapshot/shutdown | Periodic snapshot coroutine + shutdown hook may concurrently write `result.yml`/`report.html`/`summary.txt` on SIGTERM. | ✅ fixed |
| L5 | 🟡 | `ArbigentHostConfig.kt:12-14` | `clear()+putAll` on `ConcurrentHashMap` non-atomic (single startup call → low). | ✅ fixed |

### Refuted during review (not bugs)

- XCTest installer reconnect does **not** leak `xcodebuild` (reconnect builds a
  fresh installer with `process=null`; old process reclaimed via `oldMaestro.close()`).
- Mirror screenshot coordinate space **matches** (`ArbigentCanvas.load` rescales
  native pixels to window-point space; element boxes drawn in the same space).
- `runFlow` discarding its Boolean is **not** a regression — upstream
  `executeCommands` discarded it too, and both default to `onCommandFailed=FAIL`
  (failures still throw). Only the `runBlocking` suspend-bridge code smell remains.

## 4. Remediation Goal & Sequencing

**Goal:** raise real-device run reliability and shrink the long-term merge
surface, without expanding the experimental mirror path into core.

Priority order and status:

1. **G-H1 — Isolate Codex sessions across runs.** ✅ Done. CLI now uses
   `aiFactory = { aiProvider.createAi() }` in `RunCommand` and `RunTaskCommand`,
   matching UI behavior, so each scenario/retry gets a fresh `CodexCliAi`
   (fresh `sessionId`). Regression test added:
   `CodexCliAiTest.createAi returns a fresh runtime per call…`.
2. **G-H2 — Mirror Swipe compatibility (basic).** ✅ Done. Added a `swipeCommand`
   branch to `IosRealMirrorDevice.executeActions` (UP/DOWN/LEFT/RIGHT mapped onto
   the mirror `swipe` geometry, reusing the scroll convention), and changed the
   generic fallback from `UnsupportedOperationException` to `IllegalStateException`
   so any future unmapped command is a recoverable feedback step, not a scenario
   abort.
3. **G-M (real-device hardening)** — ✅ M1–M7 + L1–L5 done; ⏳ M8 deferred. As
   implemented:
   - **M1** ✅ — the MCP branch in `AgentActionJsonParser` now uses the nested
     `arguments` object when present (Codex envelope) and otherwise the top-level
     object (OpenAI flat args), stripping arbigent answer-item keys in both. Tests:
     `mcp action keeps only nested tool arguments from codex envelope`,
     `mcp action strips answer items from flat openai arguments`.
   - **M2** ✅ — `decideAgentActions` wraps `runCodexJson`, records a
     `FailedAgentAction` step (with screenshot + UI tree) before rethrowing a hard
     failure; the stdin write is guarded so a broken pipe falls through to the
     exit-code/timeout path instead of throwing a raw `IOException`.
   - **M3** ✅ — installer `uninstall()` now uses `ArbigentDevicectlClient.uninstall`
     (`xcrun devicectl device uninstall app`) for the real UDID and logs failures.
   - **M4** ✅ — iproxy `start()` redirects to a log file, checks `isAlive` after
     the warmup, and throws a clear "port in use" error on early exit; both
     devicectl `runPlain` copies redirect stderr to a file (no pipe-buffer block).
   - **M5** ✅ — mirror `init` wraps the health check in try/catch and
     `mcpClient.close()` on failure before rethrowing.
   - **M6** ✅ — `selectDevices` filters by `canConnect` when auto-selecting and
     fails with a clear "paired but not connectable" error for an explicit offline
     id. Pure function extracted and unit-tested.
   - **M7** ✅ — installer construction moved inside the cleanup `try`, so an
     installer-ctor throw still closes the already-started iproxy.
   - **L1** ✅ — mirror MCP `process`/`writer` are `@Volatile`; `close()` stays
     non-`@Synchronized` on purpose (synchronizing on the `callTool` monitor would
     block shutdown behind an in-flight call) and relies on cancel + L2 fast-fail.
   - **L2** ✅ — the stdout reader `finally` fails all pending futures on EOF /
     process death, so blocked callers return immediately instead of at timeout.
   - **L3** ✅ — `verifyGoalCompletion` rethrows `CancellationException` before the
     broad catch.
   - **L4** ✅ — `saveExecutionArtifacts` is serialized on a shared lock so the
     periodic snapshot and shutdown hook can't interleave and truncate artifacts.
   - **L5** ✅ — `ArbigentHostConfig` swaps an immutable map via `AtomicReference`.
   - **M8** ⏳ deferred — slimming the ~104MB `maestro-cli` fat jar means stripping
     intra-jar `deps/simulator-server/**` native resources from the distribution.
     That risks the real-device driver-rebuild path (the `driver/ios` source we
     deliberately bundle via Maestro `91306c0a`) and can only be validated on a
     real device, so it is kept out of this code-only batch. Do it alongside the
     G-Arch republish review, with an on-device smoke.
4. **G-Perf — Codex execution efficiency (real-device measured).** ✅ Done. The
   dominant, app-independent latency lever is the Codex session cache mode: a
   resumed session accumulates every prior screenshot server-side so per-step
   latency grows with task length (measured 27s→89s over 14 steps). Default
   flipped `auto`→`off` (stateless, self-contained prompt) → flat ~22–23s/step,
   ~2x faster total, validated on App Store / Apple Music / 闲鱼. Also refined the
   M6 device filter (don't hard-reject an explicitly requested device on a
   transient `canConnect=false`; warn and let `connectToDevice()` decide). Full
   measurement + mechanism in `docs/ios-codex-performance.md`; default documented
   in `docs/ai-providers.md`. Remaining (documented, not changed to avoid
   single-case tuning / quality risk): faster Codex model for routine decisions,
   `historicalStepLimit` bound for very long runs, and a "launch app / Home"
   action so ad-hoc `run task` can recover when it starts on the wrong app.
5. **G-Arch1 — Energize or remove the goal verifier.** Wire a real verifier that
   reuses Codex/OpenAI for second-opinion completion judgment (preferred), plus a
   convergence test; or remove the abstraction from upstream source files.
5. **G-Arch2 — Extract a shared `DevicectlClient`** to collapse the two `runJson`/
   `runPlain` copies.
6. **G-Arch3 — Move mirror to its own branch/module** so the low-fidelity device
   path stops widening the core merge surface.

### Verification gate (per global delivery rules)

iOS is real-device work, so each landed fix needs: clean build + the Apple Music
real-device smoke from `docs/ios-real-device.md`. H1/H2 so far are verified by
build + the full unit suite (`:arbigent-core`, `:arbigent-cli`,
`:arbigent-ai-openai` all green); the real-device smoke is still owed before
calling the iOS-facing fixes delivered.

## Rejected alternatives

- H1 via a `reset()` hook on `ArbigentAi` + agent-loop call: wider blast radius
  than a one-line factory change and diverges from the UI pattern. Rejected.
- H2 by adding `swipeCommand` only (leaving the `UnsupportedOperationException`
  fallback): leaves the next unmapped command fatal. Rejected in favor of also
  making the fallback recoverable.
</content>
</invoke>
