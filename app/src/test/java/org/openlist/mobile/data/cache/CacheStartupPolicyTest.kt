package org.openlist.mobile.data.cache

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.openlist.mobile.core.model.CachePolicy

class CacheStartupPolicyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun pendingDataStoreReadCannotExpireContentUsingModelDefaults() {
        val directory = temporaryFolder.newFolder("persisted-cache")
        val key = CacheKey.namespaced("startup", "old-image", revision = "v1")
        val persistedPolicy = CachePolicy(
            maxBytes = 100,
            maxAgeMillis = Long.MAX_VALUE,
            maxEntries = 10,
        )
        val writtenAt = 1_000L
        ManagedDiskCache(directory, persistedPolicy, FixedClock(writtenAt)).use { cache ->
            assertThat(cache.put(key, expectedBytes = 4) { it.write(byteArrayOf(1, 2, 3, 4)) })
                .isTrue()
        }

        val afterModelDefaultTtl = writtenAt + CachePolicy().maxAgeMillis + 1L
        val reopened = ManagedDiskCache(
            directory = directory,
            initialPolicy = CacheStartupPolicy.initial(persistedPolicy = null),
            clock = FixedClock(afterModelDefaultTtl),
        )

        // The real persisted policy arrives only after backend startup.
        reopened.updatePolicy(persistedPolicy)

        reopened.acquire(key).use { lease -> assertThat(lease).isNotNull() }
        reopened.close()
    }

    private class FixedClock(private val value: Long) : CacheClock {
        override fun nowMillis(): Long = value
    }
}
