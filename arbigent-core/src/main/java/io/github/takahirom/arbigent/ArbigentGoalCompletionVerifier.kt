package io.github.takahirom.arbigent

public fun interface ArbigentGoalCompletionVerifier {
  public suspend fun verify(
    input: ArbigentGoalCompletionVerificationInput
  ): ArbigentGoalCompletionVerificationResult
}

public data class ArbigentGoalCompletionVerificationInput(
  val goal: String,
  val maxStep: Int,
  val currentStep: Int,
  val decisionInput: ArbigentAi.DecisionInput,
  val decisionOutput: ArbigentAi.DecisionOutput,
  val previousSteps: List<ArbigentContextHolder.Step>,
)

public sealed interface ArbigentGoalCompletionVerificationResult {
  public object Accepted : ArbigentGoalCompletionVerificationResult
  public data class Rejected(val reason: String) : ArbigentGoalCompletionVerificationResult
}

public object AcceptingArbigentGoalCompletionVerifier : ArbigentGoalCompletionVerifier {
  override suspend fun verify(
    input: ArbigentGoalCompletionVerificationInput
  ): ArbigentGoalCompletionVerificationResult {
    return ArbigentGoalCompletionVerificationResult.Accepted
  }
}
