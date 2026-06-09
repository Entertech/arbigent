package io.github.takahirom.arbigent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class CodexDecisionFormatTest {
  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun `parses a single json object`() {
    val obj = CodexDecisionFormat.parseJsonObject(
      """{"action":"GoalAchieved","text":""}""", json
    )
    assertEquals("GoalAchieved", obj["action"]!!.jsonPrimitive.content)
  }

  @Test
  fun `takes the first object when the model emits two concatenated objects`() {
    // gpt-5.5 occasionally streams two JSON objects back-to-back; the old parser
    // failed with "Expected EOF". The first balanced object must win.
    val text = """{"action":"ClickWithIndex","text":"3","arguments":"{}"}{"action":"GoalAchieved","text":""}"""
    val obj = CodexDecisionFormat.parseJsonObject(text, json)
    assertEquals("ClickWithIndex", obj["action"]!!.jsonPrimitive.content)
    assertEquals("3", obj["text"]!!.jsonPrimitive.content)
  }

  @Test
  fun `braces inside string values do not break extraction`() {
    val text = """{"action":"InputText","text":"a{b}c"}{"junk":1}"""
    val obj = CodexDecisionFormat.parseJsonObject(text, json)
    assertEquals("a{b}c", obj["text"]!!.jsonPrimitive.content)
  }

  @Test
  fun `tolerates leading prose before the object`() {
    val text = """Here is the decision: {"action":"Scroll","text":""} done"""
    val obj = CodexDecisionFormat.parseJsonObject(text, json)
    assertEquals("Scroll", obj["action"]!!.jsonPrimitive.content)
  }
}
