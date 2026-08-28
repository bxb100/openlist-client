package org.openlist.mobile.data.upload

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

data class StagedUpload(
    val file: File,
    val size: Long,
    val sha256: String,
) {
    val sourceIdentity: String = UploadIdentity.staged(sha256, size)
}

/**
 * Creates an immutable, app-private snapshot before any upload request is sent.
 *
 * The blob is published with a same-directory rename only after the complete source has been
 * copied, its exact size checked, its SHA-256 calculated, and the file synced to storage.
 */
class UploadStagingStore(
    private val directory: File,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun restore(key: String, expectedSize: Long? = null): StagedUpload? {
        require(key.isNotBlank()) { "key must not be blank" }
        require(expectedSize == null || expectedSize >= 0) { "expectedSize must not be negative" }
        val fileKey = sha256(key)
        return lockFor(fileKey).withLock {
            withContext(Dispatchers.IO) { restoreFiles(fileKey, expectedSize) }
        }
    }

    suspend fun stage(
        key: String,
        expectedSize: Long?,
        openSource: () -> InputStream,
    ): StagedUpload {
        require(key.isNotBlank()) { "key must not be blank" }
        require(expectedSize == null || expectedSize >= 0) { "expectedSize must not be negative" }
        val fileKey = sha256(key)
        return lockFor(fileKey).withLock {
            withContext(Dispatchers.IO) {
                check(directory.exists() || directory.mkdirs()) {
                    "无法创建上传暂存目录：$directory"
                }
                restoreFiles(fileKey, expectedSize)?.let { return@withContext it }

                removeFiles(fileKey)
                val temporary = File(directory, "$fileKey.${UUID.randomUUID()}.part")
                try {
                    val digest = MessageDigest.getInstance("SHA-256")
                    var copied = 0L
                    try {
                        openSource().use { input ->
                            FileOutputStream(temporary).use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    coroutineContext.ensureActive()
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    if (read == 0) continue
                                    copied += read
                                    if (expectedSize != null && copied > expectedSize) {
                                        throw UploadPermanentException(
                                            "源文件大小在暂存期间发生变化（预期 $expectedSize 字节）",
                                        )
                                    }
                                    output.write(buffer, 0, read)
                                    digest.update(buffer, 0, read)
                                }
                                if (expectedSize != null && copied != expectedSize) {
                                    throw UploadPermanentException(
                                        "源文件在暂存期间提前结束（预期 $expectedSize 字节，实际 $copied 字节）",
                                    )
                                }
                                output.fd.sync()
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (permanent: UploadPermanentException) {
                        throw permanent
                    } catch (error: Exception) {
                        throw UploadPermanentException("无法读取并暂存所选文件", error)
                    }

                    val blob = blobFile(fileKey)
                    check(temporary.renameTo(blob)) { "无法原子保存上传暂存文件：$blob" }
                    val staged = StagedUpload(
                        file = blob,
                        size = copied,
                        sha256 = digest.digest().toHex(),
                    )
                    writeMetadata(fileKey, staged)
                    staged
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (permanent: UploadPermanentException) {
                    throw permanent
                } catch (error: Exception) {
                    throw UploadPermanentException("无法创建上传暂存文件", error)
                } finally {
                    if (temporary.exists()) temporary.delete()
                }
            }
        }
    }

    suspend fun remove(key: String) {
        if (key.isBlank()) return
        val fileKey = sha256(key)
        lockFor(fileKey).withLock {
            withContext(Dispatchers.IO) { removeFiles(fileKey) }
        }
    }

    suspend fun pruneOlderThan(
        cutoffMillis: Long,
        maxEntries: Int,
        excludingKeys: Set<String> = emptySet(),
    ): Int {
        require(maxEntries >= 0) { "maxEntries must not be negative" }
        if (maxEntries == 0) return 0
        val excludedFileKeys = excludingKeys.mapTo(HashSet(), ::sha256)
        return withContext(Dispatchers.IO) {
            val groups = directory.listFiles().orEmpty()
                .mapNotNull { file ->
                    STAGING_FILE_REGEX.matchEntire(file.name)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.let { fileKey -> fileKey to file }
                }
                .groupBy({ it.first }, { it.second })
            val candidates = groups.asSequence()
                .filterNot { (fileKey, _) -> fileKey in excludedFileKeys }
                .map { (fileKey, files) -> fileKey to (files.maxOfOrNull(File::lastModified) ?: 0L) }
                .filter { (_, lastModified) -> lastModified < cutoffMillis }
                .sortedBy { (_, lastModified) -> lastModified }
                .take(maxEntries)
                .toList()
            for ((fileKey, _) in candidates) {
                lockFor(fileKey).withLock { removeFiles(fileKey) }
            }
            candidates.size
        }
    }

    private fun restoreFiles(fileKey: String, expectedSize: Long?): StagedUpload? {
        val blob = blobFile(fileKey)
        if (!blob.isFile) return null
        val actualSize = blob.length()
        if (expectedSize != null && actualSize != expectedSize) return null

        val metadata = metadataFile(fileKey)
            .takeIf(File::isFile)
            ?.let(::readMetadata)
        if (metadata != null && metadata.size == actualSize) {
            touchFiles(fileKey)
            return StagedUpload(blob, metadata.size, metadata.sha256)
        }

        // A crash can occur after publishing the blob but before publishing metadata. The blob is
        // already a complete immutable snapshot, so recover it without reopening the provider.
        val recovered = StagedUpload(blob, actualSize, digestFile(blob))
        writeMetadata(fileKey, recovered)
        touchFiles(fileKey)
        return recovered
    }

    private fun writeMetadata(fileKey: String, staged: StagedUpload) {
        val target = metadataFile(fileKey)
        val temporary = File(directory, "$fileKey.${UUID.randomUUID()}.meta.part")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(
                    "$METADATA_VERSION\n${staged.size}\n${staged.sha256}\n"
                        .toByteArray(Charsets.UTF_8),
                )
                output.fd.sync()
            }
            if (target.exists() && !target.delete()) {
                error("无法更新上传暂存元数据：$target")
            }
            check(temporary.renameTo(target)) { "无法保存上传暂存元数据：$target" }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun readMetadata(file: File): StagingMetadata? = runCatching {
        val lines = file.readLines(Charsets.UTF_8)
        val version = lines.getOrNull(0)?.toIntOrNull()
        val size = lines.getOrNull(1)?.toLongOrNull()
        val digest = lines.getOrNull(2)?.lowercase()
        if (version != METADATA_VERSION || size == null || size < 0 ||
            digest == null || !SHA256_REGEX.matches(digest)
        ) {
            null
        } else {
            StagingMetadata(size, digest)
        }
    }.getOrNull()

    private fun digestFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun removeFiles(fileKey: String) {
        val candidates = directory.listFiles().orEmpty().filter { file ->
            file.name == "$fileKey.blob" ||
                file.name == "$fileKey.meta" ||
                (file.name.startsWith("$fileKey.") && file.name.endsWith(".part"))
        }
        candidates.forEach { file ->
            if (file.exists() && !file.delete()) error("无法删除上传暂存文件：$file")
        }
    }

    private fun touchFiles(fileKey: String) {
        val now = nowMillis()
        blobFile(fileKey).takeIf(File::exists)?.setLastModified(now)
        metadataFile(fileKey).takeIf(File::exists)?.setLastModified(now)
    }

    private fun blobFile(fileKey: String) = File(directory, "$fileKey.blob")

    private fun metadataFile(fileKey: String) = File(directory, "$fileKey.meta")

    private data class StagingMetadata(val size: Long, val sha256: String)

    companion object {
        private const val METADATA_VERSION = 1
        private val SHA256_REGEX = Regex("[0-9a-f]{64}")
        private val STAGING_FILE_REGEX = Regex("([0-9a-f]{64})\\..+")
        private val locks = ConcurrentHashMap<String, Mutex>()

        private fun lockFor(fileKey: String): Mutex = locks.getOrPut(fileKey) { Mutex() }

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .toHex()

        private fun ByteArray.toHex(): String =
            joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    }
}
