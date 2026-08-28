package org.openlist.mobile.data.upload

object UploadHeaders {
    const val FILE_PATH = "File-Path"
    const val FILE_SIZE = "X-File-Size"
    const val CHUNK_SIZE = "X-Chunk-Size"
    const val UPLOAD_ID = "X-Upload-Id"
    const val CHUNK_INDEX = "X-Chunk-Index"
    const val OVERWRITE = "Overwrite"
    const val LAST_MODIFIED = "Last-Modified"
    const val FILE_MD5 = "X-File-Md5"
    const val FILE_SHA1 = "X-File-Sha1"
    const val FILE_SHA256 = "X-File-Sha256"
    const val CONTENT_TYPE = "Content-Type"

    fun init(request: MultipartInitRequest): Map<String, String> = buildMap {
        put(FILE_PATH, Rfc3986.encode(request.remotePath))
        put(FILE_SIZE, request.fileSize.toString())
        put(OVERWRITE, request.overwrite.toString())
        put(CONTENT_TYPE, request.mimeType.ifBlank { "application/octet-stream" })
        request.preferredChunkSize?.let { put(CHUNK_SIZE, it.toString()) }
        request.modifiedAtMillis?.let { put(LAST_MODIFIED, it.toString()) }
        request.hashes.md5?.takeIf(String::isNotBlank)?.let { put(FILE_MD5, it) }
        request.hashes.sha1?.takeIf(String::isNotBlank)?.let { put(FILE_SHA1, it) }
        request.hashes.sha256?.takeIf(String::isNotBlank)?.let { put(FILE_SHA256, it) }
    }

    fun chunk(uploadId: String, index: Int): Map<String, String> = mapOf(
        UPLOAD_ID to uploadId,
        CHUNK_INDEX to index.toString(),
    )

    fun session(uploadId: String): Map<String, String> = mapOf(UPLOAD_ID to uploadId)

    fun legacyPut(request: MultipartInitRequest): Map<String, String> = buildMap {
        put(FILE_PATH, Rfc3986.encode(request.remotePath))
        put(FILE_SIZE, request.fileSize.toString())
        put(OVERWRITE, request.overwrite.toString())
        put(CONTENT_TYPE, request.mimeType.ifBlank { "application/octet-stream" })
        request.modifiedAtMillis?.let { put(LAST_MODIFIED, it.toString()) }
        request.hashes.md5?.takeIf(String::isNotBlank)?.let { put(FILE_MD5, it) }
        request.hashes.sha1?.takeIf(String::isNotBlank)?.let { put(FILE_SHA1, it) }
        request.hashes.sha256?.takeIf(String::isNotBlank)?.let { put(FILE_SHA256, it) }
    }
}
