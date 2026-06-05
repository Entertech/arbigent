package io.github.takahirom.arbigent

import java.util.concurrent.ConcurrentHashMap

/**
 * Process-local host configuration loaded by CLI frontends before creating devices.
 */
@ArbigentInternalApi
public object ArbigentHostConfig {
  private val values: ConcurrentHashMap<String, String> = ConcurrentHashMap()

  public fun replace(newValues: Map<String, String>) {
    values.clear()
    values.putAll(newValues.filterValues { it.isNotBlank() })
  }

  public fun get(key: String): String? {
    return values[key]?.takeIf { it.isNotBlank() }
  }
}
