package org.openlist.mobile.data.upload

import androidx.work.ExistingWorkPolicy
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.openlist.mobile.core.model.ServerProfile

class UploadTargetWorkTest {
    @Test
    fun `equivalent remote paths share one stable work name`() {
        val binding = binding("https://openlist.example/root/", "alice", "token")

        val canonical = UploadTargetWork.uniqueName(binding, false, "/media/video.mkv")
        val redundant = UploadTargetWork.uniqueName(
            binding,
            false,
            "//media///draft/.././video.mkv/",
        )

        assertThat(redundant).isEqualTo(canonical)
    }

    @Test
    fun `server account session security and destination isolate work names`() {
        val first = binding("https://one.example", "alice", "token-one")
        val otherServer = binding("https://two.example", "alice", "token-one")
        val otherAccount = binding("https://one.example", "bob", "token-one")
        val otherSession = binding("https://one.example", "alice", "token-two")
        val baseline = UploadTargetWork.uniqueName(first, false, "/target.bin")

        assertThat(UploadTargetWork.uniqueName(otherServer, false, "/target.bin"))
            .isNotEqualTo(baseline)
        assertThat(UploadTargetWork.uniqueName(otherAccount, false, "/target.bin"))
            .isNotEqualTo(baseline)
        assertThat(UploadTargetWork.uniqueName(otherSession, false, "/target.bin"))
            .isNotEqualTo(baseline)
        assertThat(UploadTargetWork.uniqueName(first, true, "/target.bin"))
            .isNotEqualTo(baseline)
        assertThat(UploadTargetWork.uniqueName(first, false, "/other.bin"))
            .isNotEqualTo(baseline)
        assertThat(UploadTargetWork.uniqueName(first, false, "/target\\bin"))
            .isNotEqualTo(baseline)
    }

    @Test
    fun `work name discloses neither credentials identity nor destination`() {
        val server = "https://private.example/secret-root"
        val username = "private-user"
        val token = "private-token"
        val remotePath = "/private/report.pdf"
        val binding = binding(server, username, token)

        val name = UploadTargetWork.uniqueName(binding, false, remotePath)

        assertThat(name).matches("openlist-upload-[0-9a-f]{64}")
        assertThat(name).doesNotContain(server)
        assertThat(name).doesNotContain(username)
        assertThat(name).doesNotContain(token)
        assertThat(name).doesNotContain(remotePath)
        assertThat(name).doesNotContain(binding.value)
    }

    @Test
    fun `unfinished duplicate contract keeps the first work`() {
        assertThat(UploadTargetWork.existingWorkPolicy).isEqualTo(ExistingWorkPolicy.KEEP)
    }

    @Test
    fun `path cannot escape root or name the root directory`() {
        assertThat(runCatching { UploadTargetWork.canonicalRemotePath("../../file") }.isFailure)
            .isTrue()
        assertThat(runCatching { UploadTargetWork.canonicalRemotePath("/") }.isFailure)
            .isTrue()
    }

    @Test
    fun `active unique work is rejected before a source grant is acquired`() = runTest {
        var acquisitions = 0
        var enqueueAttempts = 0
        var releases = 0

        val result = UploadUniqueEnqueueGate().enqueue(
            hasActiveWork = { true },
            acquireSourceGrant = { acquisitions += 1 },
            enqueueAndConfirm = {
                enqueueAttempts += 1
                true
            },
            releaseSourceGrant = { releases += 1 },
        )

        assertThat(result).isEqualTo(UploadUniqueEnqueueResult.KEPT_EXISTING)
        assertThat(acquisitions).isEqualTo(0)
        assertThat(enqueueAttempts).isEqualTo(0)
        assertThat(releases).isEqualTo(0)
    }

    @Test
    fun `grant is released when KEEP does not insert the new request`() = runTest {
        var acquisitions = 0
        var releases = 0

        val result = UploadUniqueEnqueueGate().enqueue(
            hasActiveWork = { false },
            acquireSourceGrant = { acquisitions += 1 },
            enqueueAndConfirm = { false },
            releaseSourceGrant = { releases += 1 },
        )

        assertThat(result).isEqualTo(UploadUniqueEnqueueResult.KEPT_EXISTING)
        assertThat(acquisitions).isEqualTo(1)
        assertThat(releases).isEqualTo(1)
    }

    @Test
    fun `accepted request exclusively receives grant ownership`() = runTest {
        var releases = 0

        val result = UploadUniqueEnqueueGate().enqueue(
            hasActiveWork = { false },
            acquireSourceGrant = {},
            enqueueAndConfirm = { true },
            releaseSourceGrant = { releases += 1 },
        )

        assertThat(result).isEqualTo(UploadUniqueEnqueueResult.ENQUEUED)
        assertThat(releases).isEqualTo(0)
    }

    @Test
    fun `caller cancellation cannot strand an acquired grant`() = runTest {
        val enqueueStarted = CompletableDeferred<Unit>()
        val finishEnqueue = CompletableDeferred<Unit>()
        var releases = 0
        val job = launch {
            UploadUniqueEnqueueGate().enqueue(
                hasActiveWork = { false },
                acquireSourceGrant = {},
                enqueueAndConfirm = {
                    enqueueStarted.complete(Unit)
                    finishEnqueue.await()
                    false
                },
                releaseSourceGrant = { releases += 1 },
            )
        }

        enqueueStarted.await()
        job.cancel()
        finishEnqueue.complete(Unit)
        job.join()

        assertThat(releases).isEqualTo(1)
    }

    private fun binding(server: String, username: String, token: String) =
        UploadSessionBinding.create(ServerProfile(server, username), token)
}
