package io.github.takahirom.arbigent

import java.util.concurrent.atomic.AtomicReference

/**
 * Process-local host configuration loaded by CLI frontends before creating devices.
 */
@ArbigentInternalApi
public object ArbigentHostConfig {
  // Swap the whole immutable map atomically. The previous clear()+putAll on a
  // ConcurrentHashMap was non-atomic, so a concurrent get() could observe an
  // empty/half-populated map mid-replace.
  private val values: AtomicReference<Map<String, String>> = AtomicReference(emptyMap())

  public fun replace(newValues: Map<String, String>) {
    values.set(newValues.filterValues { it.isNotBlank() }.toMap())
  }

  public fun get(key: String): String? {
    return values.get()[key]?.takeIf { it.isNotBlank() }
  }
}
