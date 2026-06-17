package io.github.takahirom.arbigent

/**
 * Manager mode (opt-in, default OFF): a lightweight deterministic "manager" that
 * supervises the executor and injects a strategy-change directive when it detects
 * the executor is grinding without progress. Two signals (measured 2026-06-17 on
 * the store task, where qwen stayed on one app page for 17/20 steps and doubao
 * relaunched the same app in a loop):
 *
 *  A. Frozen-screen grind: the SAME action repeats on an UNCHANGED (identical,
 *     non-blank) optimized tree. Catches "tap a dead button / relaunch in place".
 *  B. Same-screen dwell: the model's OWN imageDescription stays highly similar for
 *     many steps. This survives the scroll-position tree churn that defeats both
 *     signal A and [revisitedScreenHintOrNull] (their exact tree-hash changes every
 *     scroll), which is exactly why qwen's "stuck on the app page, scrolling
 *     forever" went undetected by tree-hash methods.
 *
 * Why it stays safe: opt-in (default off), so default behavior is unchanged; the
 * thresholds (A: 3 frozen repeats; B: 5 same-screen steps) are high enough that
 * the models that SOLVED the task — gemini 7-8 steps, glm 7, mimo 11 — never dwell
 * long enough to trip B. No extra LLM call: pure code over step history. An
 * LLM-backed plan decomposer (manager proposes ordered subgoals, executor runs
 * them) is the natural next step, following the same default-null interface
 * pattern as [ArbigentAiDecisionCache.getSimilarKey].
 */
internal object ManagerMode {
  /** Opt-in via -Darbigent.managerMode / ARBIGENT_MANAGER_MODE in {1,true,on,yes}. */
  fun enabled(): Boolean {
    val raw = (System.getProperty("arbigent.managerMode")
      ?: System.getenv("ARBIGENT_MANAGER_MODE"))?.trim()?.lowercase()
    return raw == "1" || raw == "true" || raw == "on" || raw == "yes"
  }

  fun interventionHintOrNull(
    previousSteps: List<ArbigentContextHolder.Step>,
    window: Int = 8,
    repeatThreshold: Int = 3,
    sameScreenThreshold: Int = 4,
    sameScreenSimilarity: Double = 0.7,
  ): String? {
    val recent = previousSteps.takeLast(window)
    frozenActionStall(recent, repeatThreshold)?.let { return it }
    sameScreenStall(recent, sameScreenThreshold, sameScreenSimilarity)?.let { return it }
    return null
  }

  /** Signal A: latest [threshold]+ consecutive steps = same action on the same non-blank tree. */
  private fun frozenActionStall(recent: List<ArbigentContextHolder.Step>, threshold: Int): String? {
    val last = recent.lastOrNull() ?: return null
    val lastAction = last.agentAction?.stepLogText() ?: return null
    val lastTree = last.uiTreeStrings?.optimizedTreeString
    if (lastTree.isNullOrBlank()) return null
    var run = 0
    for (s in recent.asReversed()) {
      if (s.agentAction?.stepLogText() == lastAction && s.uiTreeStrings?.optimizedTreeString == lastTree) run++ else break
    }
    if (run < threshold) return null
    val tried = recent.mapNotNull { it.agentAction?.stepLogText() }.distinct()
    return buildString {
      append("PROGRESS STALL: you repeated \"$lastAction\" $run times and the screen did not change — this is not working. ")
      append("Stop repeating it; go back or return home and take a different route (a different tab/section, or search).")
      if (tried.size > 1) append(" Recently tried: ${tried.joinToString("; ")}.")
    }
  }

  /** Signal B: [threshold]+ of the recent steps describe the same screen (imageDescription Jaccard >= [similarity]). */
  private fun sameScreenStall(recent: List<ArbigentContextHolder.Step>, threshold: Int, similarity: Double): String? {
    val last = recent.lastOrNull() ?: return null
    val lastDesc = last.imageDescription?.takeIf { it.isNotBlank() } ?: return null
    val lastTokens = DecisionCacheFuzzy.tokenize(lastDesc)
    if (lastTokens.isEmpty()) return null
    val sameCount = recent.count { s ->
      val d = s.imageDescription
      !d.isNullOrBlank() && DecisionCacheFuzzy.jaccard(lastTokens, DecisionCacheFuzzy.tokenize(d)) >= similarity
    }
    if (sameCount < threshold) return null
    return buildString {
      append("PROGRESS STALL: you have been on the same screen for $sameCount of the last steps without reaching the goal — ")
      append("scrolling and tapping here is not getting you closer. Step back: go back or return to the home screen and take a ")
      append("fundamentally different route (open a different tab/section, or use search) instead of continuing on this screen.")
    }
  }
}
