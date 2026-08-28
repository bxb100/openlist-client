package org.openlist.mobile.data.download

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

class DownloadUniqueEnqueueGateTest {
    @Test
    fun `active KEEP owner avoids acquiring another persisted grant`() = runTest {
        var acquisitions = 0
        var enqueueAttempts = 0
        var reconciliations = 0

        val result = DownloadUniqueEnqueueGate().enqueue(
            hasActiveWork = { true },
            acquireTargetGrant = { acquisitions += 1 },
            enqueueAndConfirm = {
                enqueueAttempts += 1
                true
            },
            reconcileUnacceptedGrant = { reconciliations += 1 },
        )

        assertThat(result).isEqualTo(DownloadUniqueEnqueueResult.KEPT_EXISTING)
        assertThat(acquisitions).isEqualTo(0)
        assertThat(enqueueAttempts).isEqualTo(0)
        assertThat(reconciliations).isEqualTo(0)
    }

    @Test
    fun `confirmed request transfers its grant to WorkManager`() = runTest {
        var acquisitions = 0
        var reconciliations = 0

        val result = DownloadUniqueEnqueueGate().enqueue(
            hasActiveWork = { false },
            acquireTargetGrant = { acquisitions += 1 },
            enqueueAndConfirm = { true },
            reconcileUnacceptedGrant = { reconciliations += 1 },
        )

        assertThat(result).isEqualTo(DownloadUniqueEnqueueResult.ENQUEUED)
        assertThat(acquisitions).isEqualTo(1)
        assertThat(reconciliations).isEqualTo(0)
    }

    @Test
    fun `KEEP race reconciles grant when WorkManager rejects the new id`() = runTest {
        var reconciliations = 0

        val result = DownloadUniqueEnqueueGate().enqueue(
            hasActiveWork = { false },
            acquireTargetGrant = {},
            enqueueAndConfirm = { false },
            reconcileUnacceptedGrant = { reconciliations += 1 },
        )

        assertThat(result).isEqualTo(DownloadUniqueEnqueueResult.KEPT_EXISTING)
        assertThat(reconciliations).isEqualTo(1)
    }

    @Test
    fun `asynchronous enqueue failure reconciles the acquired grant and remains visible`() =
        runTest {
            var reconciliations = 0
            val expected = IOException("WorkManager database unavailable")

            val thrown = runCatching {
                DownloadUniqueEnqueueGate().enqueue(
                    hasActiveWork = { false },
                    acquireTargetGrant = {},
                    enqueueAndConfirm = { throw expected },
                    reconcileUnacceptedGrant = { reconciliations += 1 },
                )
            }.exceptionOrNull()

            assertThat(thrown).isInstanceOf(IOException::class.java)
            assertThat(thrown).hasMessageThat().isEqualTo(expected.message)
            assertThat(reconciliations).isEqualTo(1)
        }
}
