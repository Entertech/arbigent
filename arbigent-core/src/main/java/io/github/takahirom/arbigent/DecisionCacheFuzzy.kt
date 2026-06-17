package io.github.takahirom.arbigent

/**
 * Fuzzy decision-cache matching (mobilerun's guarded macro-replay idea), OFF by
 * default. The exact UI-tree-hash cache misses whenever the tree changes at all
 * (a status-bar clock tick, a notification badge, a changing count), forcing a
 * fresh model call on a screen that is effectively the same. With a threshold
 * set, an exact miss falls back to replaying the cached decision of the most
 * similar PRIOR screen — but only one reached at the SAME point in the task.
 *
 * Why this is safe even though it is non-exact:
 * - Default off: `threshold()` is null unless explicitly configured, so the
 *   deterministic CI replay path is unchanged.
 * - Same-context guard: a cacheKey is `...-uitree-<H>-context-<H>[-screen-<H>]`.
 *   The context hash is the accumulated goal + step history and is independent
 *   of the current tree, so matching only within an identical context part means
 *   "same navigation history, cosmetically-different screen", not "any similar
 *   screen anywhere".
 * - Assertions are independent of this cache: a GoalAchieved decision (replayed
 *   or fresh) still runs its image/goal assertions live, so a fuzzy replay can
 *   never fabricate a passing test.
 */
internal object DecisionCacheFuzzy {
  /** Jaccard threshold in [0,1]; null/absent/out-of-range = exact-only (default). */
  fun threshold(): Double? {
    val raw = System.getProperty("arbigent.decisionCache.fuzzyThreshold")
      ?: System.getenv("ARBIGENT_DECISION_CACHE_FUZZY_THRESHOLD")
    return raw?.toDoubleOrNull()?.takeIf { it > 0.0 && it <= 1.0 }
  }

  private val splitter = Regex("[\\s,()\\[\\]{}:;=/|]+")

  /** Token set of an optimized UI-tree string (drops 1-char noise). */
  fun tokenize(optimizedTreeString: String): Set<String> =
    optimizedTreeString.split(splitter).filter { it.length >= 2 }.toSet()

  fun jaccard(a: Set<String>, b: Set<String>): Double {
    if (a.isEmpty() && b.isEmpty()) return 1.0
    if (a.isEmpty() || b.isEmpty()) return 0.0
    val intersection = a.count { it in b }
    val union = a.size + b.size - intersection
    return if (union == 0) 0.0 else intersection.toDouble() / union
  }

  /**
   * The context portion of a cacheKey (everything from "-context-" on), used to
   * confine fuzzy matches to the same navigation history. Returns "" if absent.
   */
  fun contextPart(cacheKey: String): String {
    val idx = cacheKey.indexOf("-context-")
    return if (idx >= 0) cacheKey.substring(idx) else ""
  }
}
