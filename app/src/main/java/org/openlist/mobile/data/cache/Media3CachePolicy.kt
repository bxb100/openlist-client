package org.openlist.mobile.data.cache

import org.openlist.mobile.core.model.CachePolicy

internal data class Media3SpanSnapshot(
    val key: String,
    val length: Long,
    val lastTouchTimestamp: Long,
)

internal data class Media3CacheEntry(
    val key: String,
    val sizeBytes: Long,
    val lastAccessAtMillis: Long,
)

/** Pure policy core kept free of Android and Media3 APIs for deterministic local tests. */
internal object Media3CachePolicyPlanner {
    fun aggregate(spans: Iterable<Media3SpanSnapshot>): List<Media3CacheEntry> = spans
        .filter { it.length > 0 }
        .groupBy(Media3SpanSnapshot::key)
        .map { (key, keySpans) ->
            Media3CacheEntry(
                key = key,
                sizeBytes = keySpans.fold(0L) { total, span -> saturatingAdd(total, span.length) },
                lastAccessAtMillis = keySpans.maxOf(Media3SpanSnapshot::lastTouchTimestamp),
            )
        }
        .sortedBy(Media3CacheEntry::key)

    fun keysToRemove(
        entries: List<Media3CacheEntry>,
        policy: CachePolicy,
        nowMillis: Long,
        alreadyPending: Set<String> = emptySet(),
    ): List<String> {
        val byLru = entries.sortedWith(
            compareBy<Media3CacheEntry> { it.lastAccessAtMillis }.thenBy(Media3CacheEntry::key),
        )
        val selected = linkedSetOf<String>()
        byLru.filter { it.key in alreadyPending }.forEach { selected.add(it.key) }
        byLru.filter { it.key !in selected && isExpired(it, policy, nowMillis) }
            .forEach { selected.add(it.key) }

        var projectedBytes = entries
            .filter { it.key !in selected }
            .fold(0L) { total, entry -> saturatingAdd(total, entry.sizeBytes) }
        var projectedEntries = entries.count { it.key !in selected }
        for (entry in byLru) {
            if (projectedBytes <= policy.maxBytes && projectedEntries <= policy.maxEntries) break
            if (!selected.add(entry.key)) continue
            projectedBytes = (projectedBytes - entry.sizeBytes).coerceAtLeast(0)
            projectedEntries = (projectedEntries - 1).coerceAtLeast(0)
        }
        return selected.toList()
    }

    fun isExpired(entry: Media3CacheEntry, policy: CachePolicy, nowMillis: Long): Boolean {
        if (policy.maxAgeMillis == Long.MAX_VALUE) return false
        if (entry.lastAccessAtMillis < 0) return true
        if (nowMillis < entry.lastAccessAtMillis) return false
        return nowMillis - entry.lastAccessAtMillis >= policy.maxAgeMillis
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
}
