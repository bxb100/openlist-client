package org.openlist.mobile.data.upload

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

interface UploadCheckpointStore {
    suspend fun load(key: String): UploadCheckpoint?

    suspend fun save(checkpoint: UploadCheckpoint)

    suspend fun remove(key: String)
}

class JsonUploadCheckpointStore(
    private val directory: File,
    private val gson: Gson = Gson(),
) : UploadCheckpointStore {
    override suspend fun load(key: String): UploadCheckpoint? = withKeyLock(key) {
        withContext(Dispatchers.IO) {
            val file = fileFor(key)
            if (!file.isFile) return@withContext null
            runCatching { gson.fromJson(file.readText(Charsets.UTF_8), UploadCheckpoint::class.java) }
                .getOrNull()
                ?.takeIf { it.key == key }
        }
    }

    override suspend fun save(checkpoint: UploadCheckpoint) = withKeyLock(checkpoint.key) {
        withContext(Dispatchers.IO) {
            check(directory.exists() || directory.mkdirs()) {
                "无法创建上传断点目录：$directory"
            }
            val target = fileFor(checkpoint.key)
            val temporary = File(directory, "${target.name}.${System.nanoTime()}.tmp")
            try {
                FileOutputStream(temporary).use { stream ->
                    stream.write(gson.toJson(checkpoint).toByteArray(Charsets.UTF_8))
                    stream.fd.sync()
                }
                if (target.exists() && !target.delete()) {
                    error("无法更新上传断点：$target")
                }
                check(temporary.renameTo(target)) { "无法保存上传断点：$target" }
            } finally {
                if (temporary.exists()) temporary.delete()
            }
        }
    }

    override suspend fun remove(key: String) = withKeyLock(key) {
        withContext(Dispatchers.IO) {
            val file = fileFor(key)
            if (file.exists() && !file.delete()) error("无法删除上传断点：$file")
        }
    }

    suspend fun pruneOlderThan(cutoffMillis: Long, maxEntries: Int): Int {
        require(maxEntries >= 0) { "maxEntries must not be negative" }
        if (maxEntries == 0) return 0
        return withContext(Dispatchers.IO) {
            val candidates = directory.listFiles().orEmpty()
                .asSequence()
                .filter { file ->
                    file.isFile && (file.name.endsWith(".json") || file.name.endsWith(".tmp"))
                }
                .map { file ->
                    val updatedAt = if (file.name.endsWith(".json")) {
                        runCatching {
                            gson.fromJson(file.readText(Charsets.UTF_8), UploadCheckpoint::class.java)
                                ?.updatedAtMillis
                        }.getOrNull() ?: file.lastModified()
                    } else {
                        file.lastModified()
                    }
                    file to updatedAt
                }
                .filter { (_, updatedAt) -> updatedAt < cutoffMillis }
                .sortedBy { (_, updatedAt) -> updatedAt }
                .take(maxEntries)
                .toList()
            candidates.forEach { (file, _) ->
                if (file.exists() && !file.delete()) error("无法删除过期上传断点：$file")
            }
            candidates.size
        }
    }

    private suspend fun <T> withKeyLock(key: String, block: suspend () -> T): T =
        locks.getOrPut("${directory.absolutePath}\u0000$key") { Mutex() }.withLock { block() }

    private fun fileFor(key: String): File = File(directory, "${sha256(key)}.json")

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        // Worker and cold-start reconciliation use different store instances for the same files.
        val locks = ConcurrentHashMap<String, Mutex>()
    }
}

class InMemoryUploadCheckpointStore : UploadCheckpointStore {
    private val mutex = Mutex()
    private val checkpoints = mutableMapOf<String, UploadCheckpoint>()

    override suspend fun load(key: String): UploadCheckpoint? = mutex.withLock { checkpoints[key] }

    override suspend fun save(checkpoint: UploadCheckpoint) {
        mutex.withLock { checkpoints[checkpoint.key] = checkpoint }
    }

    override suspend fun remove(key: String) {
        mutex.withLock { checkpoints.remove(key) }
    }
}
