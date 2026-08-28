package org.openlist.mobile.data.upload

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.RequestBody
import org.junit.Test
import org.openlist.mobile.data.api.dto.MultipartSession

class ResumableUploaderTest {
    @Test
    fun `checkpoint status resumes and uploads only server-reported missing chunks`() = runTest {
        val command = command()
        val store = InMemoryUploadCheckpointStore()
        store.save(checkpoint(command, "existing"))
        val transport = FakeTransport().apply {
            statusResult = session("existing", received = setOf(0, 2))
        }
        val uploader = ResumableUploader(transport, store, multipartEnabled = true)

        val result = uploader.upload(command, ByteArrayUploadSource(ByteArray(8)))

        assertThat(result.mode).isEqualTo(UploadMode.MULTIPART)
        assertThat(transport.initCalls).isEqualTo(0)
        assertThat(transport.uploadedChunks).containsExactly(1, 3).inOrder()
        assertThat(store.load(command.checkpointKey)).isNull()
    }

    @Test
    fun `404 status discards stale checkpoint and rebuilds session`() = runTest {
        val command = command()
        val store = InMemoryUploadCheckpointStore()
        store.save(checkpoint(command, "stale"))
        val transport = FakeTransport().apply { statusNotFoundOnce = true }
        val uploader = ResumableUploader(transport, store, multipartEnabled = true)

        uploader.upload(command, ByteArrayUploadSource(ByteArray(8)))

        assertThat(transport.initCalls).isEqualTo(1)
        assertThat(transport.uploadedChunks).containsExactly(0, 1, 2, 3).inOrder()
    }

    @Test
    fun `429 backs off and status can confirm chunk without resending`() = runTest {
        val command = command()
        val transport = FakeTransport().apply { throttleChunkOnce = 0 }
        val delays = mutableListOf<Long>()
        val uploader = ResumableUploader(
            transport = transport,
            checkpoints = InMemoryUploadCheckpointStore(),
            multipartEnabled = true,
            sleeper = UploadSleeper { delays += it },
        )

        uploader.upload(command, ByteArrayUploadSource(ByteArray(8)))

        assertThat(delays).containsExactly(1_500L)
        assertThat(transport.chunkAttempts.count { it == 0 }).isEqualTo(1)
    }

    @Test
    fun `server retry-after cannot hold the foreground worker beyond the delay cap`() = runTest {
        val command = command()
        val transport = FakeTransport().apply {
            throttleChunkOnce = 0
            throttleRetryAfterMillis = 24 * 60 * 60 * 1_000L
        }
        val delays = mutableListOf<Long>()
        val uploader = ResumableUploader(
            transport = transport,
            checkpoints = InMemoryUploadCheckpointStore(),
            multipartEnabled = true,
            sleeper = UploadSleeper { delays += it },
        )

        uploader.upload(command, ByteArrayUploadSource(ByteArray(8)))

        assertThat(delays).containsExactly(8_000L)
    }

    @Test
    fun `empty file uses legacy put without multipart init`() = runTest {
        val command = command(fileSize = 0)
        val transport = FakeTransport()

        val result = ResumableUploader(transport, InMemoryUploadCheckpointStore())
            .upload(command, ByteArrayUploadSource(ByteArray(0)))

        assertThat(result.mode).isEqualTo(UploadMode.LEGACY_PUT)
        assertThat(transport.legacyCalls).isEqualTo(1)
        assertThat(transport.initCalls).isEqualTo(0)
    }

    @Test
    fun `nonempty file uses openapi put by default without multipart probes`() = runTest {
        val command = command(mimeType = "video/mp4")
        val store = InMemoryUploadCheckpointStore().apply {
            save(checkpoint(command, "existing"))
        }
        val transport = FakeTransport()

        val result = ResumableUploader(transport, store)
            .upload(command, ByteArrayUploadSource(ByteArray(8)))

        assertThat(result.mode).isEqualTo(UploadMode.LEGACY_PUT)
        assertThat(transport.legacyCalls).isEqualTo(1)
        assertThat(transport.initCalls).isEqualTo(0)
        assertThat(transport.statusCalls).isEqualTo(0)
        assertThat(transport.legacyBodyContentType).isEqualTo("application/octet-stream")
        assertThat(store.load(command.checkpointKey)).isNull()
    }

