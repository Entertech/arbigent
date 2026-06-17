package io.github.takahirom.arbigent

/**
 * Manager mode (opt-in, default OFF): a lightweight deterministic "manager" that
 * supervises the executor and injects a strategy-change directive when the
 * executor is grinding — repeating the SAME action while the screen does NOT
 * change. That is the failure mode that sinks weaker models on long multi-step
 * tasks (measured 2026-06-17 on the store task: qwen scrolled a page that would
 * not scroll for many steps; doubao relaunched the same app in a loop). Such
 * models keep the full history in their prompt but never conclude "this approach
 * is not working" — a manager that says so explicitly unblocks them.
 *
 * It complements [revisitedScreenHintOrNull], which catches A->B->A revisit cycles
 * across DIFFERENT screens; this catches the orthogonal case — the SAME action on
 * an UNCHANGED screen. Productive scrolling changes the optimized tree every step,
 * so it is NOT flagged: the same-tree guard keeps false positives near zero, and a
 * blank (vision-only) tree never fires (under-detection is safe; a false stall hint
 * is not).
 *
 * No extra LLM call: the manager is pure code over step history, so it adds zero
 * latency/cost. An LLM-backed plan decomposer (manager proposes ordered subgoals,
 * executor runs them) is the natural next step and would follow the same
 * default-null interface pattern as [ArbigentAiDecisionCache.getSimilarKey].
 */
internal object ManagerMode {
  /** Opt-in via -Darbigent.managerMode / ARBIGENT_MANAGER_MODE in {1,true,on,yes}. */
  fun enabled(): Boolean {
    val raw = (System.getProperty("arbigent.managerMode")
      ?: System.getenv("ARBIGENT_MANAGER_MODE"))?.trim()?.lowercase()
    return raw == "1" || raw == "true" || raw == "on" || raw == "yes"
  }

  /**
   * Returns a strategy-change hint when the latest [repeatThreshold]+ consecutive
   * steps repeated the SAME action on an UNCHANGED (identical, non-blank) UI tree,
   * else null. [window] bounds how far back the run is allowed to extend.
   */
  fun interventionHintOrNull(
    previousSteps: List<ArbigentContextHolder.Step>,
    window: Int = 6,
    repeatThreshold: Int = 3,
  ): String? {
    val recent = previousSteps.takeLast(window)
    val last = recent.lastOrNull() ?: return null
    val lastAction = last.agentAction?.stepLogText() ?: return null
    val lastTree = last.uiTreeStrings?.optimizedTreeString
    if (lastTree.isNullOrBlank()) return null
    var run = 0
    for (s in recent.asReversed()) {
      val a = s.agentAction?.stepLogText()
      val t = s.uiTreeStrings?.optimizedTreeString
      if (a == lastAction && t == lastTree) run++ else break
    }
    if (run < repeatThreshold) return null
    val triedRecently = recent.mapNotNull { it.agentAction?.stepLogText() }.distinct()
    return buildString {
      append("PROGRESS STALL: you repeated \"$lastAction\" $run times and the screen did not change — this approach is not working. ")
      append("Stop repeating it. Try a fundamentally different path: go back or return to the home screen and re-approach, open a different tab/section, or use search instead of scrolling.")
      if (triedRecently.size > 1) append(" Recently tried: ${triedRecently.joinToString("; ")}.")
    }
  }
}
