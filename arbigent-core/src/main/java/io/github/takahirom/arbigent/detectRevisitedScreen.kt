package io.github.takahirom.arbigent

/**
 * Loop detection: returns the previous steps (within [window]) whose UI tree is
 * identical to the current screen's — i.e. the agent has been here before.
 *
 * Complements detectStuckScreen: stuck-screen catches "my action changed
 * nothing" (consecutive identical frames), while this catches A -> B -> A
 * revisit cycles where every consecutive frame differs but the agent keeps
 * returning to the same screen (e.g. opening the wrong app, backing out,
 * opening it again).
 *
 * Matching is an exact hash of the optimized tree string, so screens whose tree
 * contains volatile text (a status-bar clock that ticked) won't match —
 * deliberate: under-detection is safe, a false "you are looping" hint is not.
 * Blank trees (vision-only screens) are excluded for the same reason: they
 * would all collide with each other.
 */
internal fun findRevisitedSteps(
  previousSteps: List<ArbigentContextHolder.Step>,
  currentOptimizedTreeString: String,
  window: Int = 8,
): List<ArbigentContextHolder.Step> {
  if (currentOptimizedTreeString.isBlank()) return emptyList()
  val currentHash = currentOptimizedTreeString.hashCode()
  return previousSteps.takeLast(window).filter { step ->
    val tree = step.uiTreeStrings?.optimizedTreeString
    !tree.isNullOrBlank() && tree.hashCode() == currentHash
  }
}

/**
 * Builds the forced-pivot hint when the current screen is at least the third
 * visit (>= 2 prior matches): tells the model it is going in circles and lists
 * the actions already tried from this exact screen so it picks something else.
 */
internal fun revisitedScreenHintOrNull(
  previousSteps: List<ArbigentContextHolder.Step>,
  currentOptimizedTreeString: String,
): String? {
  val revisits = findRevisitedSteps(previousSteps, currentOptimizedTreeString)
  if (revisits.size < 2) return null
  val triedActions = revisits.mapNotNull { it.agentAction?.stepLogText() }.distinct()
  return buildString {
    append("LOOP DETECTED: you have already been on this exact screen ${revisits.size} times before — you are going in circles and the current approach is failing.")
    if (triedActions.isNotEmpty()) {
      append(" Actions already tried from this screen: ${triedActions.joinToString("; ")}.")
    }
    append(" Choose a DIFFERENT action or target, or reconsider the navigation path.")
  }
}