    @Test
    fun `old server without multipart endpoint falls back to stream put`() = runTest {
        val command = command()
        val transport = FakeTransport().apply { initUnavailable = true }

        val result = ResumableUploader(
            transport,
            InMemoryUploadCheckpointStore(),
            multipartEnabled = true,
        )
            .upload(command, ByteArrayUploadSource(ByteArray(8)))

        assertThat(result.mode).isEqualTo(UploadMode.LEGACY_PUT)
        assertThat(transport.legacyCalls).isEqualTo(1)
    }

    private fun command(
        fileSize: Long = 8,
        mimeType: String = "application/octet-stream",
    ) = UploadCommand(
        checkpointKey = "checkpoint",
        sourceIdentity = "source",
        remotePath = "/target.bin",
        fileSize = fileSize,
        mimeType = mimeType,
    )

    private fun checkpoint(command: UploadCommand, id: String) = UploadCheckpoint(
        key = command.checkpointKey,
        sourceIdentity = command.sourceIdentity,
        remotePath = command.remotePath,
        fileSize = command.fileSize,
        uploadId = id,
        chunkSize = 2,
        totalChunks = 4,
        updatedAtMillis = 1,
    )

    private class FakeTransport : MultipartUploadTransport {
        var initCalls = 0
        var legacyCalls = 0
        var statusCalls = 0
        var statusNotFoundOnce = false
        var initUnavailable = false
        var throttleChunkOnce: Int? = null
        var throttleRetryAfterMillis: Long = 1_500
        var statusResult: MultipartSession? = null
        var legacyBodyContentType: String? = null
        val uploadedChunks = mutableListOf<Int>()
        val chunkAttempts = mutableListOf<Int>()
        private val received = sortedSetOf<Int>()
        private var uploadId = "new"

        override suspend fun init(request: MultipartInitRequest): MultipartSession {
            initCalls++
            if (initUnavailable) throw protocolError(404)
            uploadId = "new-$initCalls"
            received.clear()
            return session(uploadId)
        }

        override suspend fun status(uploadId: String): MultipartSession {
            statusCalls++
            if (statusNotFoundOnce) {
                statusNotFoundOnce = false
                throw protocolError(404)
            }
            statusResult?.let {
                statusResult = null
                this.uploadId = it.uploadId
                received.clear()
                it.received.forEach { range ->
                    if (range.size >= 2) received.addAll(range[0]..range[1])
                }
                return it
            }
            return session(this.uploadId, received)
        }

        override suspend fun uploadChunk(
            uploadId: String,
            index: Int,
            body: RequestBody,
        ): MultipartSession {
            chunkAttempts += index
            if (throttleChunkOnce == index) {
                throttleChunkOnce = null
                received += index // Simulates another request winning the race.
                throw UploadProtocolException(
                    429,
                    429,
                    "window full",
                    retryAfterMillis = throttleRetryAfterMillis,
                )
            }
            received += index
            uploadedChunks += index
            return session(this.uploadId, received)
        }

        override suspend fun complete(uploadId: String): MultipartSession =
            session(this.uploadId, received, state = "completed")

        override suspend fun abort(uploadId: String) = Unit

        override suspend fun legacyPut(request: MultipartInitRequest, body: RequestBody) {
            legacyCalls++
            legacyBodyContentType = body.contentType()?.toString()
        }

        private fun protocolError(code: Int) = UploadProtocolException(code, code, "error")
    }

    companion object {
        private fun session(
            id: String,
            received: Set<Int> = emptySet(),
            state: String = "receiving",
        ): MultipartSession = MultipartSession(
            uploadId = id,
            state = state,
            size = 8,
            chunkSize = 2,
            totalChunks = 4,
            received = received.map { listOf(it, it) },
            receivedBytes = received.size * 2L,
        )
    }
}
