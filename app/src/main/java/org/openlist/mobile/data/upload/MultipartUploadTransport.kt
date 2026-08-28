package org.openlist.mobile.data.upload

import okhttp3.RequestBody
import org.openlist.mobile.data.api.dto.MultipartSession

interface MultipartUploadTransport {
    suspend fun init(request: MultipartInitRequest): MultipartSession

    suspend fun status(uploadId: String): MultipartSession

    suspend fun uploadChunk(uploadId: String, index: Int, body: RequestBody): MultipartSession

    suspend fun complete(uploadId: String): MultipartSession

    suspend fun abort(uploadId: String)

    suspend fun legacyPut(request: MultipartInitRequest, body: RequestBody)
}
