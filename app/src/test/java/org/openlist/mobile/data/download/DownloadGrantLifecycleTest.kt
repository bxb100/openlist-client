package org.openlist.mobile.data.download

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DownloadGrantLifecycleTest {
    @Test
    fun `success permanent failure and cancellation release their persisted grant`() {
        DownloadWorkDisposition.entries
            .filter { it != DownloadWorkDisposition.RETRY }
            .forEach { disposition ->
                val store = InMemoryLeaseStore()
                val releases = mutableListOf<String>()
                val ledger = DownloadGrantLedger(store, releases::add)
                ledger.track(lease(WORK_ID, TARGET_URI))

                ledger.onDisposition(WORK_ID, disposition)

                assertThat(releases).containsExactly(TARGET_URI)
                assertThat(store.entries()).isEmpty()
            }
    }

    @Test
    fun `retry retains both the grant and its durable ownership record`() {
        val store = InMemoryLeaseStore()
        val releases = mutableListOf<String>()
        val ledger = DownloadGrantLedger(store, releases::add)
        ledger.track(lease(WORK_ID, TARGET_URI))

        ledger.onDisposition(WORK_ID, DownloadWorkDisposition.RETRY)

        assertThat(releases).isEmpty()
        assertThat(store.entries()).containsExactly(lease(WORK_ID, TARGET_URI))
    }

    @Test
    fun `cancel cleanup is armed only after the target stream actually opened`() {
        val store = InMemoryLeaseStore()
        val ledger = DownloadGrantLedger(store) { _ -> }
        ledger.track(lease(WORK_ID, TARGET_URI))

        assertThat(ledger.targetWasOpened(WORK_ID)).isFalse()

        ledger.markTargetOpened(WORK_ID)

        assertThat(ledger.targetWasOpened(WORK_ID)).isTrue()
        assertThat(store.entries()).containsExactly(
            DownloadGrantLease(WORK_ID, TARGET_URI, targetOpened = true),
        )
    }

    @Test
    fun `enqueued cancellation releases without truncating an unopened target`() {
        assertThat(cancelledTargetAction(workerActive = false, targetOpened = false)).isEqualTo(
            DownloadCancelledTargetAction.RELEASE_ONLY,
        )
    }

    @Test
    fun `running cancellation waits for partial-file cleanup before grant release`() {
        assertThat(cancelledTargetAction(workerActive = true, targetOpened = true)).isEqualTo(
            DownloadCancelledTargetAction.WAIT_FOR_WORKER,
        )
        assertThat(cancelledTargetAction(workerActive = false, targetOpened = true)).isEqualTo(
            DownloadCancelledTargetAction.CLEAR_THEN_RELEASE,
        )
    }

    @Test
    fun `duplicate KEEP lease cannot revoke the accepted work grant`() {
        val store = InMemoryLeaseStore()
        val releases = mutableListOf<String>()
        val ledger = DownloadGrantLedger(store, releases::add)
        ledger.track(lease(WORK_ID, TARGET_URI))
        ledger.track(lease(DUPLICATE_WORK_ID, TARGET_URI))

        ledger.onDisposition(WORK_ID, DownloadWorkDisposition.SUCCESS)

        assertThat(releases).isEmpty()
        assertThat(store.entries()).containsExactly(lease(DUPLICATE_WORK_ID, TARGET_URI))

        ledger.releaseTerminal(DUPLICATE_WORK_ID)
        assertThat(releases).containsExactly(TARGET_URI)
        assertThat(store.entries()).isEmpty()
    }

    @Test
    fun `terminal work defers release while another tracked request shares the URI`() {
        val store = InMemoryLeaseStore()
        val releases = mutableListOf<String>()
        val ledger = DownloadGrantLedger(store, releases::add)
        ledger.track(lease(WORK_ID, TARGET_URI))
        ledger.track(lease(DUPLICATE_WORK_ID, TARGET_URI))

        ledger.onDisposition(WORK_ID, DownloadWorkDisposition.SUCCESS)

        assertThat(releases).isEmpty()
        assertThat(store.entries()).containsExactly(lease(DUPLICATE_WORK_ID, TARGET_URI))

        ledger.releaseTerminal(DUPLICATE_WORK_ID)
        assertThat(releases).containsExactly(TARGET_URI)
        assertThat(store.entries()).isEmpty()
    }

    @Test
    fun `legacy terminal callback does not revoke a newer tracked request grant`() {
        val store = InMemoryLeaseStore()
        val releases = mutableListOf<String>()
        val ledger = DownloadGrantLedger(store, releases::add)
        ledger.track(lease(DUPLICATE_WORK_ID, TARGET_URI))

        ledger.releaseTerminal(WORK_ID, legacyTargetUri = TARGET_URI)

        assertThat(releases).isEmpty()
        assertThat(store.entries()).containsExactly(lease(DUPLICATE_WORK_ID, TARGET_URI))
    }

    @Test
    fun `transient provider cleanup error keeps lease for cold start retry`() {
        val store = InMemoryLeaseStore()
        var attempts = 0
        val ledger = DownloadGrantLedger(store) {
            attempts += 1
            if (attempts == 1) throw IllegalStateException("provider unavailable")
        }
        ledger.track(lease(WORK_ID, TARGET_URI))

        ledger.onDisposition(WORK_ID, DownloadWorkDisposition.SUCCESS)

        assertThat(attempts).isEqualTo(1)
        assertThat(store.entries()).containsExactly(lease(WORK_ID, TARGET_URI))

        ledger.releaseTerminal(WORK_ID)
        assertThat(attempts).isEqualTo(2)
        assertThat(store.entries()).isEmpty()
    }

    @Test
    fun `already absent provider grant resolves the durable lease`() {
        val store = InMemoryLeaseStore()
        var attempts = 0
        val ledger = DownloadGrantLedger(store) {
            attempts += 1
            throw SecurityException("grant already released")
        }
        ledger.track(lease(WORK_ID, TARGET_URI))

        ledger.releaseTerminal(WORK_ID)

        assertThat(attempts).isEqualTo(1)
        assertThat(store.entries()).isEmpty()
    }

    @Test
    fun `metadata removal failure cannot turn a completed download into work failure`() {
        val lease = lease(WORK_ID, TARGET_URI)
        val store = object : DownloadGrantLeaseStore {
            override fun entries(): List<DownloadGrantLease> = listOf(lease)
            override fun put(lease: DownloadGrantLease) = Unit
            override fun remove(workId: String) = error("preferences unavailable")
        }
        val releases = mutableListOf<String>()
        val ledger = DownloadGrantLedger(store, releases::add)

        ledger.onDisposition(WORK_ID, DownloadWorkDisposition.SUCCESS)

        assertThat(releases).containsExactly(TARGET_URI)
    }

    private fun lease(workId: String, uri: String) = DownloadGrantLease(workId, uri)

    private class InMemoryLeaseStore : DownloadGrantLeaseStore {
        private val leases = linkedMapOf<String, DownloadGrantLease>()

        override fun entries(): List<DownloadGrantLease> = leases.values.toList()

        override fun put(lease: DownloadGrantLease) {
            leases[lease.workId] = lease
        }

        override fun remove(workId: String) {
            leases.remove(workId)
        }
    }

    private companion object {
        const val WORK_ID = "00000000-0000-0000-0000-000000000001"
        const val DUPLICATE_WORK_ID = "00000000-0000-0000-0000-000000000002"
        const val TARGET_URI = "content://documents/download/42"
    }
}
