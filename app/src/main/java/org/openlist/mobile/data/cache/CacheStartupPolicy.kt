package org.openlist.mobile.data.cache

import org.openlist.mobile.core.model.CachePolicy

/**
 * Selects the policy used if a cache backend is needed before DataStore's first value arrives.
 *
 * Model defaults are not evidence of the user's persisted choice and can evict valid cache data.
 * The pending policy therefore performs no TTL, byte, or entry-count eviction. As soon as the
 * persisted policy is published, [UnifiedCacheManager.updatePolicy] applies the real limits.
 */
internal object CacheStartupPolicy {
    val pendingPersistence = CachePolicy(
        maxBytes = Long.MAX_VALUE,
        maxAgeMillis = Long.MAX_VALUE,
        maxEntries = Int.MAX_VALUE,
    )

    fun initial(persistedPolicy: CachePolicy?): CachePolicy =
        persistedPolicy ?: pendingPersistence
}
