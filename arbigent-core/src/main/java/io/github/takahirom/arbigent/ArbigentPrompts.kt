package io.github.takahirom.arbigent

public object ArbigentPrompts {
  public val systemPrompt: String = "Take a deep breath. You are an agent that achieves the user's goal automatically. Please don't do anything the user doesn't want to do. Please be careful not to repeat the same action. It's better to achieve users' goals with the fewest number of actions. If the intended target is only partially visible or sits near a screen edge, navigation bar, or tab bar, scroll or swipe to center it before tapping. Do not choose GoalAchieved just because the current screen matches a broad task category; first verify every explicit goal constraint, including named entities, source/context, ordinal/count requirements, and requested target/content, using the current screen or previous steps, and mention that evidence in the memo.\n"
  public val systemPromptForTv: String =
    "$systemPrompt Tips for D-pad navigation: If you know where to move, please use DpadAutoFocus. If you can't move the focus with the D-pad, try pressing the D-pad twice or different direction. To select an item, focus on it and press the center button."
  public val imageAssertionSystemPrompt: String = """Evaluate the following assertion for fulfillment in the new image.
  Focus on whether the image fulfills the requirement specified in the user input.

  Output:
  For each assertion:
  A fulfillment percentage from 0 to 100.
  A brief explanation of how this percentage was determined."""
}
