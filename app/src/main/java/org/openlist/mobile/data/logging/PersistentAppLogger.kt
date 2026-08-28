package org.openlist.mobile.data.logging

import com.google.gson.Gson
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Thread-safe bounded JSON-lines logger. All values are redacted before entering memory or disk.
 * Disk failures degrade to the same bounded in-memory log instead of crashing the application.
 */
class PersistentAppLogger(
    val directory: File,
    private val retention: LogRetention = LogRetention(),
    private val clock: LogClock = SystemLogClock,
    private val gson: Gson = Gson(),
) : AppLogger {
    private val lock = ReentrantLock()
    private val logFile = File(directory, LOG_FILE_NAME)
    private val partFile = File(directory, PART_FILE_NAME)
    private val backupFile = File(directory, BACKUP_FILE_NAME)
    private val mutableLogs = MutableStateFlow<List<LogEntry>>(emptyList())
    private val mutablePersistenceHealthy = MutableStateFlow(true)
    private var nextId = 1L

    override val logs: StateFlow<List<LogEntry>> = mutableLogs.asStateFlow()
    override val persistenceHealthy: StateFlow<Boolean> = mutablePersistenceHealthy.asStateFlow()

    init {
        lock.withLock {
            val loaded = runCatching {
                prepareDirectoryAndRecover()
                loadFromDisk()
            }.onFailure {
                mutablePersistenceHealthy.value = false
            }.getOrDefault(LoadResult(emptyList(), needsRewrite = false))

            val bounded = trimToBounds(loaded.entries)
            mutableLogs.value = bounded.entries
            nextId = bounded.entries.maxOfOrNull(LogEntry::id)?.let(::nextIdAfter) ?: 1L
            if (loaded.needsRewrite || bounded.wasTrimmed) {
                persistRewriteSafely(bounded.entries)
            }
        }
    }

    override fun log(
        level: LogLevel,
        module: String,
        message: String,
        throwable: Throwable?,
    ): LogEntry = lock.withLock {
        val safeModule = LogSanitizer.redact(module)
            .replace('\n', ' ')
            .replace('\r', ' ')
            .take(retention.maxModuleChars)
            .ifBlank { DEFAULT_MODULE }
        val rawMessage = message.take(LogSanitizer.MAX_INPUT_CHARS)
        val combinedMessage = if (throwable == null) rawMessage else buildString {
            append(rawMessage)
            if (isNotEmpty()) append('\n')
            val remaining = (LogSanitizer.MAX_INPUT_CHARS - length).coerceAtLeast(0)
            append(throwable.stackTraceToString().take(remaining))
        }
        val safeMessage = LogSanitizer.redact(combinedMessage).take(retention.maxMessageChars)
        val entry = LogEntry(
            id = nextId,
            timestampMillis = clock.nowMillis(),
            level = level,
            module = safeModule,
            message = safeMessage,
        )
        nextId = nextIdAfter(nextId)

        val previous = mutableLogs.value
        val bounded = trimToBounds(previous + entry)
        mutableLogs.value = bounded.entries
        if (!bounded.wasTrimmed && bounded.entries.size == previous.size + 1) {
            persistAppendSafely(entry)
        } else {
            persistRewriteSafely(bounded.entries)
        }
        entry
    }

    override fun clear() = lock.withLock {
        mutableLogs.value = emptyList()
        nextId = 1L
        persistRewriteSafely(emptyList())
    }

    private fun prepareDirectoryAndRecover() {
        if (directory.exists() && !directory.isDirectory) {
            error("Log path is not a directory")
        }
        if (!directory.exists() && !directory.mkdirs()) {
            error("Unable to create log directory")
        }

        when {
            logFile.exists() -> {
                partFile.delete()
                backupFile.delete()
            }
            backupFile.exists() -> {
                backupFile.renameTo(logFile)
                partFile.delete()
            }
            partFile.exists() -> partFile.renameTo(logFile)
        }
    }

    private fun loadFromDisk(): LoadResult {
        if (!logFile.isFile || logFile.length() == 0L) return LoadResult(emptyList(), false)
        val entries = mutableListOf<LogEntry>()
        var needsRewrite = logFile.length() > retention.maxFileBytes
        val startOffset = (logFile.length() - retention.maxFileBytes).coerceAtLeast(0L)

        FileInputStream(logFile).use { input ->
            input.channel.position(startOffset)
            input.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                if (startOffset > 0L) {
                    reader.readLine()
                    needsRewrite = true
                }
                reader.lineSequence().forEach { line ->
                    if (line.isBlank()) return@forEach
                    val stored = runCatching { gson.fromJson(line, StoredLog::class.java) }.getOrNull()
                    val level = stored?.level?.let { raw ->
                        runCatching { LogLevel.valueOf(raw) }.getOrNull()
                    }
                    if (stored == null || stored.version != FORMAT_VERSION || level == null) {
                        needsRewrite = true
                        return@forEach
                    }
                    val safeModule = LogSanitizer.redact(stored.module.orEmpty())
                        .replace('\n', ' ')
                        .replace('\r', ' ')
                        .take(retention.maxModuleChars)
                        .ifBlank { DEFAULT_MODULE }
                    val safeMessage = LogSanitizer.redact(stored.message.orEmpty())
                        .take(retention.maxMessageChars)
                    if (safeModule != stored.module || safeMessage != stored.message) needsRewrite = true
                    entries += LogEntry(
                        id = stored.id,
                        timestampMillis = stored.timestampMillis,
                        level = level,
                        module = safeModule,
                        message = safeMessage,
                    )
                }
            }
        }
        return LoadResult(entries, needsRewrite)
    }

    private fun trimToBounds(entries: List<LogEntry>): BoundedEntries {
        var bounded = entries.takeLast(retention.maxEntries)
        var serialized = bounded.map(::serialize)
        var totalBytes = serialized.fold(0L) { total, line -> total + line.size }
        while (bounded.isNotEmpty() && totalBytes > retention.maxFileBytes) {
            totalBytes -= serialized.first().size
            bounded = bounded.drop(1)
            serialized = serialized.drop(1)
        }
        return BoundedEntries(
            entries = bounded,
            wasTrimmed = bounded.size != entries.size,
        )
    }

    private fun persistAppendSafely(entry: LogEntry) {
        if (!mutablePersistenceHealthy.value || (!logFile.exists() && mutableLogs.value.size > 1)) {
            persistRewriteSafely(mutableLogs.value)
            return
        }
        val bytes = serialize(entry)
        val canAppend = runCatching {
            prepareDirectoryAndRecover()
            logFile.length() + bytes.size <= retention.maxFileBytes
        }.getOrDefault(false)
        if (!canAppend) {
            persistRewriteSafely(mutableLogs.value)
            return
        }

        runCatching {
            FileOutputStream(logFile, true).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
        }.onSuccess {
            mutablePersistenceHealthy.value = true
        }.onFailure {
            mutablePersistenceHealthy.value = false
        }
    }

    private fun persistRewriteSafely(entries: List<LogEntry>) {
        runCatching {
            prepareDirectoryAndRecover()
            FileOutputStream(partFile).use { fileOutput ->
                BufferedOutputStream(fileOutput).use { output ->
                    entries.forEach { output.write(serialize(it)) }
                    output.flush()
                    fileOutput.fd.sync()
                }
            }

            if (backupFile.exists() && !backupFile.delete()) error("Unable to replace log backup")
            if (logFile.exists() && !logFile.renameTo(backupFile)) error("Unable to stage old log")
            if (!partFile.renameTo(logFile)) {
                backupFile.renameTo(logFile)
                error("Unable to publish compacted log")
            }
            backupFile.delete()
        }.onSuccess {
            mutablePersistenceHealthy.value = true
        }.onFailure {
            mutablePersistenceHealthy.value = false
        }
    }

    private fun serialize(entry: LogEntry): ByteArray {
        val stored = StoredLog(
            id = entry.id,
            timestampMillis = entry.timestampMillis,
            level = entry.level.name,
            module = entry.module,
            message = entry.message,
        )
        return (gson.toJson(stored) + "\n").toByteArray(StandardCharsets.UTF_8)
    }

    private data class StoredLog(
        val version: Int = FORMAT_VERSION,
        val id: Long = 0,
        val timestampMillis: Long = 0,
        val level: String? = null,
        val module: String? = null,
        val message: String? = null,
    )

    private data class LoadResult(
        val entries: List<LogEntry>,
        val needsRewrite: Boolean,
    )

    private data class BoundedEntries(
        val entries: List<LogEntry>,
        val wasTrimmed: Boolean,
    )

    private companion object {
        const val FORMAT_VERSION = 1
        const val LOG_FILE_NAME = "application.log"
        const val PART_FILE_NAME = "application.log.part"
        const val BACKUP_FILE_NAME = "application.log.backup"
        const val DEFAULT_MODULE = "app"

        fun nextIdAfter(current: Long): Long = if (current == Long.MAX_VALUE) 1L else current + 1L
    }
}
