package org.openlist.mobile.data.cache

import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import org.openlist.mobile.core.model.CachePolicy

/**
 * Synchronous content-cache boundary shared by media and image loaders.
 *
 * Methods perform filesystem I/O and should be called on the caller's I/O executor/dispatcher.
 */
interface CacheCoordinator : Closeable {
    val policy: CachePolicy

    fun updatePolicy(policy: CachePolicy): CacheTrimResult

    fun acquire(key: CacheKey): CacheLease?

    fun beginWrite(key: CacheKey, expectedBytes: Long? = null): CacheWriteSession?

    fun stats(): CacheStats

    fun entries(): List<CacheEntrySnapshot>

    fun remove(key: CacheKey): Boolean

    fun trim(): CacheTrimResult

    fun clear(): CacheTrimResult
}

/**
 * Pins a cache file until closed. A caller must retain the lease for as long as a player,
 * decoder, or input stream can still read [file].
 */
class CacheLease internal constructor(
    val entry: CacheEntrySnapshot,
    val file: File,
    private val release: () -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)

    fun openInputStream(): InputStream {
        check(!closed.get()) { "Cache lease is closed" }
        return FileInputStream(file)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}

/**
 * A staged write. [commit] atomically publishes non-empty content whose final length exactly
 * matches the optional `expectedBytes` supplied to [CacheCoordinator.beginWrite]. Closing first
 * aborts it.
 */
interface CacheWriteSession : Closeable {
    val outputStream: OutputStream

    /** Returns false when policy changed, clear was requested, or the completed blob is too large. */
    fun commit(): Boolean

    fun abort()

    override fun close() = abort()
}
