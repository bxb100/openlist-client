package org.openlist.mobile.media

import androidx.media3.common.MimeTypes
import org.openlist.mobile.core.model.FileDetails
import org.openlist.mobile.core.model.MediaKind
import org.openlist.mobile.core.model.OpenListObject
import org.openlist.mobile.core.model.ServerProfile
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/** A cache-safe identity for one revision of an OpenList object. */
@JvmInline
value class ContentKey(val value: String) {
    init {
        require(value.isNotBlank()) { "ContentKey must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * A media item before its expiring/signed raw URL has been resolved.
 *
 * Keeping only the remote path here is deliberate: raw URLs and signatures can expire and must not
 * become queue or cache identity.
 */
data class MediaEntry(
    val remotePath: String,
    val name: String,
    val kind: MediaKind,
    val size: Long,
    val modified: String,
    val contentKey: ContentKey,
    val mimeType: String? = MediaTypeDetector.mimeType(name, kind),
    val subtitles: List<SubtitleEntry> = emptyList(),
) {
    init {
        require(remotePath.startsWith('/')) { "remotePath must be absolute" }
        require(name.isNotBlank()) { "name must not be blank" }
        require(kind in supportedMediaKinds) { "Only audio, video, and image entries are supported" }
        require(kind == MediaKind.VIDEO || subtitles.isEmpty()) { "Only video entries may have subtitles" }
    }
}

class MediaSequence(
    items: List<MediaEntry>,
    val currentIndex: Int,
    val kind: MediaKind,
    /** True when listing the parent failed and the sequence contains only the requested item. */
    val isDirectoryFallback: Boolean = false,
) {
    /** Immutable directory snapshot; later list refreshes never mutate an active queue/gallery. */
    val items: List<MediaEntry> = items.toList()

    init {
        require(this.items.isNotEmpty()) { "A media sequence must not be empty" }
        require(currentIndex in this.items.indices) { "currentIndex is outside the sequence" }
        require(this.items.all { it.kind == kind }) { "A media sequence may contain only one media kind" }
    }

    val current: MediaEntry get() = items[currentIndex]
}

object ContentKeyFactory {
    private const val PREFIX = "openlist-content-v2:"

    /**
     * Builds a deterministic key from server, path, and content revision. Auth tokens, raw URLs,
     * thumbnail URLs and OpenList `sign` are intentionally not inputs.
     */
    fun forObject(serverIdentity: String, remotePath: String, item: OpenListObject): ContentKey =
        forObject(serverIdentity, "", remotePath, item)

    fun forObject(
        serverIdentity: String,
        accountIdentity: String,
        remotePath: String,
        item: OpenListObject,
    ): ContentKey =
        create(
            serverIdentity = serverIdentity,
            accountIdentity = accountIdentity,
            remotePath = remotePath,
            revision = revision(item.hashes, item.hashinfo, item.modified, item.size),
        )

    fun forObject(profile: ServerProfile, remotePath: String, item: OpenListObject): ContentKey =
        forObject(profile.baseUrl, profile.username, remotePath, item)

    /** See [forObject]. [FileDetails.rawUrl] and [FileDetails.sign] are intentionally ignored. */
    fun forDetails(serverIdentity: String, remotePath: String, details: FileDetails): ContentKey =
        forDetails(serverIdentity, "", remotePath, details)

    fun forDetails(
        serverIdentity: String,
        accountIdentity: String,
        remotePath: String,
        details: FileDetails,
    ): ContentKey =
        create(
            serverIdentity = serverIdentity,
            accountIdentity = accountIdentity,
            remotePath = remotePath,
            revision = revision(details.hashes, details.hashinfo, details.modified, details.size),
        )

    fun forDetails(profile: ServerProfile, remotePath: String, details: FileDetails): ContentKey =
        forDetails(profile.baseUrl, profile.username, remotePath, details)

    internal fun revision(
        hashes: Map<String, String>?,
        hashInfo: String,
        modified: String,
        size: Long,
    ): String {
        preferredHash(hashes)?.let { (algorithm, value) ->
            return "hash:$algorithm:$value"
        }
        hashInfo.trim().takeIf(String::isNotEmpty)?.let { return "hashinfo:$it" }
        return "metadata:${modified.trim()}:$size"
    }

    private fun create(
        serverIdentity: String,
        accountIdentity: String,
        remotePath: String,
        revision: String,
    ): ContentKey {
        val canonicalFields = listOf(
            canonicalServerIdentity(serverIdentity),
            accountIdentity.trim(),
            normalizeRemotePath(remotePath),
            revision,
        ).joinToString(separator = "") { value -> "${value.length}:$value" }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonicalFields.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(radix = 16).padStart(length = 2, padChar = '0')
        }
        return ContentKey(PREFIX + hex)
    }

    private fun preferredHash(hashes: Map<String, String>?): Pair<String, String>? {
        val normalized = hashes.orEmpty()
            .mapNotNull { (algorithm, value) ->
                val cleanAlgorithm = algorithm.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
                val trimmedValue = value.trim()
                val cleanValue = if (trimmedValue.matches(HEX_HASH)) {
                    trimmedValue.lowercase(Locale.ROOT)
                } else {
                    trimmedValue
                }
                if (cleanAlgorithm.isBlank() || cleanValue.isBlank()) null else cleanAlgorithm to cleanValue
            }
            .groupBy(keySelector = Pair<String, String>::first, valueTransform = Pair<String, String>::second)
            .mapValues { (_, values) -> values.minOrNull().orEmpty() }
        val preferredAlgorithms = listOf(
            "blake3",
            "sha512",
            "sha384",
            "sha256",
            "sha224",
            "sha1",
            "md5",
            "crc32",
        )
        preferredAlgorithms.firstNotNullOfOrNull { algorithm ->
            normalized[algorithm]?.let { algorithm to it }
        }?.let { return it }
        return normalized.entries.sortedBy(Map.Entry<String, String>::key).firstOrNull()?.toPair()
    }

    private fun canonicalServerIdentity(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        if (trimmed.isBlank()) return "local"
        return runCatching {
            val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
            val uri = URI(withScheme)
            val scheme = uri.scheme?.lowercase(Locale.ROOT).orEmpty()
            val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
            val port = when {
                uri.port < 0 -> -1
                scheme == "http" && uri.port == 80 -> -1
                scheme == "https" && uri.port == 443 -> -1
                else -> uri.port
            }
            URI(scheme, null, host, port, uri.path.ifBlank { null }, null, null).toString().trimEnd('/')
        }.getOrElse {
            // Invalid profile values still get a stable key without retaining query credentials.
            trimmed.substringBefore('?')
                .substringBefore('#')
                .substringAfterLast('@')
                .trimEnd('/')
                .lowercase(Locale.ROOT)
        }
    }

    private val HEX_HASH = Regex("[0-9a-fA-F]+")
}

object MediaTypeDetector {
    private val authoritativeAudioExtensions = setOf("wma")
    private val authoritativeVideoExtensions = setOf("m3u8", "mkv", "wmv")
    private val audioExtensions = setOf(
        "aac", "aif", "aiff", "alac", "amr", "ape", "dff", "dsf", "flac", "m4a",
        "mid", "midi", "mp3", "oga", "ogg", "opus", "wav", "weba", "wma",
    )
    private val videoExtensions = setOf(
        "3gp", "avi", "flv", "m2ts", "m4v", "mkv", "mov", "mp4", "mpeg", "mpg",
        "m3u8", "mts", "ogv", "rmvb", "ts", "webm", "wmv",
    )
    private val imageExtensions = setOf(
        "avif", "bmp", "gif", "heic", "heif", "jpeg", "jpg", "jxl", "png", "svg",
        "tif", "tiff", "webp",
    )

    /**
     * Trusts a known server type and falls back to extension only when OpenList reports OTHER.
     * Streaming/container extensions and legacy Windows Media files are exceptions: OpenList
     * instances sometimes report their text/container payload as another known type, but they
     * must still enter the matching playback pipeline.
     */
    fun kind(item: OpenListObject, fileName: String = item.name): MediaKind = when {
        item.mediaKind == MediaKind.DIRECTORY -> MediaKind.DIRECTORY
        hasAuthoritativeAudioExtension(fileName) -> MediaKind.AUDIO
        hasAuthoritativeVideoExtension(fileName) -> MediaKind.VIDEO
        item.mediaKind == MediaKind.OTHER -> kindFromName(fileName)
        else -> item.mediaKind
    }

    fun kind(details: FileDetails): MediaKind = kind(details.asObject())

    fun kindFromName(name: String): MediaKind = when (extension(name)) {
        in audioExtensions -> MediaKind.AUDIO
        in videoExtensions -> MediaKind.VIDEO
        in imageExtensions -> MediaKind.IMAGE
        else -> MediaKind.OTHER
    }

    fun mimeType(name: String, kind: MediaKind = kindFromName(name)): String? {
        val extension = extension(name)
        return when (extension) {
            "m3u8" -> MimeTypes.APPLICATION_M3U8
            "aac" -> "audio/aac"
            "flac" -> "audio/flac"
            "m4a" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "oga", "ogg" -> "audio/ogg"
            "opus" -> "audio/opus"
            "wav" -> "audio/wav"
            "wma" -> "audio/x-ms-wma"
            "avi" -> "video/x-msvideo"
            "m4v", "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "mpeg", "mpg" -> "video/mpeg"
            "ogv" -> "video/ogg"
            "rmvb" -> "application/vnd.rn-realmedia-vbr"
            "ts", "m2ts", "mts" -> "video/mp2t"
            "webm" -> "video/webm"
            "wmv" -> "video/x-ms-wmv"
            "avif" -> "image/avif"
            "bmp" -> "image/bmp"
            "gif" -> "image/gif"
            "heic", "heif" -> "image/heif"
            "jpeg", "jpg" -> "image/jpeg"
            "png" -> "image/png"
            "svg" -> "image/svg+xml"
            "tif", "tiff" -> "image/tiff"
            "webp" -> "image/webp"
            else -> when (kind) {
                MediaKind.AUDIO -> "audio/*"
                MediaKind.VIDEO -> "video/*"
                MediaKind.IMAGE -> "image/*"
                else -> null
            }
        }
    }

    private fun extension(name: String): String = name
        .substringAfterLast('/', name)
        .substringAfterLast('.', missingDelimiterValue = "")
        .lowercase(Locale.ROOT)

    private fun hasAuthoritativeAudioExtension(name: String): Boolean =
        extension(name) in authoritativeAudioExtensions

    private fun hasAuthoritativeVideoExtension(name: String): Boolean =
        extension(name) in authoritativeVideoExtensions
}

internal val supportedMediaKinds = setOf(MediaKind.AUDIO, MediaKind.VIDEO, MediaKind.IMAGE)

internal fun normalizeRemotePath(path: String): String {
    if (path.isBlank() || path == "/") return "/"
    return "/${path.trim('/')}"
}
