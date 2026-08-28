@file:androidx.annotation.OptIn(
    markerClass = [androidx.media3.common.util.UnstableApi::class],
)

package org.openlist.mobile.data.cache

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.openlist.mobile.core.model.CachePolicy

@RunWith(AndroidJUnit4::class)
class Media3CacheControllerInitializationTest {
    @Test
    fun populatedCacheReopensWithoutLockInversion() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val directory = File(context.cacheDir, "media-cache-reopen-test")
        directory.deleteRecursively()
        check(directory.mkdirs())
        val key = "openlist-content-v2:" + "a".repeat(64)
        val payload = byteArrayOf(1, 2, 3, 4)
        val policy = CachePolicy(maxBytes = 1_024, maxAgeMillis = Long.MAX_VALUE, maxEntries = 10)

        Media3CacheController(directory, policy).use { controller ->
            val hole = controller.cache.startReadWrite(key, 0, payload.size.toLong())
            try {
                val cacheFile = controller.cache.startFile(key, 0, payload.size.toLong())
                cacheFile.outputStream().use { it.write(payload) }
                controller.cache.commitFile(cacheFile, payload.size.toLong())
            } finally {
                controller.cache.releaseHoleSpan(hole)
            }
        }

        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "media-cache-reopen-test").apply { isDaemon = true }
        }
        try {
            val stats = executor.submit<CacheStats> {
                Media3CacheController(directory, policy).use { controller -> controller.stats() }
            }.get(5, TimeUnit.SECONDS)

            assertEquals(1, stats.entryCount)
            assertEquals(payload.size.toLong(), stats.totalBytes)
        } finally {
            executor.shutdownNow()
            directory.deleteRecursively()
        }
    }
}
