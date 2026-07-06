package io.github.takahirom.arbigent

import java.util.concurrent.TimeUnit

internal object IosCodeSigningTeamResolver {
  private val teamIdRegex = Regex("""\(([A-Z0-9]{10})\)""")

  private class CachedTeam(val id: String?)

  // Detection shells out to `security find-identity` and may warn; configs are
  // constructed once per listed device, so cache the outcome. A 0-team result is
  // NOT cached: it can be transient (locked keychain, `security` timeout, identity
  // installed later) and long-lived processes (arbigent-ui) must be able to pick
  // the identity up on a later refresh.
  @Volatile
  private var cachedTeam: CachedTeam? = null

  fun autoDetectTeamId(): String? {
    cachedTeam?.let { return it.id }
    val teams = detectTeamIds()
    return when (teams.size) {
      0 -> null
      1 -> teams.single().also {
        cachedTeam = CachedTeam(it)
        arbigentInfoLog("Auto-detected Apple Team ID: $it")
      }
      else -> {
        arbigentWarnLog(
          "Multiple Apple Team IDs are available for code signing: ${teams.joinToString(", ")}. " +
            "Set ios-xctest-apple-team-id in .arbigent/settings.local.yml."
        )
        cachedTeam = CachedTeam(null)
        null
      }
    }
  }

  fun detectedTeamsMessage(): String {
    val teams = detectTeamIds()
    return when (teams.size) {
      0 -> "No valid Apple code-signing teams were detected by `security find-identity -v -p codesigning`."
      1 -> "Detected Apple Team ID: ${teams.single()}."
      else -> "Detected multiple Apple Team IDs: ${teams.joinToString(", ")}."
    }
  }

  internal fun parseTeamIds(securityFindIdentityOutput: String): Set<String> {
    return teamIdRegex.findAll(securityFindIdentityOutput)
      .map { it.groupValues[1] }
      .toSet()
  }

  private fun detectTeamIds(): Set<String> {
    if (!System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) {
      return emptySet()
    }
    return runCatching {
      val process = ProcessBuilder("security", "find-identity", "-v", "-p", "codesigning")
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .redirectError(ProcessBuilder.Redirect.PIPE)
        .start()
      if (!process.waitFor(5, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return@runCatching emptySet<String>()
      }
      if (process.exitValue() != 0) {
        return@runCatching emptySet<String>()
      }
      parseTeamIds(process.inputStream.bufferedReader().readText())
    }.getOrDefault(emptySet())
  }
}
