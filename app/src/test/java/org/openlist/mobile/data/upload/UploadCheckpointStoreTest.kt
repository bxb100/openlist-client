package org.openlist.mobile.data.upload

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UploadCheckpointStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `json checkpoint survives a new store instance and can be removed`() = runTest {
        val directory = temporaryFolder.newFolder("checkpoints")
        val checkpoint = UploadCheckpoint(
            key = "server-path-source",
            sourceIdentity = "source",
            remotePath = "/target.bin",
            fileSize = 10,
            uploadId = "upload-id",
            chunkSize = 4,
            totalChunks = 3,
            updatedAtMillis = 123,
        )

        JsonUploadCheckpointStore(directory).save(checkpoint)
        val restored = JsonUploadCheckpointStore(directory).load(checkpoint.key)

        assertThat(restored).isEqualTo(checkpoint)
        JsonUploadCheckpointStore(directory).remove(checkpoint.key)
        assertThat(JsonUploadCheckpointStore(directory).load(checkpoint.key)).isNull()
    }

    @Test
    fun `stale checkpoint pruning is age and batch bounded`() = runTest {
        val directory = temporaryFolder.newFolder("stale-checkpoints")
        val store = JsonUploadCheckpointStore(directory)
        fun checkpoint(key: String, updatedAt: Long) = UploadCheckpoint(
            key = key,
            sourceIdentity = "source-$key",
            remotePath = "/$key.bin",
            fileSize = 10,
            uploadId = "upload-$key",
            chunkSize = 4,
            totalChunks = 3,
            updatedAtMillis = updatedAt,
        )
        store.save(checkpoint("oldest", 10))
        store.save(checkpoint("old", 20))
        store.save(checkpoint("fresh", 100))

        val removed = store.pruneOlderThan(cutoffMillis = 50, maxEntries = 1)

        assertThat(removed).isEqualTo(1)
        assertThat(store.load("oldest")).isNull()
        assertThat(store.load("old")).isNotNull()
        assertThat(store.load("fresh")).isNotNull()
    }
}
