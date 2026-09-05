package io.github.takahirom.arbigent.sample.test

import io.github.takahirom.arbigent.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ArbigentAgentExecutorTest {
  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun testCacheKeyFormat() = runTest {
    val testDispatcher = coroutineContext[CoroutineDispatcher]!!

    val testDevice = FakeDevice()
    val cacheKeyCapture = FakeAi.Status.CacheKeyCapture()
    val testAi = FakeAi().apply {
      status = cacheKeyCapture
    }

    val agentConfig = AgentConfig {
      deviceFactory { testDevice }
      aiFactory { testAi }
    }

    val task = ArbigentAgentTask("id1", "Test goal", agentConfig)
    ArbigentAgent(agentConfig, testDispatcher, replayTrace = null).execute(task, MCPClient())
    advanceUntilIdle()

    // Verify cache key format
    val cacheKey = assertNotNull(cacheKeyCapture.capturedCacheKey, "Cache key should not be null")

    // Verify essential components are present and in correct order
    val keyPattern = Regex("v.+?-decision-r\\d+-uitree-[^-]+-context-[^-]+")
    assertTrue(cacheKey.matches(keyPattern), 
      """
      Cache key should match pattern: v{version}-decision-r{revision}-uitree-{hash}-context-{hash}
      Actual: $cacheKey
      """.trimIndent())
  }

  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun tests() = runTest {
    val testDispatcher = coroutineContext[CoroutineDispatcher]!!
    val agentConfig = AgentConfig {
      deviceFactory { FakeDevice() }
      aiFactory { FakeAi() }
    }

    val task = ArbigentAgentTask("id1", "goal1", agentConfig)
    ArbigentAgent(agentConfig, testDispatcher, replayTrace = null)
      .execute(task, MCPClient())

    advanceUntilIdle()
  }

  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun goalCompletionVerifierRejectsProviderGoalAchieved() = runTest {
    val testDispatcher = coroutineContext[CoroutineDispatcher]!!
    var verificationCount = 0
    val testAi = FakeAi().apply {
      status = FakeAi.Status.GoalAchieved()
    }
    val agentConfig = AgentConfig {
      deviceFactory { FakeDevice() }
      aiFactory { testAi }
      goalCompletionVerifier {
        verificationCount++
        ArbigentGoalCompletionVerificationResult.Rejected("missing explicit goal evidence")
      }
    }

    val task = ArbigentAgentTask("id1", "goal1", agentConfig, maxStep = 1)
    val agent = ArbigentAgent(agentConfig, testDispatcher, replayTrace = null)
    agent.execute(task, MCPClient())
    advanceUntilIdle()

    val lastStep = assertNotNull(agent.latestArbigentContext()?.steps()?.lastOrNull())
    assertEquals(1, verificationCount)
    assertFalse(agent.isGoalAchieved())
    assertNull(lastStep.agentAction)
    assertTrue(lastStep.feedback.orEmpty().contains("Rejected GoalAchieved"))
    assertTrue(lastStep.feedback.orEmpty().contains("missing explicit goal evidence"))
  }
}
