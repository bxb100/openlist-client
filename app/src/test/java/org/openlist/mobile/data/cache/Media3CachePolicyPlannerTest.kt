package org.openlist.mobile.data.cache

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.openlist.mobile.core.model.CachePolicy

class Media3CachePolicyPlannerTest {
    @Test
    fun onlyAppOwnedContentKeysAreEligibleForMedia3DiskCache() {
        val valid = "openlist-content-v2:" + "a".repeat(64)

        assertThat(Media3StableCacheKey.from(valid)).isEqualTo(valid)
        assertThat(Media3StableCacheKey.from(null)).isNull()
        assertThat(Media3StableCacheKey.from("https://storage.test/file?signature=secret")).isNull()
        assertThat(Media3StableCacheKey.from("openlist-content-v2:segment")).isNull()
        assertThat(Media3StableCacheKey.from("openlist-content-v2:" + "A".repeat(64))).isNull()
    }

    @Test
    fun aggregateCountsManySpansOfOneKeyAsOneEntry() {
        val entries = Media3CachePolicyPlanner.aggregate(
            listOf(
                Media3SpanSnapshot("audio", length = 10, lastTouchTimestamp = 100),
                Media3SpanSnapshot("audio", length = 20, lastTouchTimestamp = 200),
                Media3SpanSnapshot("image", length = 4, lastTouchTimestamp = 150),
                Media3SpanSnapshot("empty", length = 0, lastTouchTimestamp = 300),
            ),
        )

        assertThat(entries).containsExactly(
            Media3CacheEntry("audio", sizeBytes = 30, lastAccessAtMillis = 200),
            Media3CacheEntry("image", sizeBytes = 4, lastAccessAtMillis = 150),
        )
    }

    @Test
    fun ttlIsAnIndependentOrConstraintAtExactBoundary() {
        val entries = listOf(
            Media3CacheEntry("expired", sizeBytes = 1, lastAccessAtMillis = 100),
            Media3CacheEntry("fresh", sizeBytes = 1, lastAccessAtMillis = 101),
        )
        val policy = CachePolicy(maxBytes = 100, maxAgeMillis = 100, maxEntries = 10)

        assertThat(Media3CachePolicyPlanner.keysToRemove(entries, policy, nowMillis = 200))
            .containsExactly("expired")
    }

    @Test
    fun lruContinuesUntilBytesAndCountAreBothWithinLimits() {
        val entries = listOf(
            Media3CacheEntry("a", sizeBytes = 4, lastAccessAtMillis = 100),
            Media3CacheEntry("b", sizeBytes = 4, lastAccessAtMillis = 200),
            Media3CacheEntry("c", sizeBytes = 4, lastAccessAtMillis = 300),
        )
        val policy = CachePolicy(maxBytes = 5, maxAgeMillis = 10_000, maxEntries = 2)

        assertThat(Media3CachePolicyPlanner.keysToRemove(entries, policy, nowMillis = 400))
            .containsExactly("a", "b").inOrder()
    }

    @Test
    fun pendingActiveKeyIsIncludedInProjectedRemovalWithoutOverEvicting() {
        val entries = listOf(
            Media3CacheEntry("active-old", sizeBytes = 4, lastAccessAtMillis = 100),
            Media3CacheEntry("b", sizeBytes = 4, lastAccessAtMillis = 200),
            Media3CacheEntry("c", sizeBytes = 4, lastAccessAtMillis = 300),
        )
        val policy = CachePolicy(maxBytes = 100, maxAgeMillis = 10_000, maxEntries = 2)

        assertThat(
            Media3CachePolicyPlanner.keysToRemove(
                entries,
                policy,
                nowMillis = 400,
                alreadyPending = setOf("active-old"),
            ),
        ).containsExactly("active-old")
    }

    @Test
    fun everyZeroLimitSelectsAllEntries() {
        val entries = listOf(
            Media3CacheEntry("a", sizeBytes = 1, lastAccessAtMillis = 100),
            Media3CacheEntry("b", sizeBytes = 1, lastAccessAtMillis = 200),
        )
        val policies = listOf(
            CachePolicy(maxBytes = 0, maxAgeMillis = 1_000, maxEntries = 2),
            CachePolicy(maxBytes = 100, maxAgeMillis = 0, maxEntries = 2),
            CachePolicy(maxBytes = 100, maxAgeMillis = 1_000, maxEntries = 0),
        )

        policies.forEach { policy ->
            assertThat(Media3CachePolicyPlanner.keysToRemove(entries, policy, nowMillis = 300))
                .containsExactly("a", "b")
        }
    }
}
