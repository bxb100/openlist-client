package org.openlist.mobile.ui.transfers

import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.workDataOf
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.openlist.mobile.core.model.ServerProfile
import org.openlist.mobile.data.download.DownloadFailureCode
import org.openlist.mobile.data.download.DownloadWorkKeys
import org.openlist.mobile.data.preferences.AppSettings
import org.openlist.mobile.worker.DownloadWorker
import org.openlist.mobile.worker.TransferWorkMetadata
import org.openlist.mobile.worker.UploadWorker
import java.util.UUID

class TransferCenterStateTest {
    private val settings = AppSettings(
        server = ServerProfile(baseUrl = "https://files.example.test", username = "alice"),
        token = "initial-token",
        sessionBindingKey = "login-one",
    )
    private val identity = TransferIdentity.from(settings)

    @Test
    fun `switching account or login session cannot display stale work emissions`() {
        val upload = work(WorkInfo.State.ENQUEUED)
        val download = work(WorkInfo.State.RUNNING, direction = TransferDirection.DOWNLOAD)
        val unbound = WorkInfo(UUID.randomUUID(), WorkInfo.State.FAILED, setOf(UploadWorker.WORK_TAG))
        val oldEmission = listOf(upload, download, unbound)

        assertThat(transferEntries(oldEmission, identity)).hasSize(2)
        assertThat(
            transferEntries(oldEmission, TransferIdentity.from(settings.copy(
                server = settings.server.copy(username = "bob"),
            ))),
        ).isEmpty()
        assertThat(
            transferEntries(oldEmission, TransferIdentity.from(settings.copy(sessionBindingKey = "login-two"))),
        ).isEmpty()
        assertThat(transferEntries(listOf(unbound), identity)).isEmpty()
    }

    @Test
    fun `token refresh keeps current session transfers visible`() {
        val upload = work(WorkInfo.State.ENQUEUED)
        val refreshedIdentity = TransferIdentity.from(settings.copy(token = "refreshed-token"))
        assertThat(transferEntries(listOf(upload), refreshedIdentity)).hasSize(1)
    }

    @Test
    fun `all queue items remain visible and active items precede recent results`() {
        val queued = (1..8).map { work(WorkInfo.State.ENQUEUED, createdAt = it.toLong()) }
        val blocked = work(WorkInfo.State.BLOCKED, createdAt = 9)
        val finished = work(WorkInfo.State.SUCCEEDED, createdAt = 10)
        val entries = transferEntries(listOf(finished, blocked) + queued, identity)

        assertThat(entries).hasSize(10)
        assertThat(entries.first().id).isEqualTo(blocked.id)
        assertThat(entries.last().id).isEqualTo(finished.id)
        assertThat(TransferCenterState(entries).activeCount).isEqualTo(9)
    }

    @Test
    fun `terminal work reads result bytes and keeps its name after progress is cleared`() {
        val finished = work(
            state = WorkInfo.State.SUCCEEDED,
            direction = TransferDirection.DOWNLOAD,
            output = workDataOf(
                DownloadWorkKeys.DOWNLOADED_BYTES to 1024L,
                DownloadWorkKeys.TOTAL_BYTES to 1024L,
            ),
            progress = workDataOf(DownloadWorkKeys.DOWNLOADED_BYTES to 512L),
        )
        val entry = transferEntries(listOf(finished), identity).single()

        assertThat(entry.name).isEqualTo("假期照片.jpg")
        assertThat(entry.transferredBytes).isEqualTo(1024L)
        assertThat(entry.progress).isEqualTo(1f)
    }

    @Test
    fun `waiting retry is distinct from user cancellation without claiming it can resume`() {
        val waiting = work(WorkInfo.State.ENQUEUED, attempts = 1)
        val cancelled = work(WorkInfo.State.CANCELLED, direction = TransferDirection.DOWNLOAD)
        val entries = transferEntries(listOf(waiting, cancelled), identity)

        assertThat(entries.first { it.id == waiting.id }.isRetrying).isTrue()
        val cancelledEntry = entries.first { it.id == cancelled.id }
        assertThat(cancelledEntry.status.isActive).isFalse()
        assertThat(cancelledEntry.name).isEqualTo("假期照片.jpg")
        assertThat(cancelledEntry.recoveryMessage()).contains("重新选择保存位置")
    }

    @Test
    fun `storage failure gives a safe new destination action and never renders worker raw error`() {
        val failed = work(
            state = WorkInfo.State.FAILED,
            direction = TransferDirection.DOWNLOAD,
            output = workDataOf(
                DownloadWorkKeys.FAILURE_CODE to DownloadFailureCode.TARGET_UNAVAILABLE.name,
                DownloadWorkKeys.ERROR to "content://private/document https://host.test/?token=secret",
            ),
        )
        val entry = transferEntries(listOf(failed), identity).single()

        assertThat(entry.recoveryMessage()).contains("选择可写入的位置")
        assertThat(entry.toString()).doesNotContain("content://")
        assertThat(entry.toString()).doesNotContain("secret")
    }

    @Test
    fun `unknown totals stay indeterminate and invalid reported progress cannot overflow`() {
        val unknown = work(
            WorkInfo.State.RUNNING,
            progress = workDataOf(UploadWorker.KEY_UPLOADED_BYTES to 10L),
        )
        val overreported = work(
            WorkInfo.State.RUNNING,
            progress = workDataOf(
                UploadWorker.KEY_UPLOADED_BYTES to Long.MAX_VALUE,
                UploadWorker.KEY_TOTAL_BYTES to 100L,
            ),
        )
        val empty = work(
            WorkInfo.State.SUCCEEDED,
            output = workDataOf(UploadWorker.KEY_UPLOADED_BYTES to 0L),
        )
        val entries = transferEntries(listOf(unknown, overreported, empty), identity)

        assertThat(entries.first { it.id == unknown.id }.progress).isNull()
        assertThat(entries.first { it.id == overreported.id }.progress).isEqualTo(1f)
        assertThat(entries.first { it.id == empty.id }.status).isEqualTo(TransferStatus.SUCCEEDED)
        assertThat(entries.first { it.id == empty.id }.transferredBytes).isEqualTo(0L)
    }

    @Test
    fun `persistent display metadata excludes directory and misleading control characters`() {
        val tags = TransferWorkMetadata.tags(
            binding = "a".repeat(64),
            remotePath = "/private/folder/photo\n\u202egpj.jpg",
            createdAtMillis = 1L,
        )

        assertThat(TransferWorkMetadata.name(tags)).isEqualTo("photogpj.jpg")
        assertThat(tags.joinToString()).doesNotContain("/private/folder")
    }

    private fun work(
        state: WorkInfo.State,
        direction: TransferDirection = TransferDirection.UPLOAD,
        progress: Data = Data.EMPTY,
        output: Data = Data.EMPTY,
        attempts: Int = 0,
        createdAt: Long = 1,
    ): WorkInfo {
        val sessionTag = if (direction == TransferDirection.UPLOAD) identity.uploadTag else identity.downloadTag
        val kindTag = if (direction == TransferDirection.UPLOAD) UploadWorker.WORK_TAG else DownloadWorker.WORK_TAG
        val tags = TransferWorkMetadata.tags(
            binding = sessionTag.substringAfterLast(':'),
            remotePath = "/相册/假期照片.jpg",
            createdAtMillis = createdAt,
        ) + kindTag
        return WorkInfo(
            id = UUID.randomUUID(),
            state = state,
            tags = tags,
            progress = progress,
            outputData = output,
            runAttemptCount = attempts,
        )
    }
}
