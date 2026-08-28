package org.openlist.mobile.data.api.dto

import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import org.openlist.mobile.core.model.NullToEmptyCollectionAdapterFactory

data class ApiEnvelope<T>(
    val code: Int = 0,
    val message: String? = null,
    val data: T? = null,
)

data class LoginRequest(
    val username: String,
    val password: String,
    @SerializedName("otp_code") val otpCode: String = "",
)

data class LoginData(val token: String = "")

/** Fields consumed by OpenList's current-user update handler. */
data class UpdateMeRequest(
    val username: String,
    val password: String = "",
    @SerializedName("sso_id") val ssoId: String = "",
)

data class ListRequest(
    val path: String,
    val password: String = "",
    val page: Int = 1,
    @SerializedName("per_page") val perPage: Int = 0,
    val refresh: Boolean = false,
)

data class GetRequest(val path: String, val password: String = "")

data class SearchRequest(
    val parent: String,
    val keywords: String,
    val scope: Int = 0,
    val page: Int = 1,
    @SerializedName("per_page") val perPage: Int = 100,
    val password: String = "",
)

data class SearchData(
    @field:JsonAdapter(NullToEmptyCollectionAdapterFactory::class, nullSafe = false)
    val content: List<SearchObject> = emptyList(),
    val total: Long = 0,
)

data class SearchObject(
    val parent: String = "/",
    val name: String = "",
    @SerializedName("is_dir") val isDirectory: Boolean = false,
    val size: Long = 0,
    val type: Int = 0,
)

data class DirectoryEntry(
    val name: String = "",
    val modified: String = "",
)

data class PathRequest(val path: String)

data class RenameRequest(
    val path: String,
    val name: String,
    val overwrite: Boolean = false,
)

data class TransferRequest(
    @SerializedName("src_dir") val sourceDirectory: String,
    @SerializedName("dst_dir") val destinationDirectory: String,
    val names: List<String>,
    val overwrite: Boolean = false,
    @SerializedName("skip_existing") val skipExisting: Boolean = false,
    val merge: Boolean = false,
)

data class RemoveRequest(val dir: String, val names: List<String>)

data class VerifyTwoFactorRequest(val code: String, val secret: String)

data class TwoFactorSetup(val qr: String = "", val secret: String = "")

data class TaskInfo(
    val id: String = "",
    val name: String = "",
    val creator: String = "",
    @SerializedName("creator_role") val creatorRole: Int = -1,
    val state: Int = 0,
    val status: String = "",
    val progress: Double = 0.0,
    @SerializedName("start_time") val startTime: String? = null,
    @SerializedName("end_time") val endTime: String? = null,
    @SerializedName("total_bytes") val totalBytes: Long = 0,
    val error: String = "",
)

data class MultipartSession(
    @SerializedName("upload_id") val uploadId: String = "",
    val state: String = "",
    val attempt: Int = 0,
    val path: String = "",
    val size: Long = 0,
    @SerializedName("chunk_size") val chunkSize: Long = 0,
    @SerializedName("total_chunks") val totalChunks: Int = 0,
    @field:JsonAdapter(NullToEmptyCollectionAdapterFactory::class, nullSafe = false)
    val received: List<List<Int>> = emptyList(),
    @SerializedName("received_bytes") val receivedBytes: Long = 0,
    val frontier: Int = 0,
    @SerializedName("storage_progress") val storageProgress: Double = 0.0,
    val error: String? = null,
    val resumed: Boolean = false,
)
