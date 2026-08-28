package org.openlist.mobile.data.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LogoutCleanupTest {
    @Test
    fun `remote timeout is bounded and still clears local credentials`() = runTest {
        var cleaned = false

        performBestEffortLogout(
            remoteTimeoutMillis = 1_000,
            remoteLogout = { awaitCancellation() },
            localCleanup = { cleaned = true },
        )

        assertThat(cleaned).isTrue()
        assertThat(testScheduler.currentTime).isEqualTo(1_000)
    }

    @Test
    fun `remote failure does not prevent or fail local cleanup`() = runTest {
        var cleaned = false

        performBestEffortLogout(
            remoteLogout = { error("server unavailable") },
            localCleanup = { cleaned = true },
        )

        assertThat(cleaned).isTrue()
    }

    @Test
    fun `external cancellation propagates only after non cancellable cleanup`() = runTest {
        val remoteStarted = CompletableDeferred<Unit>()
        val cleanupFinished = CompletableDeferred<Unit>()
        val logout = launch {
            performBestEffortLogout(
                remoteLogout = {
                    remoteStarted.complete(Unit)
                    awaitCancellation()
                },
                localCleanup = {
                    yield()
                    cleanupFinished.complete(Unit)
                },
            )
        }
        remoteStarted.await()

        logout.cancel()
        runCurrent()

        assertThat(logout.isCancelled).isTrue()
        assertThat(cleanupFinished.isCompleted).isTrue()
    }
}
