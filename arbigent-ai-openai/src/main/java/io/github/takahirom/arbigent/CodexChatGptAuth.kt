package io.github.takahirom.arbigent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.Base64

/**
 * Reads the Codex CLI's ChatGPT-subscription credentials (the same `auth.json`
 * the `codex` CLI maintains) so Arbigent can call the model over HTTP without
 * spawning `codex` and without a pay-per-token API key.
 *
 * Security contract: credentials are read at runtime only, never logged or
 * returned anywhere they could be printed, and the file is rewritten atomically
 * (temp + rename, 0600) only when a refresh is required — so the CLI's own
 * session is preserved. This mirrors how the Codex CLI itself uses the file.
 */
internal class CodexChatGptAuth(
  private val authFile: File = defaultAuthFile(),
  private val httpClient: HttpClient = HttpClient.newHttpClient(),
) {
  private val json = Json { ignoreUnknownKeys = true }
  private val lock = Any()
  @Volatile private var cached: Tokens? = null

  data class Tokens(val accessToken: String, val accountId: String, val refreshToken: String?)

  fun accountId(): String = current().accountId
  fun accessToken(): String = current().accessToken

  /** Returns a non-expired token, refreshing proactively if it expires soon. */
  private fun current(): Tokens = synchronized(lock) {
    val token = cached ?: read().also { cached = it }
    if (expiresWithin(token.accessToken, SKEW_SECONDS)) refreshLocked(token) else token
  }

  /** Force a refresh (e.g. after a 401) and return the new token. */
  fun forceRefresh(): Tokens = synchronized(lock) {
    refreshLocked(cached ?: read())
  }

  private fun read(): Tokens {
    require(authFile.isFile) {
      "Codex auth file not found at ${authFile.absolutePath}. Run `codex login` first."
    }
    val tokens = json.parseToJsonElement(authFile.readText()).jsonObject["tokens"]?.jsonObject
      ?: error("No tokens object in ${authFile.absolutePath}")
    val access = tokens["access_token"]?.jsonPrimitive?.content
      ?: error("No access_token in ${authFile.absolutePath}")
    val account = tokens["account_id"]?.jsonPrimitive?.content
      ?: accountIdFromJwt(access)
      ?: error("No account_id in ${authFile.absolutePath}")
    val refresh = tokens["refresh_token"]?.jsonPrimitive?.content
    return Tokens(access, account, refresh)
  }

  private fun refreshLocked(old: Tokens): Tokens {
    val refresh = old.refreshToken
      ?: throw IllegalStateException("Codex access token expired and no refresh_token available. Run `codex login`.")
    val body = buildJsonObject {
      put("client_id", CODEX_OAUTH_CLIENT_ID)
      put("grant_type", "refresh_token")
      put("refresh_token", refresh)
    }.toString()
    val request = HttpRequest.newBuilder(URI.create(OAUTH_TOKEN_URL))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(body))
      .build()
    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() !in 200..299) {
      throw IllegalStateException("Codex token refresh failed (${response.statusCode()}). Run `codex login`.")
    }
    val obj = json.parseToJsonElement(response.body()).jsonObject
    val newAccess = obj["access_token"]?.jsonPrimitive?.content ?: error("Token refresh returned no access_token")
    val newRefresh = obj["refresh_token"]?.jsonPrimitive?.content ?: refresh
    val newIdToken = obj["id_token"]?.jsonPrimitive?.content
    writeBack(newAccess, newRefresh, newIdToken)
    return Tokens(newAccess, old.accountId, newRefresh).also { cached = it }
  }

  private fun writeBack(access: String, refresh: String, idToken: String?) {
    val root = json.parseToJsonElement(authFile.readText()).jsonObject.toMutableMap()
    val tokens = (root["tokens"]?.jsonObject ?: JsonObject(emptyMap())).toMutableMap()
    tokens["access_token"] = JsonPrimitive(access)
    tokens["refresh_token"] = JsonPrimitive(refresh)
    if (idToken != null) tokens["id_token"] = JsonPrimitive(idToken)
    root["tokens"] = JsonObject(tokens)
    root["last_refresh"] = JsonPrimitive(Instant.now().toString())
    val parent = authFile.parentFile ?: File(".")
    val tmp = File.createTempFile("arbigent-auth", ".json", parent)
    tmp.writeText(JsonObject(root).toString())
    runCatching {
      Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("rw-------"))
    }
    runCatching {
      Files.move(tmp.toPath(), authFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }.onFailure {
      Files.move(tmp.toPath(), authFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
  }

  private fun expiresWithin(accessToken: String, seconds: Long): Boolean {
    val exp = jwtClaims(accessToken)?.get("exp")?.jsonPrimitive?.content?.toLongOrNull() ?: return false
    return Instant.now().epochSecond + seconds >= exp
  }

  private fun accountIdFromJwt(accessToken: String): String? {
    val auth = jwtClaims(accessToken)?.get("https://api.openai.com/auth")?.jsonObject ?: return null
    return auth["chatgpt_account_id"]?.jsonPrimitive?.content
  }

  private fun jwtClaims(jwt: String): JsonObject? {
    return runCatching {
      val payload = jwt.split(".").getOrNull(1) ?: return null
      val decoded = Base64.getUrlDecoder().decode(payload.padBase64())
      json.parseToJsonElement(String(decoded)).jsonObject
    }.getOrNull()
  }

  internal companion object {
    // Public OAuth client id used by the Codex CLI (not a secret).
    const val CODEX_OAUTH_CLIENT_ID: String = "app_EMoamEEZ73f0CkXaXp7hrann"
    const val OAUTH_TOKEN_URL: String = "https://auth.openai.com/oauth/token"
    private const val SKEW_SECONDS: Long = 120

    fun defaultAuthFile(): File {
      val home = System.getenv("CODEX_HOME")?.takeIf { it.isNotBlank() }
        ?: (System.getProperty("user.home") + File.separator + ".codex")
      return File(home, "auth.json")
    }

    private fun String.padBase64(): String = when (length % 4) {
      2 -> this + "=="
      3 -> this + "="
      else -> this
    }
  }
}
