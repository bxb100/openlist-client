package org.openlist.mobile.data.logging

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PersistentAppLoggerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun persistsRedactedEntriesAndContinuesIdsAfterRestart() {
        val directory = temporaryFolder.newFolder("persistent")
        val clock = FakeClock(100)
        val logger = PersistentAppLogger(directory, clock = clock)

        val first = logger.info("network", "Authorization: super-secret")
        clock.now = 200
        logger.warn("download", "raw_url=https://cdn.test/file?sign=also-secret")

        assertThat(first.id).isEqualTo(1)
        assertThat(logger.snapshot()).hasSize(2)
        assertThat(logger.snapshot().joinToString { it.message }).doesNotContain("super-secret")
        assertThat(File(directory, "application.log").readText()).doesNotContain("also-secret")

        val reopened = PersistentAppLogger(directory, clock = clock)
        val third = reopened.error("api", "failed")

        assertThat(reopened.snapshot()).hasSize(3)
        assertThat(third.id).isEqualTo(3)
        assertThat(reopened.persistenceHealthy.value).isTrue()
    }

    @Test
    fun entryLimitKeepsNewestEntries() {
        val logger = PersistentAppLogger(
            directory = temporaryFolder.newFolder("entry-limit"),
            retention = LogRetention(maxEntries = 3, maxFileBytes = 8_192),
            clock = FakeClock(1),
        )

        repeat(5) { logger.info("test", "message-$it") }

        assertThat(logger.snapshot().map(LogEntry::message))
            .containsExactly("message-2", "message-3", "message-4").inOrder()
    }

    @Test
    fun fileLimitCompactsOldEntriesAndNeverExceedsBound() {
        val directory = temporaryFolder.newFolder("byte-limit")
        val retention = LogRetention(
            maxEntries = 100,
            maxFileBytes = 420,
            maxMessageChars = 200,
        )
        val logger = PersistentAppLogger(directory, retention, FakeClock(1))

        repeat(12) { logger.info("large", "message-$it-${"x".repeat(90)}") }

        val file = File(directory, "application.log")
        assertThat(file.length()).isAtMost(retention.maxFileBytes)
        assertThat(logger.snapshot().size).isLessThan(12)
        assertThat(logger.snapshot().last().message).contains("message-11")
    }

    @Test
    fun concurrentWritersKeepUniqueIdsAndValidPersistence() {
        val directory = temporaryFolder.newFolder("concurrent")
        val logger = PersistentAppLogger(
            directory,
            LogRetention(maxEntries = 200, maxFileBytes = 64 * 1_024),
            FakeClock(1_000),
        )
        val executor = Executors.newFixedThreadPool(4)

        val futures = (0 until 100).map { index ->
            executor.submit { logger.debug("worker-${index % 4}", "message-$index token=secret-$index") }
        }
        futures.forEach { it.get(20, TimeUnit.SECONDS) }
        executor.shutdown()

        val snapshot = logger.snapshot()
        assertThat(snapshot).hasSize(100)
        assertThat(snapshot.map(LogEntry::id).toSet()).hasSize(100)
        assertThat(snapshot.joinToString { it.message }).doesNotContain("secret-")
        assertThat(PersistentAppLogger(directory).snapshot()).hasSize(100)
    }

    @Test
    fun clearRemovesEntriesFromMemoryAndRestart() {
        val directory = temporaryFolder.newFolder("clear")
        val logger = PersistentAppLogger(directory)
        logger.info("app", "one")
        logger.info("app", "two")

        logger.clear()

        assertThat(logger.snapshot()).isEmpty()
        assertThat(File(directory, "application.log").length()).isEqualTo(0)
        assertThat(PersistentAppLogger(directory).snapshot()).isEmpty()
    }

    @Test
    fun startupDropsMalformedTailAndRewritesValidPrefix() {
        val directory = temporaryFolder.newFolder("malformed")
        PersistentAppLogger(directory).info("app", "valid")
        File(directory, "application.log").appendText("{partial")

        val reopened = PersistentAppLogger(directory)

        assertThat(reopened.snapshot().map(LogEntry::message)).containsExactly("valid")
        assertThat(File(directory, "application.log").readText()).doesNotContain("partial")
    }

    @Test
    fun startupResanitizesLegacyFileBeforeExposingOrRewriting() {
        val directory = temporaryFolder.newFolder("legacy-secret")
        File(directory, "application.log").writeText(
            """{"version":1,"id":7,"timestampMillis":10,"level":"INFO","module":"api","message":"password=legacy-secret"}
            """.trimIndent(),
        )

        val logger = PersistentAppLogger(directory)

        assertThat(logger.snapshot().single().message).doesNotContain("legacy-secret")
        assertThat(File(directory, "application.log").readText()).doesNotContain("legacy-secret")
    }

    @Test
    fun diskFailureFallsBackToBoundedInMemoryLogging() {
        val notDirectory = temporaryFolder.newFile("not-a-directory")
        val logger = PersistentAppLogger(notDirectory)

        logger.error("startup", "password=must-hide")

        assertThat(logger.snapshot()).hasSize(1)
        assertThat(logger.snapshot().single().message).doesNotContain("must-hide")
        assertThat(logger.persistenceHealthy.value).isFalse()
    }

    private class FakeClock(var now: Long) : LogClock {
        override fun nowMillis(): Long = now
    }
}
