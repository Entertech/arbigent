# Hybrid grounding (tree-optional + vision-coordinate fallback)

## Problem

Arbigent grounds taps through the **view tree**: the model perceives the screenshot,
but a tap is expressed as `ClickWithIndex(index)` which resolves to an element's
bounds center from the accessibility hierarchy (set-of-marks). This is the right
*default* — element-bounds taps are precise, resolution-independent, semantically
rich, and cacheable/replayable (the decision cache keys on the UI-tree hash).

It fails as the *only* grounding channel whenever the AX tree is incomplete:

- **iOS SpringBoard home screen** exposes only a handful of nodes (~13 for ~25
  visible icons); the App Store icon is visible in pixels but has no index, so the
  agent had to detour through Spotlight search (3 steps instead of 2, ~2.8× the
  Android time for the same task).
- **Games / Flutter / Canvas / WebGL / video / ad SDKs** — sparse or empty trees.
- **The iOS mirror backend** (`ARBIGENT_IOS_REAL_BACKEND=mirror`, drives Apple's
  iPhone Mirroring via `mirroir-mcp`) provides screenshots + coordinate taps but
  **no real view tree**, so index grounding starved it — it was effectively unusable.

The model has *perception without grounding*: it sees a target it cannot select.

## Design

Keep set-of-marks/index as the **primary** grounding, add a **normalized coordinate
tap** as an always-available **fallback**, and make the agent loop **tolerate an
empty tree** instead of crashing. This is "true hybrid": index-first, coordinates
when the target isn't in the tree.

Entirely **arbigent-side — no Maestro change/republish**, because Maestro's existing
percent path (`point="x%,y%"` → `tapOnRelative` → `widthGrid/heightGrid`) and the
mirror backend's `parsePoint` percent branch are already correct. We just emit `%`.

### 1. Normalized coordinate contract

`ClickAtCoordinates` now carries `xPercent`/`yPercent` (0–100). The model is asked
for a fraction `"nx,ny"` in `[0,1]` (top-left origin, center = `"0.5,0.5"`); the
parser converts to percent and `runDeviceAction` emits `"x%,y%"`.

Why normalized, not raw pixels: the model only ever sees the **annotated screenshot**,
which is rescaled (`ArbigentCanvas.load`) and then capped to a max long edge
(`capLongEdge`, default 1024). A pixel read off that downscaled image is in the
wrong space and lands far off on a high-DPI device. A *fraction* is invariant under
uniform downscaling, so each backend can scale it against its own true tap space.
The parser tolerates a mistaken 0–100 percent and clamps out-of-range values.

- `AgentCommands.kt` — `ClickAtCoordinates(xPercent, yPercent)`, emits `"x%,y%"`.
- `AgentActionJsonParser.kt` — parse `"nx,ny"` doubles → `toPercent()` (fraction,
  or 0–100 tolerance, clamped to `[0,100]`).

### 2. Hybrid policy (default-on)

`ClickAtCoordinates` is added to `defaultAgentActionTypesForVisualMode()` right
after `ClickWithIndex`, so every mobile backend has it. Prompt steering keeps the
model index-first: it uses coordinates **only** when the target is visible but
absent from `ELEMENTS`.

- `ArbigentAgent.kt` — default action set; `mergeAdditionalActions` dedups so the
  now-default action can't be double-added via the UI opt-in toggle.
- `CodexResponsesAiProvider.kt` / `CodexCliAiProvider.kt` — contract guidance.

### 3. Tree-optional loop

- `ArbigentElementList.from()` returns an empty list (was: throw
  `NodeInBoundsNotFoundException` / NPE on `!!`) when there is no in-bounds root.
- `ViewHierarchy.toOptimizedString()` returns `""` (was: throw).
- `ClickWithIndex` / `DpadAutoFocusWithIndex` use `getOrNull(index)` and throw a
  **catchable** `IllegalStateException` (surfaced as model feedback) instead of an
  uncaught `IndexOutOfBoundsException` that aborted the run — this matters for
  cached/stale indices replayed against a shorter live list.
- Side benefit: removes the ~6 s/step of forced `Thread.sleep` retries that the
  old throw-then-retry path burned on empty/tree-less screens.

### 4. Vision-aware cache key

When the optimized tree is blank, `"".hashCode()==0` collapses every tree-less
screen to the same key — a cached coordinate tap could replay on the wrong screen.
The key now folds in a **screenshot content hash** *only when the tree is blank*
(tree-rich screens keep the original key, preserving replay-cache hit rate). The
marker is bumped `decision-r2` → `decision-r3` (also invalidates stale entries whose
action serialization shape changed).

- `ArbigentAgent.kt` — `cacheKey` construction.

### 5. Mirror backend unlocked

No mirror-device change needed. `IosRealMirrorDevice` already executes coordinate
taps/swipes/type/screenshot; the above (default `ClickAtCoordinates` + tree-optional
loop + vision cache key) is exactly what it needed to operate vision-only.

## Impacted repositories / modules

- `arbigent` only: `arbigent-core` (AgentCommands, ArbigentAgent, ArbigentDevice,
  ArbigentCanvas), `arbigent-ai-openai` (AgentActionJsonParser, both Codex
  providers). **No `Maestro` change** (reuses the existing `%` path), no schema/rpc,
  no lt-vad.

## Verification

- `:arbigent-core:test` + `:arbigent-ai-openai:test` green (incl. new parser tests
  for normalized fraction, 0–100 tolerance, and clamping). `installDist` builds the
  CLI. (Pre-existing, unrelated: `arbigent-ui` fails to compile — a `flowPath`
  signature mismatch in `FixedScenariosDialog.kt`, untouched by this change.)
- **iOS, real device (iPhone 12 mini), from home page 1**: the model recognized the
  App Store icon was visible but not in `ELEMENTS` and tapped `ClickAtCoordinates
  (15%, 48%)` → App Store opened. **2 steps / 35 s**, vs the prior **3 steps / 45 s**
  Spotlight detour. Memo: *"…未出现在可点击元素列表中，因此用坐标点击该图标"*.
- **Android, real device (Pixel 4)**: unchanged — `ClickWithIndex` on the indexed
  Play Store icon (tap `540,1829`, no `%`), 2 steps. No regression where the tree is
  healthy.
- Mirror backend: unblocked at the code level by the same changes; live verification
  requires the iPhone Mirroring + `mirroir-mcp` environment.

## Rejected alternatives

- **Coordinate fallback only when the tree is empty.** Misses the canonical case:
  SpringBoard returns 13 elements (non-empty) but not the target, so an empty-tree
  gate never fires there. Hybrid must expose coordinates whenever the *target* is
  absent, not only when the *tree* is.
- **Raw-pixel coordinates.** Corrupted by the screenshot rescale + long-edge cap;
  no point in the tap path knows the cap scale factor to invert it.
- **Switch grounding to coordinate-first.** Loses precision, determinism, and the
  replay cache on the 90 % of screens with a healthy tree.
- **Enrich the iOS tree via `springboard.icons` (Maestro fork).** Heavier (Maestro
  change + republish) and only fixes SpringBoard; the coordinate fallback fixes the
  whole class (games/canvas/mirror) in one arbigent-side change.
