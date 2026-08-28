package org.openlist.mobile.core.model

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.net.URI

data class ServerProfile(
    val baseUrl: String = "",
    val username: String = "",
    val allowInsecureHttp: Boolean = false,
) {
    fun normalizedBaseUrl(): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        require(trimmed.isNotBlank()) { "服务器地址不能为空" }
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val uri = URI(withScheme)
        require(uri.scheme == "http" || uri.scheme == "https") { "仅支持 HTTP 或 HTTPS 地址" }
        require(!uri.host.isNullOrBlank()) { "服务器地址无效" }
        if (uri.scheme == "http") {
            require(allowInsecureHttp) { "HTTP 连接需要显式允许" }
        }
        return withScheme.trimEnd('/')
    }
}

data class CachePolicy(
    val maxBytes: Long = 2L * 1024 * 1024 * 1024,
    val maxAgeMillis: Long = 7L * 24 * 60 * 60 * 1000,
    val maxEntries: Int = 2_000,
) {
    init {
        require(maxBytes >= 0) { "maxBytes must not be negative" }
        require(maxAgeMillis >= 0) { "maxAgeMillis must not be negative" }
        require(maxEntries >= 0) { "maxEntries must not be negative" }
    }
}

data class OpenListUser(
    val id: Long = 0,
    val username: String = "",
    @SerializedName("base_path") val basePath: String = "/",
    val role: Int = 0,
    val disabled: Boolean = false,
    val permission: Long = 0,
    @SerializedName("sso_id") val ssoId: String = "",
    @SerializedName("allow_ldap") val allowLdap: Boolean = true,
    val otp: Boolean = false,
)

enum class MediaKind {
    DIRECTORY,
    AUDIO,
    VIDEO,
    IMAGE,
    TEXT,
    OTHER,
}

data class OpenListObject(
    val name: String = "",
    val size: Long = 0,
    @SerializedName("is_dir") val isDirectory: Boolean = false,
    val modified: String = "",
    val created: String = "",
    val sign: String = "",
    val thumb: String = "",
    val type: Int = 0,
    val hashinfo: String = "",
    @SerializedName("hash_info") val hashes: Map<String, String>? = null,
    @SerializedName("mount_details") val mountDetails: StorageDetails? = null,
    val id: String = "",
    val path: String = "",
) {
    val mediaKind: MediaKind
        get() = when {
            isDirectory || type == 1 -> MediaKind.DIRECTORY
            type == 2 -> MediaKind.VIDEO
            type == 3 -> MediaKind.AUDIO
            type == 4 -> MediaKind.TEXT
            type == 5 -> MediaKind.IMAGE
            else -> MediaKind.OTHER
        }
}

data class StorageDetails(
    @SerializedName("total_space") val totalSpace: Long = 0,
    @SerializedName("used_space") val usedSpace: Long = 0,
    @SerializedName("free_space") val freeSpace: Long = 0,
    @SerializedName("driver_name") val driverName: String = "",
)

data class DirectoryListing(
    @field:JsonAdapter(NullToEmptyCollectionAdapterFactory::class, nullSafe = false)
    val content: List<OpenListObject> = emptyList(),
    val total: Long = 0,
    val readme: String = "",
    val header: String = "",
    val write: Boolean = false,
    @SerializedName("write_content_bypass") val writeContentBypass: Boolean = false,
    val provider: String = "unknown",
    @field:JsonAdapter(NullToEmptyCollectionAdapterFactory::class, nullSafe = false)
    @SerializedName("direct_upload_tools") val directUploadTools: List<String> = emptyList(),
)

data class FileDetails(
    val name: String = "",
    val size: Long = 0,
    @SerializedName("is_dir") val isDirectory: Boolean = false,
    val modified: String = "",
    val created: String = "",
    val sign: String = "",
    val thumb: String = "",
    val type: Int = 0,
    val hashinfo: String = "",
    @SerializedName("hash_info") val hashes: Map<String, String>? = null,
    @SerializedName("mount_details") val mountDetails: StorageDetails? = null,
    @SerializedName("raw_url") val rawUrl: String = "",
    val readme: String = "",
    val header: String = "",
    val provider: String = "unknown",
    @field:JsonAdapter(NullToEmptyCollectionAdapterFactory::class, nullSafe = false)
    val related: List<OpenListObject> = emptyList(),
    val id: String = "",
    val path: String = "",
) {
    fun asObject() = OpenListObject(
        id = id,
        path = path,
        name = name,
        size = size,
        isDirectory = isDirectory,
        modified = modified,
        created = created,
        sign = sign,
        thumb = thumb,
        type = type,
        hashinfo = hashinfo,
        hashes = hashes,
        mountDetails = mountDetails,
    )
}

/**
 * Gson normally assigns a JSON `null` directly to a Kotlin non-null collection property. Applying
 * this adapter to response fields keeps their public Kotlin contract truthful while preserving
 * Gson's normal element adapters for non-null values.
 */
class NullToEmptyCollectionAdapterFactory : TypeAdapterFactory {
    override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T> {
        val rawType = type.rawType
        require(
            Collection::class.java.isAssignableFrom(rawType) ||
                Map::class.java.isAssignableFrom(rawType),
        ) { "NullToEmptyCollectionAdapterFactory only supports collection or map fields" }

        val jsonElementAdapter = gson.getAdapter(JsonElement::class.java)
        return object : TypeAdapter<T>() {
            override fun read(reader: JsonReader): T {
                if (reader.peek() == JsonToken.NULL) {
                    reader.nextNull()
                    @Suppress("UNCHECKED_CAST")
                    return emptyValue(rawType) as T
                }
                val tree = JsonParser.parseReader(reader)
                return gson.fromJson(tree, type.type)
            }

            override fun write(writer: JsonWriter, value: T?) {
                if (value == null) {
                    writer.nullValue()
                } else {
                    jsonElementAdapter.write(writer, gson.toJsonTree(value, type.type))
                }
            }
        }
    }

    private fun emptyValue(rawType: Class<*>): Any = when {
        Map::class.java.isAssignableFrom(rawType) -> emptyMap<Any?, Any?>()
        Set::class.java.isAssignableFrom(rawType) -> emptySet<Any?>()
        else -> emptyList<Any?>()
    }
}

data class SearchResult(
    @field:JsonAdapter(NullToEmptyCollectionAdapterFactory::class, nullSafe = false)
    val content: List<OpenListObject> = emptyList(),
    val total: Long = 0,
)

fun joinRemotePath(parent: String, child: String): String {
    val cleanParent = parent.trim().let { if (it.isBlank() || it == "/") "" else "/${it.trim('/')}" }
    val cleanChild = child.trim('/')
    return if (cleanParent.isBlank()) "/$cleanChild" else "$cleanParent/$cleanChild"
}

fun parentRemotePath(path: String): String {
    val parts = path.trim('/').split('/').filter(String::isNotBlank)
    return if (parts.size <= 1) "/" else "/${parts.dropLast(1).joinToString("/")}"
}
