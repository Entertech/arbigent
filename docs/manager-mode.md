# Manager mode + stall/loop detection

Opt-in, deterministic supervision that keeps the executor (the per-step decision
loop) from grinding on a failing approach during long multi-step tasks. Default OFF;
zero extra LLM cost. Built alongside the fuzzy decision cache as the "multi-step
reliability" work.

## Why

The measured store task (from home → 2nd popular app → its 5th review, both real
devices, 5 models) split cleanly: the **reasoning** models (gemini, glm, mimo)
planned the navigation and succeeded; the **fast non-reasoning** models (doubao,
qwen) got lost and failed 0/2. The failure shape was always the same — the executor
keeps the whole history in its prompt but never concludes *"this approach is not
working, change strategy"*: qwen scrolled one app page for **17 of 20 steps**;
doubao relaunched the same app in a loop.

## Detectors (three, complementary)

1. **`revisitedScreenHintOrNull`** (always on) — exact UI-tree-hash repeat: the
   agent returned to a screen it has been on ≥3 times (A→B→A cycles). Fires a
   "LOOP DETECTED, you've tried X/Y here" pivot hint.
2. **Manager signal A** (`ManagerMode`, opt-in) — the **same action** repeated on an
   **identical, non-blank** tree ≥3× (tap a dead button / relaunch in place).
3. **Manager signal B** (`ManagerMode`, opt-in) — **same-screen dwell**: the model's
   own `imageDescription` stays Jaccard-similar (≥0.7) across ≥4 of the recent steps.

`ManagerMode.enabled()` gates A+B behind `ARBIGENT_MANAGER_MODE` (or
`-Darbigent.managerMode`) in `{1,true,on,yes}`. Both emit a "PROGRESS STALL — stop
repeating, go back/home and take a different route (different tab/section, or
search)" hint, injected into `aiHints` next to the revisit hint (outside both cache
hashes, so the replay cache is unaffected).

## The key finding: tree-hash detection is blind to scroll stalls

On-device validation exposed why signals 1 and A were insufficient. qwen sat on one
app page for 17/20 steps — but **every scroll changes the optimized tree** (scroll
position, lazily-rendered rows), so the tree *hash* churned every step and neither
the exact-hash revisit detector nor signal A ever fired. The robust signal is the
model's **own `imageDescription`** (signal B): it summarises the *logical* screen and
survives scroll-induced tree churn. Lesson: for "stuck scrolling" stalls, compare
semantic screen descriptions, not tree hashes.

Productive scrolling is *not* flagged: when you are actually progressing, the
described screen changes each step (list → detail → reviews), so the same-screen
count stays low. Thresholds (A:3, B:4) are above what the winners ever dwell
(gemini 7-8 steps, glm 7, mimo 11 — they move every 1-2 steps), so manager mode does
not trip them.

## Validation outcome (honest)

With manager mode on, signal B **fires correctly** on the qwen stall (escalating
"stuck 4→8 steps"). But **recovery is model-dependent**: across two runs qwen once
acted on the hint (executed *Go to home* and broke the loop) and once ignored it and
kept scrolling. Deterministic hinting **detects reliably but cannot force a weak
model to replan**. The genuine fix for weak models is an **LLM-backed manager** that
decomposes the goal into ordered subgoals and can take over navigation — the natural
next step, following the default-null interface pattern of
`ArbigentAiDecisionCache.getSimilarKey` (add e.g. `ArbigentAi.proposePlan(...) = null`
so only the primary provider implements it, no breaking change).

## Files

- `ManagerMode.kt` (signals A+B, opt-in gate), wired in `ArbigentAgent.kt` next to
  the revisit hint. `ManagerModeTest.kt` (7 tests). No Maestro/schema/rpc change.

## Rejected alternatives

- **Lower the same-screen threshold further to force a rescue.** Run-to-run sampling,
  not threshold timing, decided whether qwen complied — tuning can't fix model
  compliance; only a real (LLM) manager can.
- **Always-on (not opt-in).** Changes default agent behavior for every user; the
  conservative thresholds make it safe but the value is unproven enough to keep off
  by default.
- **Screenshot-diff stall detection.** `detectStuckScreen` already covers consecutive
  identical *frames*; it misses the "screen scrolls a little but goes nowhere" case
  that signal B catches via the semantic description.
