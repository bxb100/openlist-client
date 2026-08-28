package org.openlist.mobile.data.cache

import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import org.openlist.mobile.core.model.CachePolicy

/**
 * A process-local, filesystem-backed cache for complete media and image blobs.
 *
 * Each completed value is published by renaming a `.part` file in the same directory. Metadata is
 * committed afterwards; process termination between the two renames leaves an unindexed blob which
 * is removed during the next startup reconciliation. This is not a power-loss durability promise:
 * directory entries are not fsynced. A single instance must own a directory at a time.
 */
class ManagedDiskCache(
    val directory: File,
    initialPolicy: CachePolicy,
    private val clock: CacheClock = SystemCacheClock,
) : CacheCoordinator {
    private val monitor = Any()
    private val records = mutableMapOf<String, EntryRecord>()
    private val writes = mutableMapOf<String, WriteReservation>()
    private var currentPolicy = initialPolicy
    private var clearEpoch = 0L
    private var closed = false

    @Volatile
    private var mutationGate: CacheMutationGate? = null

    val startupOrphansRemoved: Int

    init {
        prepareDirectory()
        startupOrphansRemoved = synchronized(monitor) {
            var removed = removeTemporaryFilesLocked()
            removed += loadMetadataLocked()
            removed += removeUnindexedFilesLocked()
            trimLocked()
            removed
        }
    }

    override val policy: CachePolicy
        get() = synchronized(monitor) { currentPolicy }

    override fun updatePolicy(policy: CachePolicy): CacheTrimResult = synchronized(monitor) {
        ensureOpenLocked()
        currentPolicy = policy
        // A policy reduction must also stop already-open writers. The output wrapper accounts
        // bytes under this same monitor, so a `.part` file can never grow past the policy that was
        // current when those bytes were accepted.
        writes.values
            .filter { !policy.acceptsWrites || it.writtenBytes > policy.maxBytes }
            .forEach { reservation ->
                reservation.invalidated = true
                reservation.partFile.delete()
            }
        trimLocked()
    }

    override fun acquire(key: CacheKey): CacheLease? = synchronized(monitor) {
        ensureOpenLocked()
        val record = records[key.diskId] ?: return@synchronized null
        if (!record.file.isFile || record.file.length() != record.sizeBytes) {
            record.pendingRemoval = true
            if (record.activeLeases == 0) deleteRecordLocked(record)
            return@synchronized null
        }
        if (record.pendingRemoval || isExpiredLocked(record)) {
            record.pendingRemoval = true
            if (record.activeLeases == 0) deleteRecordLocked(record)
            return@synchronized null
        }

        record.lastAccessAtMillis = clock.nowMillis()
        // The blob mtime is the persistent sliding-TTL journal. Failure is harmless for this
        // process; the in-memory timestamp remains authoritative until restart.
        record.file.setLastModified(record.lastAccessAtMillis)
        record.activeLeases += 1
        val snapshot = record.snapshot()
        CacheLease(entry = snapshot, file = record.file) { release(key.diskId) }
    }

    override fun beginWrite(key: CacheKey, expectedBytes: Long?): CacheWriteSession? {
        require(expectedBytes == null || expectedBytes >= 0) { "expectedBytes must not be negative" }

        val reservation = synchronized(monitor) {
            ensureOpenLocked()
            val policy = currentPolicy
            if (!policy.acceptsWrites || expectedBytes == 0L ||
                (expectedBytes != null && expectedBytes > policy.maxBytes)
            ) {
                return@synchronized null
            }
            if (records.containsKey(key.diskId) || writes.containsKey(key.diskId)) {
                return@synchronized null
            }

            val partFile = partFile(key.diskId)
            val staleFiles = listOf(partFile, blobFile(key.diskId), metadataFile(key.diskId), metadataPartFile(key.diskId))
            if (staleFiles.any { it.exists() && !it.delete() }) {
                throw IOException("Unable to remove stale cache files for ${key.diskId}")
            }
            WriteReservation(
                key = key,
                partFile = partFile,
                expectedBytes = expectedBytes,
                epoch = clearEpoch,
            ).also { writes[key.diskId] = it }
        } ?: return null

        return try {
            DiskWriteSession(this, reservation)
        } catch (error: Throwable) {
            abortWrite(reservation)
            throw error
        }
    }

    /** Convenience for complete blobs; partial or failed writers never become visible. */
    fun put(
        key: CacheKey,
        expectedBytes: Long? = null,
        writer: (OutputStream) -> Unit,
    ): Boolean {
        val session = beginWrite(key, expectedBytes) ?: return false
        return try {
            writer(session.outputStream)
            session.commit()
        } catch (_: CacheWriteRejectedException) {
            session.abort()
            false
        } catch (error: Throwable) {
            session.abort()
            throw error
        }
    }

    override fun stats(): CacheStats = synchronized(monitor) {
        ensureOpenLocked()
        statsLocked()
    }

    override fun entries(): List<CacheEntrySnapshot> = synchronized(monitor) {
        ensureOpenLocked()
        records.values
            .sortedWith(compareBy<EntryRecord> { it.lastAccessAtMillis }.thenBy { it.key.diskId })
            .map(EntryRecord::snapshot)
    }

    /** Returns true when neither a committed value nor a staged write remains immediately. */
    override fun remove(key: CacheKey): Boolean = synchronized(monitor) {
        ensureOpenLocked()
        writes[key.diskId]?.invalidated = true
        val record = records[key.diskId]
        if (record != null) {
            record.pendingRemoval = true
            if (record.activeLeases == 0) deleteRecordLocked(record)
        }
        !records.containsKey(key.diskId) && !writes.containsKey(key.diskId)
    }

    override fun trim(): CacheTrimResult = synchronized(monitor) {
        ensureOpenLocked()
        trimLocked()
    }

    override fun clear(): CacheTrimResult = synchronized(monitor) {
        ensureOpenLocked()
        clearEpoch += 1
        writes.values.forEach { it.invalidated = true }

        var removedBytes = 0L
        var removedEntries = 0
        records.values.toList().forEach { record ->
            record.pendingRemoval = true
            if (record.activeLeases == 0 && deleteRecordLocked(record)) {
                removedBytes = saturatingAdd(removedBytes, record.sizeBytes)
                removedEntries += 1
            }
        }
        removeTemporaryFilesLocked(excluding = writes.values.mapTo(mutableSetOf()) { it.partFile.name })
        val stats = statsLocked()
        CacheTrimResult(
            removedBytes = removedBytes,
            removedEntries = removedEntries,
            deferredEntries = records.values.count(EntryRecord::pendingRemoval),
            bytesAfter = stats.totalBytes,
            entriesAfter = stats.entryCount,
        )
    }

    override fun close() = synchronized(monitor) {
        if (closed) return@synchronized
        closed = true
        clearEpoch += 1
        writes.values.forEach { it.invalidated = true }
        // Open output streams are owned by their sessions. Their `.part` files are either removed
        // when those sessions close, or reconciled at the next process start.
        writes.clear()
    }

    internal fun attachMutationGate(gate: CacheMutationGate?) {
        mutationGate = gate
    }

    private fun release(diskId: String) = synchronized(monitor) {
        val record = records[diskId] ?: return@synchronized
        check(record.activeLeases > 0) { "Unbalanced cache lease release" }
        record.activeLeases -= 1
        if (record.activeLeases == 0 && record.pendingRemoval) {
            deleteRecordLocked(record)
        }
        if (!closed) trimLocked()
    }

    private fun commitWrite(reservation: WriteReservation): Boolean {
        var committed = false
        val mutation = {
            committed = commitWriteLocked(reservation)
        }
        mutationGate?.mutateAndTrim(mutation) ?: mutation()
        return committed && synchronized(monitor) {
            records[reservation.key.diskId]?.pendingRemoval == false
        }
    }

    private fun commitWriteLocked(reservation: WriteReservation): Boolean = synchronized(monitor) {
        val registered = writes[reservation.key.diskId]
        if (registered !== reservation) {
            reservation.partFile.delete()
            return@synchronized false
        }
        writes.remove(reservation.key.diskId)

        val sizeBytes = reservation.partFile.length()
        if (closed || reservation.invalidated || reservation.epoch != clearEpoch ||
            !currentPolicy.acceptsWrites || sizeBytes <= 0L || sizeBytes > currentPolicy.maxBytes ||
            reservation.expectedBytes?.let { expected -> sizeBytes != expected } == true
        ) {
            reservation.partFile.delete()
            return@synchronized false
        }

        val target = blobFile(reservation.key.diskId)
        val metadata = metadataFile(reservation.key.diskId)
        if (records.containsKey(reservation.key.diskId) || target.exists() || metadata.exists()) {
            reservation.partFile.delete()
            return@synchronized false
        }

        val now = clock.nowMillis()
        try {
            if (!reservation.partFile.renameTo(target)) {
                throw IOException("Unable to atomically publish ${reservation.key.diskId}")
            }
            target.setLastModified(now)
            writeMetadataLocked(
                key = reservation.key,
                sizeBytes = sizeBytes,
                createdAtMillis = now,
                lastAccessAtMillis = now,
            )
        } catch (error: Throwable) {
            reservation.partFile.delete()
            target.delete()
            metadata.delete()
            metadataPartFile(reservation.key.diskId).delete()
            throw error
        }

        records[reservation.key.diskId] = EntryRecord(
            key = reservation.key,
            file = target,
            sizeBytes = sizeBytes,
            createdAtMillis = now,
            lastAccessAtMillis = now,
        )
        trimLocked()
        records[reservation.key.diskId]?.pendingRemoval == false
    }

    private fun abortWrite(reservation: WriteReservation) = synchronized(monitor) {
        if (writes[reservation.key.diskId] === reservation) {
            writes.remove(reservation.key.diskId)
        }
        reservation.partFile.delete()
    }

    /**
     * Accounts and writes one chunk atomically with policy updates. Rejecting the complete chunk
     * (instead of truncating it) keeps the staged object unambiguously incomplete.
     */
    private fun writeChunk(
        reservation: WriteReservation,
        byteCount: Int,
        write: () -> Unit,
    ) = synchronized(monitor) {
        ensureOpenLocked()
        val registered = writes[reservation.key.diskId]
        if (registered !== reservation || reservation.invalidated || reservation.epoch != clearEpoch) {
            throw CacheWriteRejectedException("Cache write is no longer active")
        }
        if (!currentPolicy.acceptsWrites) {
            reservation.invalidated = true
            throw CacheWriteRejectedException("Cache writes are disabled by the current policy")
        }

        val hardLimit = minOf(currentPolicy.maxBytes, reservation.expectedBytes ?: Long.MAX_VALUE)
        val remaining = (hardLimit - reservation.writtenBytes).coerceAtLeast(0L)
        if (byteCount.toLong() > remaining) {
            reservation.invalidated = true
            throw CacheWriteRejectedException("Cache entry exceeds its declared or configured size limit")
        }

        write()
        reservation.writtenBytes += byteCount.toLong()
    }

    private fun trimLocked(): CacheTrimResult {
        var removedBytes = 0L
        var removedEntries = 0
        var projectedBytes = records.values.fold(0L) { total, entry ->
            saturatingAdd(total, entry.sizeBytes)
        }
        var projectedEntries = records.size

        fun selectForRemoval(record: EntryRecord) {
            if (!records.containsKey(record.key.diskId) || record.pendingRemoval) return
            record.pendingRemoval = true
            projectedBytes = (projectedBytes - record.sizeBytes).coerceAtLeast(0L)
            projectedEntries = (projectedEntries - 1).coerceAtLeast(0)
            if (record.activeLeases == 0 && deleteRecordLocked(record)) {
                removedBytes = saturatingAdd(removedBytes, record.sizeBytes)
                removedEntries += 1
            }
        }

        records.values.toList().filter(EntryRecord::pendingRemoval).forEach { record ->
            projectedBytes = (projectedBytes - record.sizeBytes).coerceAtLeast(0L)
            projectedEntries = (projectedEntries - 1).coerceAtLeast(0)
            if (record.activeLeases == 0 && deleteRecordLocked(record)) {
                removedBytes = saturatingAdd(removedBytes, record.sizeBytes)
                removedEntries += 1
            }
        }

        records.values
            .filter { !it.pendingRemoval && isExpiredLocked(it) }
            .sortedWith(ENTRY_LRU_ORDER)
            .forEach(::selectForRemoval)

        for (record in records.values.filter { !it.pendingRemoval }.sortedWith(ENTRY_LRU_ORDER)) {
            if (projectedBytes <= currentPolicy.maxBytes && projectedEntries <= currentPolicy.maxEntries) break
            selectForRemoval(record)
        }

        val stats = statsLocked()
        return CacheTrimResult(
            removedBytes = removedBytes,
            removedEntries = removedEntries,
            deferredEntries = records.values.count(EntryRecord::pendingRemoval),
            bytesAfter = stats.totalBytes,
            entriesAfter = stats.entryCount,
        )
    }

    private fun isExpiredLocked(record: EntryRecord): Boolean {
        val maxAge = currentPolicy.maxAgeMillis
        if (maxAge == Long.MAX_VALUE) return false
        val now = clock.nowMillis()
        if (now < record.lastAccessAtMillis) return false
        return now - record.lastAccessAtMillis >= maxAge
    }

    private fun statsLocked(): CacheStats = CacheStats(
        totalBytes = records.values.fold(0L) { total, entry -> saturatingAdd(total, entry.sizeBytes) },
        entryCount = records.size,
        activeLeaseCount = records.values.sumOf(EntryRecord::activeLeases),
        inProgressWriteCount = writes.size,
        expiredEntryCount = records.values.count { isExpiredLocked(it) },
    )

    private fun deleteRecordLocked(record: EntryRecord): Boolean {
        check(record.activeLeases == 0) { "Cannot remove a leased cache entry" }
        val blobRemoved = !record.file.exists() || record.file.delete()
        if (!blobRemoved) return false

        metadataFile(record.key.diskId).delete()
        metadataPartFile(record.key.diskId).delete()
        records.remove(record.key.diskId)
        return true
    }

    private fun prepareDirectory() {
        if (directory.exists() && !directory.isDirectory) {
            throw IOException("Cache path is not a directory: $directory")
        }
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Unable to create cache directory: $directory")
        }
    }

    private fun loadMetadataLocked(): Int {
        var removed = 0
        directory.listFiles()
            .orEmpty()
            .filter { it.name.endsWith(METADATA_SUFFIX) }
            .forEach { metadata ->
                val diskId = metadata.name.removeSuffix(METADATA_SUFFIX)
                val key = CacheKey.fromDiskId(diskId)
                val blob = blobFile(diskId)
                val persisted = key?.let { readMetadata(metadata, it) }
                if (key == null || persisted == null || !blob.isFile || blob.length() <= 0L ||
                    blob.length() != persisted.sizeBytes
                ) {
                    if (metadata.delete()) removed += 1
                    if (blob.delete()) removed += 1
                    return@forEach
                }

                val fileTimestamp = blob.lastModified()
                records[diskId] = EntryRecord(
                    key = key,
                    file = blob,
                    sizeBytes = persisted.sizeBytes,
                    createdAtMillis = persisted.createdAtMillis,
                    lastAccessAtMillis = if (fileTimestamp > 0L) fileTimestamp else persisted.lastAccessAtMillis,
                )
            }
        return removed
    }

    private fun readMetadata(file: File, expectedKey: CacheKey): PersistedMetadata? = runCatching {
        DataInputStream(FileInputStream(file).buffered()).use { input ->
            if (input.readInt() != METADATA_MAGIC) return@runCatching null
            if (input.readInt() != METADATA_VERSION) return@runCatching null
            if (input.readUTF() != expectedKey.diskId) return@runCatching null
            val sizeBytes = input.readLong()
            val createdAtMillis = input.readLong()
            val lastAccessAtMillis = input.readLong()
            if (sizeBytes <= 0L) return@runCatching null
            PersistedMetadata(sizeBytes, createdAtMillis, lastAccessAtMillis)
        }
    }.getOrNull()

    private fun writeMetadataLocked(
        key: CacheKey,
        sizeBytes: Long,
        createdAtMillis: Long,
        lastAccessAtMillis: Long,
    ) {
        val part = metadataPartFile(key.diskId)
        val target = metadataFile(key.diskId)
        if (part.exists() && !part.delete()) throw IOException("Unable to replace $part")

        val fileOutput = FileOutputStream(part)
        try {
            DataOutputStream(BufferedOutputStream(fileOutput)).use { output ->
                output.writeInt(METADATA_MAGIC)
                output.writeInt(METADATA_VERSION)
                output.writeUTF(key.diskId)
                output.writeLong(sizeBytes)
                output.writeLong(createdAtMillis)
                output.writeLong(lastAccessAtMillis)
                output.flush()
                fileOutput.fd.sync()
            }
            if (!part.renameTo(target)) throw IOException("Unable to publish metadata for ${key.diskId}")
        } catch (error: Throwable) {
            part.delete()
            throw error
        }
    }

    private fun removeTemporaryFilesLocked(excluding: Set<String> = emptySet()): Int {
        var removed = 0
        directory.listFiles().orEmpty()
            .filter { it.name.endsWith(PART_SUFFIX) && it.name !in excluding }
            .forEach { if (it.delete()) removed += 1 }
        return removed
    }

    private fun removeUnindexedFilesLocked(): Int {
        var removed = 0
        val indexedIds = records.keys
        directory.listFiles().orEmpty().forEach { file ->
            val diskId = when {
                file.name.endsWith(BLOB_SUFFIX) -> file.name.removeSuffix(BLOB_SUFFIX)
                file.name.endsWith(METADATA_SUFFIX) -> file.name.removeSuffix(METADATA_SUFFIX)
                else -> return@forEach
            }
            if (diskId !in indexedIds && file.delete()) removed += 1
        }
        return removed
    }

    private fun ensureOpenLocked() {
        check(!closed) { "Cache is closed" }
    }

    private fun blobFile(diskId: String) = File(directory, "$diskId$BLOB_SUFFIX")

    private fun partFile(diskId: String) = File(directory, "$diskId$PART_SUFFIX")

    private fun metadataFile(diskId: String) = File(directory, "$diskId$METADATA_SUFFIX")

    private fun metadataPartFile(diskId: String) = File(directory, "$diskId$METADATA_SUFFIX$PART_SUFFIX")

    private data class EntryRecord(
        val key: CacheKey,
        val file: File,
        val sizeBytes: Long,
        val createdAtMillis: Long,
        var lastAccessAtMillis: Long,
        var activeLeases: Int = 0,
        var pendingRemoval: Boolean = false,
    ) {
        fun snapshot() = CacheEntrySnapshot(
            key = key,
            sizeBytes = sizeBytes,
            createdAtMillis = createdAtMillis,
            lastAccessAtMillis = lastAccessAtMillis,
            activeLeases = activeLeases,
            pendingRemoval = pendingRemoval,
        )
    }

    private data class WriteReservation(
        val key: CacheKey,
        val partFile: File,
        val expectedBytes: Long?,
        val epoch: Long,
        var invalidated: Boolean = false,
        var writtenBytes: Long = 0L,
    )

    private data class PersistedMetadata(
        val sizeBytes: Long,
        val createdAtMillis: Long,
        val lastAccessAtMillis: Long,
    )

    private class CacheWriteRejectedException(message: String) : IOException(message)

    private class DiskWriteSession(
        private val owner: ManagedDiskCache,
        private val reservation: WriteReservation,
    ) : CacheWriteSession {
        private enum class State { OPEN, COMMITTED, ABORTED }

        private val sessionMonitor = Any()
        private val stagedOutput = DurableFileOutputStream(owner, reservation)
        override val outputStream: OutputStream = stagedOutput
        private var state = State.OPEN

        override fun commit(): Boolean = synchronized(sessionMonitor) {
            when (state) {
                State.COMMITTED -> return@synchronized true
                State.ABORTED -> return@synchronized false
                State.OPEN -> Unit
            }

            try {
                stagedOutput.close()
            } catch (error: Throwable) {
                state = State.ABORTED
                owner.abortWrite(reservation)
                throw error
            }

            val committed = try {
                owner.commitWrite(reservation)
            } catch (error: Throwable) {
                state = State.ABORTED
                throw error
            }
            state = if (committed) State.COMMITTED else State.ABORTED
            committed
        }

        override fun abort() = synchronized(sessionMonitor) {
            if (state != State.OPEN) return@synchronized
            state = State.ABORTED
            runCatching { stagedOutput.close() }
            owner.abortWrite(reservation)
        }
    }

    /** Idempotent close lets callers use `outputStream.use {}` before committing the session. */
    private class DurableFileOutputStream(
        private val owner: ManagedDiskCache,
        private val reservation: WriteReservation,
    ) : OutputStream() {
        private val fileOutput = FileOutputStream(reservation.partFile)
        private val bufferedOutput = BufferedOutputStream(fileOutput)
        private var closed = false

        override fun write(value: Int) {
            checkOpen()
            owner.writeChunk(reservation, 1) { bufferedOutput.write(value) }
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            checkOpen()
            if (offset < 0 || length < 0 || offset > bytes.size - length) {
                throw IndexOutOfBoundsException()
            }
            if (length == 0) return
            owner.writeChunk(reservation, length) { bufferedOutput.write(bytes, offset, length) }
        }

        override fun flush() {
            checkOpen()
            bufferedOutput.flush()
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                bufferedOutput.flush()
                fileOutput.fd.sync()
            } finally {
                bufferedOutput.close()
            }
        }

        private fun checkOpen() {
            if (closed) throw IOException("Cache output is closed")
        }
    }

    private companion object {
        const val BLOB_SUFFIX = ".blob"
        const val PART_SUFFIX = ".part"
        const val METADATA_SUFFIX = ".meta"
        const val METADATA_MAGIC = 0x4f4c4348
        const val METADATA_VERSION = 1

        val ENTRY_LRU_ORDER = compareBy<EntryRecord> { it.lastAccessAtMillis }
            .thenBy { it.createdAtMillis }
            .thenBy { it.key.diskId }

        fun saturatingAdd(left: Long, right: Long): Long =
            if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
    }
}
