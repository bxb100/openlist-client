package org.openlist.mobile.data.upload

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class UploadWorkCleanupTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `temporary failure keeps staging and checkpoint while staged source grant is released`() =
        runTest {
            val stagingStore = UploadStagingStore(temporaryFolder.newFolder("retry-staging"))
            val checkpointStore = JsonUploadCheckpointStore(
                temporaryFolder.newFolder("retry-checkpoint"),
            )
            val staged = stagingStore.stage(STAGING_KEY, 4) { "data".byteInputStream() }
            val checkpoint = checkpoint()
            checkpointStore.save(checkpoint)
            var grantReleases = 0
            val sourceGrant = UploadSourceGrant { grantReleases += 1 }
            val disposition = UploadWorkPolicy.classifyFailure(IOException("offline"))

            sourceGrant.onStagingSucceeded()
            sourceGrant.onWorkDisposition(disposition)
            UploadWorkCleanup(stagingStore, checkpointStore).apply(
                disposition,
                STAGING_KEY,
                CHECKPOINT_KEY,
            )

            assertThat(disposition).isEqualTo(UploadWorkDisposition.RETRY)
            assertThat(grantReleases).isEqualTo(1)
            assertThat(stagingStore.restore(STAGING_KEY, 4)).isEqualTo(staged)
            assertThat(checkpointStore.load(CHECKPOINT_KEY)).isEqualTo(checkpoint)
        }

    @Test
    fun `permanent failure removes staging and checkpoint`() = runTest {
        val stagingStore = UploadStagingStore(temporaryFolder.newFolder("failed-staging"))
        val checkpointStore = JsonUploadCheckpointStore(
            temporaryFolder.newFolder("failed-checkpoint"),
        )
        val staged = stagingStore.stage(STAGING_KEY, 4) { "data".byteInputStream() }
        checkpointStore.save(checkpoint())

        UploadWorkCleanup(stagingStore, checkpointStore).apply(
            UploadWorkDisposition.PERMANENT_FAILURE,
            STAGING_KEY,
            CHECKPOINT_KEY,
        )

        assertThat(staged.file.exists()).isFalse()
        assertThat(stagingStore.restore(STAGING_KEY, 4)).isNull()
        assertThat(checkpointStore.load(CHECKPOINT_KEY)).isNull()
    }

    private fun checkpoint() = UploadCheckpoint(
        key = CHECKPOINT_KEY,
        sourceIdentity = "sha256:source:4",
        remotePath = "/target.bin",
        fileSize = 4,
        uploadId = "upload-id",
        chunkSize = 4,
        totalChunks = 1,
        updatedAtMillis = 123,
    )

    private companion object {
        const val STAGING_KEY = "work-id"
        const val CHECKPOINT_KEY = "checkpoint-id"
    }
}
