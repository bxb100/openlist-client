package org.openlist.mobile.data.api

import com.google.gson.JsonElement
import okhttp3.RequestBody
import org.openlist.mobile.core.model.DirectoryListing
import org.openlist.mobile.core.model.FileDetails
import org.openlist.mobile.core.model.OpenListUser
import org.openlist.mobile.data.api.dto.GetRequest
import org.openlist.mobile.data.api.dto.DirectoryEntry
import org.openlist.mobile.data.api.dto.ListRequest
import org.openlist.mobile.data.api.dto.LoginData
import org.openlist.mobile.data.api.dto.LoginRequest
import org.openlist.mobile.data.api.dto.PathRequest
import org.openlist.mobile.data.api.dto.RemoveRequest
import org.openlist.mobile.data.api.dto.RenameRequest
import org.openlist.mobile.data.api.dto.SearchData
import org.openlist.mobile.data.api.dto.SearchRequest
import org.openlist.mobile.data.api.dto.TransferRequest
import org.openlist.mobile.data.api.dto.TwoFactorSetup
import org.openlist.mobile.data.api.dto.UpdateMeRequest
import org.openlist.mobile.data.api.dto.VerifyTwoFactorRequest

enum class OfflineDownloadDeletePolicy(val wireValue: String) {
    DELETE_ON_UPLOAD_SUCCEED("delete_on_upload_succeed"),
    DELETE_ON_UPLOAD_FAILED("delete_on_upload_failed"),
    DELETE_NEVER("delete_never"),
    DELETE_ALWAYS("delete_always"),
    UPLOAD_DOWNLOAD_STREAM("upload_download_stream"),
}

class OpenListApi(private val http: OpenListHttpClient) {
    suspend fun login(username: String, password: String, otpCode: String = ""): LoginData =
        http.post("/api/auth/login", LoginRequest(username, password, otpCode))

    suspend fun loginWithHash(username: String, passwordHash: String, otpCode: String = ""): LoginData =
        http.post("/api/auth/login/hash", LoginRequest(username, passwordHash, otpCode))

    suspend fun loginWithLdap(username: String, password: String): LoginData =
        http.post("/api/auth/login/ldap", LoginRequest(username, password))

    suspend fun logout() = http.get<Unit>("/api/auth/logout")

    suspend fun me(): OpenListUser = http.get("/api/me")

    suspend fun updateMe(user: OpenListUser, password: String = "") =
        http.post<Unit>(
            "/api/me/update",
            UpdateMeRequest(
                username = user.username,
                password = password,
                ssoId = user.ssoId,
            ),
        )

    suspend fun generateTwoFactor(): TwoFactorSetup = http.post("/api/auth/2fa/generate")

    suspend fun verifyTwoFactor(code: String, secret: String) =
        http.post<Unit>("/api/auth/2fa/verify", VerifyTwoFactorRequest(code, secret))

    suspend fun publicSettings(): JsonElement = http.get("/api/public/settings")

    suspend fun serverCapabilities(): ServerCapabilities =
        ServerCapabilities.from(http.get<Map<String, String>>("/api/public/settings"))

    suspend fun offlineDownloadTools(): List<String> = http.get("/api/public/offline_download_tools")

    suspend fun archiveExtensions(): List<String> = http.get("/api/public/archive_extensions")

    suspend fun list(
        path: String,
        password: String = "",
        page: Int = 1,
        perPage: Int = 0,
        refresh: Boolean = false,
    ): DirectoryListing = http.post(
        "/api/fs/list",
        ListRequest(path, password, page, perPage, refresh),
    )

    suspend fun get(path: String, password: String = ""): FileDetails =
        http.post("/api/fs/get", GetRequest(path, password))

    suspend fun directories(path: String, password: String = "", forceRoot: Boolean = false): List<DirectoryEntry> =
        http.post(
            "/api/fs/dirs",
            mapOf("path" to path, "password" to password, "force_root" to forceRoot),
        )

    suspend fun search(
        parent: String,
        keywords: String,
        scope: Int = 0,
        page: Int = 1,
        perPage: Int = 100,
        password: String = "",
    ): SearchData = http.post(
        "/api/fs/search",
        SearchRequest(parent, keywords, scope, page, perPage, password),
    )

    suspend fun makeDirectory(path: String) = http.post<Unit>("/api/fs/mkdir", PathRequest(path))

    suspend fun rename(path: String, newName: String, overwrite: Boolean = false) =
        http.post<Unit>("/api/fs/rename", RenameRequest(path, newName, overwrite))

    suspend fun move(request: TransferRequest): JsonElement = http.post("/api/fs/move", request)

    suspend fun copy(request: TransferRequest): JsonElement = http.post("/api/fs/copy", request)

    suspend fun remove(directory: String, names: List<String>) =
        http.post<Unit>("/api/fs/remove", RemoveRequest(directory, names))

    suspend fun removeEmptyDirectories(sourceDirectory: String) =
        http.post<Unit>("/api/fs/remove_empty_directory", mapOf("src_dir" to sourceDirectory))

    suspend fun addOfflineDownload(
        path: String,
        urls: List<String>,
        tool: String,
        deletePolicy: OfflineDownloadDeletePolicy? = null,
    ): JsonElement =
        http.post(
            "/api/fs/add_offline_download",
            buildMap<String, Any> {
                put("path", path)
                put("urls", urls)
                put("tool", tool)
                deletePolicy?.let { put("delete_policy", it.wireValue) }
            },
        )

    suspend fun dynamicGet(path: String, query: Map<String, String?> = emptyMap()): JsonElement =
        http.get(path, query)

    suspend fun dynamicPost(
        path: String,
        body: Any? = null,
        query: Map<String, String?> = emptyMap(),
    ): JsonElement = http.post(path, body, query)

    suspend fun dynamicPut(
        path: String,
        body: RequestBody,
        query: Map<String, String?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): JsonElement = http.put(path, body, query, headers)
}
