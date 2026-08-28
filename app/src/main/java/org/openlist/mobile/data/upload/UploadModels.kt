package org.openlist.mobile.data.upload

import org.openlist.mobile.data.api.dto.MultipartSession
import java.io.IOException

data class UploadFileHashes(
    val md5: String? = null,
    val sha1: String? = null,
    val sha256: String? = null,
)

data class UploadCommand(
    val checkpointKey: String,
    val sourceIdentity: String,
    val remotePath: String,
    val fileSize: Long,
    val mimeType: String = "application/octet-stream",
    val modifiedAtMillis: Long? = null,
    val overwrite: Boolean = true,
    val preferredChunkSize: Long? = null,
    val hashes: UploadFileHashes = UploadFileHashes(),
) {
    init {
        require(checkpointKey.isNotBlank()) { "checkpointKey must not be blank" }
        require(sourceIdentity.isNotBlank()) { "sourceIdentity must not be blank" }
        require(remotePath.isNotBlank()) { "remotePath must not be blank" }
        require(fileSize >= 0) { "fileSize must not be negative" }
        require(preferredChunkSize == null || preferredChunkSize > 0) {
            "preferredChunkSize must be positive"
        }
    }
}

data class MultipartInitRequest(
    val remotePath: String,
    val fileSize: Long,
    val mimeType: String,
    val modifiedAtMillis: Long?,
    val overwrite: Boolean,
    val preferredChunkSize: Long?,
    val hashes: UploadFileHashes,
)

data class UploadCheckpoint(
    val version: Int = CURRENT_VERSION,
    val key: String,
    val sourceIdentity: String,
    val remotePath: String,
    val fileSize: Long,
    val uploadId: String,
    val chunkSize: Long,
    val totalChunks: Int,
    val updatedAtMillis: Long,
) {
    fun matches(command: UploadCommand): Boolean =
        version == CURRENT_VERSION &&
            key == command.checkpointKey &&
            sourceIdentity == command.sourceIdentity &&
            remotePath == command.remotePath &&
            fileSize == command.fileSize &&
            uploadId.isNotBlank() &&
            chunkSize > 0 &&
            totalChunks > 0

    companion object {
        const val CURRENT_VERSION = 1
    }
}

enum class UploadMode {
    MULTIPART,
    LEGACY_PUT,
}

data class UploadResult(
    val mode: UploadMode,
    val uploadedBytes: Long,
    val session: MultipartSession? = null,
)

data class UploadProgress(
    val uploadedBytes: Long,
    val totalBytes: Long,
    val chunkIndex: Int? = null,
    val totalChunks: Int? = null,
)

class UploadProtocolException(
    val httpStatus: Int,
    val apiCode: Int,
    override val message: String,
    val session: MultipartSession? = null,
    val retryAfterMillis: Long? = null,
    val malformedResponse: Boolean = false,
    cause: Throwable? = null,
) : IOException(message, cause) {
    val effectiveCode: Int
        get() = if (httpStatus !in 200..299) httpStatus else apiCode

    fun isMultipartUnavailable(): Boolean =
        effectiveCode == 404 ||
            effectiveCode == 405 ||
            effectiveCode == 501 ||
            (malformedResponse && httpStatus in 200..299) ||
            (effectiveCode == 403 && message.contains("multipart upload is disabled", ignoreCase = true))
}

class UploadPermanentException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

internal class UploadSessionGoneException(
    cause: Throwable,
) : IOException("Multipart upload session no longer exists", cause)
