package org.openlist.mobile.data.cache

import java.security.MessageDigest
import java.util.Locale
import org.openlist.mobile.core.model.CachePolicy

/** Time source used by the cache. Tests can supply a deterministic implementation. */
fun interface CacheClock {
    fun nowMillis(): Long
}

object SystemCacheClock : CacheClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

/** Any zero limit means zero capacity; [CacheCoordinator.updatePolicy] applies it immediately. */
internal val CachePolicy.acceptsWrites: Boolean
    get() = maxBytes > 0 && maxAgeMillis > 0 && maxEntries > 0

/** A representation is counted independently in maxEntries (for example thumbnail vs original). */
enum class CacheRepresentation(val stableName: String) {
    MEDIA_ORIGINAL("media-original"),
    IMAGE_ORIGINAL("image-original"),
    IMAGE_THUMBNAIL("image-thumbnail"),
}

/**
 * Stable remote-resource identity. Deliberately absent are raw URLs, signatures, passwords and
 * authorization headers, all of which are transient credentials rather than content identity.
 */
data class CacheIdentity(
    val serverProfileId: String,
    val canonicalPath: String,
    val revision: String,
    val representation: String,
) {
    init {
        require(serverProfileId.isNotBlank()) { "serverProfileId must not be blank" }
        require(canonicalPath.isNotBlank()) { "canonicalPath must not be blank" }
        require(revision.isNotBlank()) { "revision must not be blank" }
        require(representation.isNotBlank()) { "representation must not be blank" }
    }

    constructor(
        serverProfileId: String,
        canonicalPath: String,
        revision: String,
        representation: CacheRepresentation,
    ) : this(serverProfileId, canonicalPath, revision, representation.stableName)

    fun toCacheKey(): CacheKey = CacheKey.stable(
        serverProfileId = serverProfileId,
        canonicalPath = canonicalPath,
        revision = revision,
        representation = representation,
    )
}

/**
 * Opaque, filesystem-safe key. The digest uses length-prefixed fields to avoid delimiter
 * collisions and includes a schema identifier so the identity format can evolve safely.
 */
class CacheKey private constructor(val diskId: String) {
    override fun equals(other: Any?): Boolean = other is CacheKey && diskId == other.diskId

    override fun hashCode(): Int = diskId.hashCode()

    override fun toString(): String = diskId

    companion object {
        private const val SCHEMA = "openlist-content-cache-v1"
        private val DISK_ID = Regex("[0-9a-f]{64}")

        fun stable(
            serverProfileId: String,
            canonicalPath: String,
            revision: String,
            representation: CacheRepresentation,
        ): CacheKey = stable(
            serverProfileId = serverProfileId,
            canonicalPath = canonicalPath,
            revision = revision,
            representation = representation.stableName,
        )

        fun stable(
            serverProfileId: String,
            canonicalPath: String,
            revision: String,
            representation: String,
        ): CacheKey {
            require(serverProfileId.isNotBlank()) { "serverProfileId must not be blank" }
            require(canonicalPath.isNotBlank()) { "canonicalPath must not be blank" }
            require(revision.isNotBlank()) { "revision must not be blank" }
            require(representation.isNotBlank()) { "representation must not be blank" }

            val digest = MessageDigest.getInstance("SHA-256")
            listOf(
                SCHEMA,
                serverProfileId.trim(),
                normalizeRemotePath(canonicalPath),
                revision.trim(),
                representation.trim().lowercase(Locale.ROOT),
            ).forEach { value ->
                val bytes = value.toByteArray(Charsets.UTF_8)
                digest.update((bytes.size ushr 24).toByte())
                digest.update((bytes.size ushr 16).toByte())
                digest.update((bytes.size ushr 8).toByte())
                digest.update(bytes.size.toByte())
                digest.update(bytes)
            }
            return CacheKey(
                digest.digest().joinToString(separator = "") {
                    (it.toInt() and 0xff).toString(16).padStart(2, '0')
                },
            )
        }

        /** Creates a namespaced key for local/generated content that has no remote path. */
        fun namespaced(namespace: String, logicalId: String, revision: String): CacheKey {
            require(namespace.isNotBlank()) { "namespace must not be blank" }
            require(logicalId.isNotBlank()) { "logicalId must not be blank" }
            return stable(
                serverProfileId = "local:${namespace.trim()}",
                canonicalPath = "/${logicalId.trimStart('/')}",
                revision = revision,
                representation = "opaque",
            )
        }

        internal fun fromDiskId(diskId: String): CacheKey? =
            diskId.takeIf(DISK_ID::matches)?.let(::CacheKey)

        internal fun normalizeRemotePath(path: String): String {
            val components = path
                .replace('\\', '/')
                .split('/')
                .filter(String::isNotEmpty)
            return if (components.isEmpty()) "/" else "/${components.joinToString("/")}"
        }
    }
}

/** Helpers for preferring immutable content hashes and falling back to file metadata. */
object CacheRevision {
    private val hashPreference = listOf("blake3", "sha256", "sha1", "md5")

    fun from(
        hashes: Map<String, String> = emptyMap(),
        modifiedAtMillis: Long? = null,
        sizeBytes: Long? = null,
    ): String {
        val normalizedHashes = hashes.entries
            .filter { it.key.isNotBlank() && it.value.isNotBlank() }
            .associate { it.key.trim().lowercase(Locale.ROOT) to it.value.trim().lowercase(Locale.ROOT) }
        val algorithm = hashPreference.firstOrNull(normalizedHashes::containsKey)
            ?: normalizedHashes.keys.sorted().firstOrNull()
        if (algorithm != null) return "hash:$algorithm:${normalizedHashes.getValue(algorithm)}"

        return if (modifiedAtMillis != null || sizeBytes != null) {
            "metadata:${modifiedAtMillis ?: "unknown"}:${sizeBytes ?: "unknown"}"
        } else {
            "unknown"
        }
    }
}

data class CacheEntrySnapshot(
    val key: CacheKey,
    val sizeBytes: Long,
    val createdAtMillis: Long,
    val lastAccessAtMillis: Long,
    val activeLeases: Int,
    val pendingRemoval: Boolean,
)

data class CacheStats(
    val totalBytes: Long,
    val entryCount: Int,
    val activeLeaseCount: Int,
    val inProgressWriteCount: Int,
    val expiredEntryCount: Int,
)

data class CacheTrimResult(
    val removedBytes: Long,
    val removedEntries: Int,
    val deferredEntries: Int,
    val bytesAfter: Long,
    val entriesAfter: Int,
)

/**
 * Serializes cache publications that must be followed by an aggregate policy trim.
 *
 * Backends remain independently usable in tests and tools. [UnifiedCacheManager] installs this
 * gate while it owns them so a newly published blob/span and the corresponding cross-backend trim
 * are one operation from the manager's point of view.
 */
internal fun interface CacheMutationGate {
    fun mutateAndTrim(mutation: () -> Unit)
}
