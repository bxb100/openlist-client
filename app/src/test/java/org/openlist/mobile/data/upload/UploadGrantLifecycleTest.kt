package org.openlist.mobile.data.upload

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test

class UploadGrantLifecycleTest {
    @Test
    fun `same URI remains granted until every upload has staged or terminated`() {
        val store = InMemoryLeaseStore()
        val releases = mutableListOf<String>()
        val ledger = UploadGrantLedger(store, releases::add)
        ledger.track(lease(WORK_ID))
        ledger.track(lease(OTHER_WORK_ID))

        ledger.releaseSourceGrant(WORK_ID)

        assertThat(releases).isEmpty()
        assertThat(store.entry(WORK_ID)?.sourceGrantRequired).isFalse()
        assertThat(store.entry(OTHER_WORK_ID)?.sourceGrantRequired).isTrue()
        assertThat(ledger.removeTerminal(WORK_ID)).isTrue()
        assertThat(store.entries()).containsExactly(lease(OTHER_WORK_ID))

        ledger.releaseSourceGrant(OTHER_WORK_ID)

        assertThat(releases).containsExactly(SOURCE_URI)
        assertThat(store.entry(OTHER_WORK_ID)?.sourceGrantRequired).isFalse()
    }

    @Test
    fun `checkpoint and grant state remain durable until terminal record removal`() {
        val store = InMemoryLeaseStore()
        val ledger = UploadGrantLedger(store) {}
        ledger.track(lease(WORK_ID))

        ledger.registerCheckpoint(WORK_ID, CHECKPOINT_KEY)
        ledger.releaseSourceGrant(WORK_ID)

        assertThat(store.entry(WORK_ID)).isEqualTo(
            lease(WORK_ID).copy(
                sourceGrantRequired = false,
                checkpointKey = CHECKPOINT_KEY,
            ),
        )
        assertThat(ledger.removeTerminal(WORK_ID)).isTrue()
        assertThat(store.entries()).isEmpty()
    }

    @Test
    fun `provider cleanup failure retains ownership for reconciliation retry`() {
        val store = InMemoryLeaseStore()
        var attempts = 0
        val ledger = UploadGrantLedger(store) {
            attempts += 1
            if (attempts == 1) throw IllegalStateException("provider unavailable")
        }
        ledger.track(lease(WORK_ID))

        ledger.releaseSourceGrant(WORK_ID)
        assertThat(store.entry(WORK_ID)?.sourceGrantRequired).isTrue()

        ledger.releaseSourceGrant(WORK_ID)
        assertThat(attempts).isEqualTo(2)
        assertThat(store.entry(WORK_ID)?.sourceGrantRequired).isFalse()
    }

    @Test
    fun `legacy callback cannot revoke a grant required by a tracked upload`() {
        val store = InMemoryLeaseStore()
        val releases = mutableListOf<String>()
        val ledger = UploadGrantLedger(store, releases::add)
        ledger.track(lease(OTHER_WORK_ID))

        ledger.releaseSourceGrant(WORK_ID, legacySourceUri = SOURCE_URI)

        assertThat(releases).isEmpty()
        assertThat(store.entries()).containsExactly(lease(OTHER_WORK_ID))
    }

    @Test
    fun `failed platform acquisition discards only its durable claim`() {
        val store = InMemoryLeaseStore()
        val ledger = UploadGrantLedger(store) {}
        ledger.track(lease(WORK_ID))
        ledger.track(lease(OTHER_WORK_ID))

        ledger.discard(WORK_ID)

        assertThat(store.entries()).containsExactly(lease(OTHER_WORK_ID))
    }

    @Test
    fun `tracked active work with a different URI does not retain a rejected grant`() {
        val store = InMemoryLeaseStore()
        val ledger = UploadGrantLedger(store) {}
        ledger.track(lease(OTHER_WORK_ID, sourceUri = OTHER_SOURCE_URI))

        assertThat(
            ledger.hasPotentialSourceOwner(setOf(OTHER_WORK_ID), SOURCE_URI),
        ).isFalse()
        assertThat(
            ledger.hasPotentialSourceOwner(setOf(OTHER_WORK_ID), OTHER_SOURCE_URI),
        ).isTrue()
    }

    @Test
    fun `untracked active work is conservatively treated as a legacy shared owner`() {
        val ledger = UploadGrantLedger(InMemoryLeaseStore()) {}

        assertThat(ledger.hasPotentialSourceOwner(setOf(OTHER_WORK_ID), SOURCE_URI)).isTrue()
    }

    @Test
    fun `terminal cleanup removes staging and registered checkpoint`() = runTest {
        val removals = mutableListOf<String>()
        val cleanup = UploadTerminalLocalCleanup(
            removeStaging = { removals += "stage:$it" },
            removeCheckpoint = { removals += "checkpoint:$it" },
        )

        val cleaned = cleanup.cleanup(WORK_ID, CHECKPOINT_KEY)

        assertThat(cleaned).isTrue()
        assertThat(removals).containsExactly(
            "stage:$WORK_ID",
            "checkpoint:$CHECKPOINT_KEY",
        ).inOrder()
    }

    @Test
    fun `failed local cleanup remains unresolved for a later terminal retry`() = runTest {
        var attempts = 0
        val cleanup = UploadTerminalLocalCleanup(
            removeStaging = {
                attempts += 1
                throw IllegalStateException("disk busy")
            },
            removeCheckpoint = {},
        )

        assertThat(cleanup.cleanup(WORK_ID, CHECKPOINT_KEY)).isFalse()
        assertThat(attempts).isEqualTo(1)
    }

    @Test
    fun `observed terminal cleanup waits until the worker has exited`() = runTest {
        val activeWorkers = mutableSetOf(WORK_ID)
        val waitStarted = CompletableDeferred<Unit>()
        val allowRecheck = CompletableDeferred<Unit>()
        var cleaned = false

        val cleanup = async {
            runAfterUploadWorkerExit(
                workId = WORK_ID,
                activeWorkers = activeWorkers,
                waitForStateChange = {
                    waitStarted.complete(Unit)
                    allowRecheck.await()
                },
                action = { cleaned = true },
            )
        }

        waitStarted.await()
        assertThat(cleaned).isFalse()
        activeWorkers.remove(WORK_ID)
        allowRecheck.complete(Unit)
        cleanup.await()

        assertThat(cleaned).isTrue()
    }

    private fun lease(workId: String, sourceUri: String = SOURCE_URI) = UploadGrantLease(
        workId = workId,
        sourceUri = sourceUri,
        uniqueWorkName = UNIQUE_WORK_NAME,
    )

    private class InMemoryLeaseStore : UploadGrantLeaseStore {
        private val leases = linkedMapOf<String, UploadGrantLease>()

        override fun entries(): List<UploadGrantLease> = leases.values.toList()

        override fun put(lease: UploadGrantLease) {
            leases[lease.workId] = lease
        }

        override fun remove(workId: String) {
            leases.remove(workId)
        }

        fun entry(workId: String): UploadGrantLease? = leases[workId]
    }

    private companion object {
        const val WORK_ID = "00000000-0000-0000-0000-000000000001"
        const val OTHER_WORK_ID = "00000000-0000-0000-0000-000000000002"
        const val SOURCE_URI = "content://documents/source/42"
        const val OTHER_SOURCE_URI = "content://documents/source/84"
        const val UNIQUE_WORK_NAME = "openlist-upload-target"
        const val CHECKPOINT_KEY = "checkpoint-key"
    }
}
