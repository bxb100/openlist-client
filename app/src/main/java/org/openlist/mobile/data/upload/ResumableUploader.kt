package org.openlist.mobile.data.upload

import kotlinx.coroutines.delay
import org.openlist.mobile.data.api.dto.MultipartSession
import java.io.IOException

fun interface UploadSleeper {
    suspend fun sleep(milliseconds: Long)
}

data class UploadRetryPolicy(
    val maxChunkAttempts: Int = 6,
    val initialDelayMillis: Long = 250,
    val maxDelayMillis: Long = 8_000,
    val maxSessionRebuilds: Int = 3,
    val maxPipelineRetries: Int = 3,
) {
    init {
        require(maxChunkAttempts > 0)
        require(initialDelayMillis >= 0)
        require(maxDelayMillis >= initialDelayMillis)
        require(maxSessionRebuilds >= 0)
        require(maxPipelineRetries >= 0)
    }
}

class ResumableUploader(
    private val transport: MultipartUploadTransport,
    private val checkpoints: UploadCheckpointStore,
    private val retryPolicy: UploadRetryPolicy = UploadRetryPolicy(),
    private val sleeper: UploadSleeper = UploadSleeper { delay(it) },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun upload(
        command: UploadCommand,
        source: RandomAccessUploadSource,
        onProgress: suspend (UploadProgress) -> Unit = {},
    ): UploadResult {
        require(source.size == command.fileSize) {
            "源文件大小已变化（预期 ${command.fileSize}，实际 ${source.size}）"
        }
        onProgress(UploadProgress(uploadedBytes = 0, totalBytes = command.fileSize))

        val initRequest = command.toInitRequest()
        if (command.fileSize == 0L) {
            return legacyPut(command, initRequest, source, onProgress)
        }

        var rebuilds = 0
        while (true) {
            when (val acquired = acquireSession(command, initRequest)) {
                SessionAcquisition.LegacyFallback ->
                    return legacyPut(command, initRequest, source, onProgress)
                is SessionAcquisition.Session -> {
                    try {
                        val session = driveSession(command, source, acquired.value, onProgress)
                        checkpoints.remove(command.checkpointKey)
                        return UploadResult(UploadMode.MULTIPART, command.fileSize, session)
                    } catch (gone: UploadSessionGoneException) {
                        checkpoints.remove(command.checkpointKey)
                        if (rebuilds++ >= retryPolicy.maxSessionRebuilds) {
                            throw IOException("服务器多次丢失上传会话，已停止自动重建", gone)
                        }
                    }
                }
            }
        }
    }

    suspend fun abort(command: UploadCommand) {
        val checkpoint = checkpoints.load(command.checkpointKey)
        if (checkpoint == null || !checkpoint.matches(command)) {
            checkpoints.remove(command.checkpointKey)
            return
        }
        try {
            transport.abort(checkpoint.uploadId)
            checkpoints.remove(command.checkpointKey)
        } catch (error: UploadProtocolException) {
            if (error.effectiveCode == 404) {
                checkpoints.remove(command.checkpointKey)
            } else {
                throw error
            }
        }
    }

    private suspend fun acquireSession(
        command: UploadCommand,
        request: MultipartInitRequest,
    ): SessionAcquisition {
        val checkpoint = checkpoints.load(command.checkpointKey)
        if (checkpoint != null && checkpoint.matches(command)) {
            try {
                val session = transport.status(checkpoint.uploadId)
                validateSession(command, session)
                saveCheckpoint(command, session)
                return SessionAcquisition.Session(session)
            } catch (error: UploadProtocolException) {
                if (error.effectiveCode != 404) throw error
                checkpoints.remove(command.checkpointKey)
            }
        } else if (checkpoint != null) {
            checkpoints.remove(command.checkpointKey)
        }

        val session = try {
            transport.init(request)
        } catch (error: UploadProtocolException) {
            if (error.isMultipartUnavailable()) return SessionAcquisition.LegacyFallback
            throw error
        }
        validateSession(command, session)
        saveCheckpoint(command, session)
        return SessionAcquisition.Session(session)
    }

    private suspend fun driveSession(
        command: UploadCommand,
        source: RandomAccessUploadSource,
        initial: MultipartSession,
        onProgress: suspend (UploadProgress) -> Unit,
    ): MultipartSession {
        var session = initial
        var pipelineRetries = 0
        while (true) {
            validateSession(command, session)
            reportProgress(command, session, null, onProgress)
            when (val action = UploadStateMachine.next(session)) {
                UploadAction.Finished -> return session
                is UploadAction.Fail -> throw UploadPermanentException(action.reason)
                UploadAction.Complete -> {
                    session = completeSession(command, session)
                }
                is UploadAction.SendChunks -> {
                    if (session.state.equals("failed_retriable", ignoreCase = true)) {
                        if (pipelineRetries++ >= retryPolicy.maxPipelineRetries) {
                            throw IOException(
                                session.error?.let { "存储端持续临时失败：$it" }
                                    ?: "存储端持续临时失败",
                            )
                        }
                    }
                    for (index in action.indices) {
                        session = sendChunkWithBackoff(command, source, session, index)
                        saveCheckpoint(command, session)
                        reportProgress(command, session, index, onProgress)
                        if (!session.state.equals("receiving", ignoreCase = true) &&
                            session.state.isNotBlank()
                        ) {
                            break
                        }
                    }
                }
            }
        }
    }

    private suspend fun completeSession(
        command: UploadCommand,
        current: MultipartSession,
    ): MultipartSession {
        return try {
            transport.complete(current.uploadId).also {
                validateSession(command, it)
                saveCheckpoint(command, it)
            }
        } catch (error: UploadProtocolException) {
            if (error.effectiveCode == 404) throw UploadSessionGoneException(error)
            val session = error.session
            if (session != null && (
                    !session.state.equals("receiving", ignoreCase = true) ||
                        ChunkRanges.missing(session.totalChunks, session.received).isNotEmpty()
                    )
            ) {
                validateSession(command, session)
                saveCheckpoint(command, session)
                session
            } else {
                throw error
            }
        }
    }

    private suspend fun sendChunkWithBackoff(
        command: UploadCommand,
        source: RandomAccessUploadSource,
        current: MultipartSession,
        index: Int,
    ): MultipartSession {
        var attempt = 0
        var session = current
        while (true) {
            val offset = index.toLong() * session.chunkSize
            val length = minOf(session.chunkSize, command.fileSize - offset)
            if (offset < 0 || length <= 0) {
                throw UploadPermanentException("服务器返回了越界的分片索引：$index")
            }
            try {
                return transport.uploadChunk(
                    session.uploadId,
                    index,
                    source.requestBody(offset, length),
                )
            } catch (error: UploadProtocolException) {
                if (error.effectiveCode == 404) throw UploadSessionGoneException(error)
                if (error.effectiveCode != 409 && error.effectiveCode != 429) {
                    val errorSession = error.session
                    if (errorSession != null && (
                            !errorSession.state.equals("receiving", ignoreCase = true) ||
                                ChunkRanges.contains(errorSession.received, index)
                            )
                    ) {
                        return errorSession
                    }
                    throw error
                }
                attempt++
                if (attempt >= retryPolicy.maxChunkAttempts) throw error
                sleeper.sleep(retryDelayMillis(attempt, error.retryAfterMillis))
                session = try {
                    transport.status(session.uploadId)
                } catch (statusError: UploadProtocolException) {
                    if (statusError.effectiveCode == 404) throw UploadSessionGoneException(statusError)
                    throw statusError
                }
                validateSession(command, session)
                saveCheckpoint(command, session)
                if (ChunkRanges.contains(session.received, index) ||
                    !session.state.equals("receiving", ignoreCase = true)
                ) {
                    return session
                }
            }
        }
    }

    private fun retryDelayMillis(attempt: Int, retryAfterMillis: Long?): Long {
        val shift = (attempt - 1).coerceIn(0, 30)
        val exponential = if (shift >= 63) Long.MAX_VALUE else retryPolicy.initialDelayMillis shl shift
        val bounded = exponential.coerceAtMost(retryPolicy.maxDelayMillis)
        val serverDelay = (retryAfterMillis ?: 0L).coerceIn(0L, retryPolicy.maxDelayMillis)
        return maxOf(bounded, serverDelay)
    }

    private suspend fun legacyPut(
        command: UploadCommand,
        request: MultipartInitRequest,
        source: RandomAccessUploadSource,
        onProgress: suspend (UploadProgress) -> Unit,
    ): UploadResult {
        transport.legacyPut(
            request,
            source.requestBody(0, command.fileSize, command.mimeType),
        )
        checkpoints.remove(command.checkpointKey)
        onProgress(UploadProgress(command.fileSize, command.fileSize))
        return UploadResult(UploadMode.LEGACY_PUT, command.fileSize)
    }

    private fun validateSession(command: UploadCommand, session: MultipartSession) {
        if (session.uploadId.isBlank()) throw UploadPermanentException("服务器未返回 upload_id")
        if (session.size != command.fileSize) {
            throw UploadPermanentException(
                "上传会话文件大小不一致（本地 ${command.fileSize}，服务器 ${session.size}）",
            )
        }
        if (session.chunkSize <= 0 || session.totalChunks <= 0) {
            throw UploadPermanentException("服务器返回了无效的 chunk_size 或 total_chunks")
        }
        val expectedChunks = ((command.fileSize - 1) / session.chunkSize + 1)
        if (expectedChunks > Int.MAX_VALUE || session.totalChunks != expectedChunks.toInt()) {
            throw UploadPermanentException(
                "服务器分片数量不一致（预期 $expectedChunks，服务器 ${session.totalChunks}）",
            )
        }
    }

    private suspend fun saveCheckpoint(command: UploadCommand, session: MultipartSession) {
        checkpoints.save(
            UploadCheckpoint(
                key = command.checkpointKey,
                sourceIdentity = command.sourceIdentity,
                remotePath = command.remotePath,
                fileSize = command.fileSize,
                uploadId = session.uploadId,
                chunkSize = session.chunkSize,
                totalChunks = session.totalChunks,
                updatedAtMillis = nowMillis(),
            ),
        )
    }

    private suspend fun reportProgress(
        command: UploadCommand,
        session: MultipartSession,
        chunkIndex: Int?,
        onProgress: suspend (UploadProgress) -> Unit,
    ) {
        onProgress(
            UploadProgress(
                uploadedBytes = session.receivedBytes.coerceIn(0L, command.fileSize),
                totalBytes = command.fileSize,
                chunkIndex = chunkIndex,
                totalChunks = session.totalChunks,
            ),
        )
    }

    private fun UploadCommand.toInitRequest() = MultipartInitRequest(
        remotePath = remotePath,
        fileSize = fileSize,
        mimeType = mimeType,
        modifiedAtMillis = modifiedAtMillis,
        overwrite = overwrite,
        preferredChunkSize = preferredChunkSize,
        hashes = hashes,
    )

    private sealed interface SessionAcquisition {
        data object LegacyFallback : SessionAcquisition
        data class Session(val value: MultipartSession) : SessionAcquisition
    }
}
