package org.openlist.mobile.data.download

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/** Stable, non-disclosing identity used to serialize writes to one SAF destination. */
object DownloadTargetWork {
    fun uniqueName(targetUri: String): String {
        require(targetUri.isNotBlank()) { "targetUri must not be blank" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(targetUri.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return "openlist-download-$digest"
    }
}

internal enum class DownloadUniqueEnqueueResult {
    ENQUEUED,
    KEPT_EXISTING,
}

/** Keeps the KEEP decision and persistable-grant handoff in one cancellation-safe critical path. */
internal class DownloadUniqueEnqueueGate {
    private val mutex = Mutex()

    suspend fun enqueue(
        hasActiveWork: suspend () -> Boolean,
        acquireTargetGrant: () -> Unit,
        enqueueAndConfirm: suspend () -> Boolean,
        reconcileUnacceptedGrant: suspend () -> Unit,
    ): DownloadUniqueEnqueueResult = mutex.withLock {
        withContext(NonCancellable) {
            if (hasActiveWork()) {
                return@withContext DownloadUniqueEnqueueResult.KEPT_EXISTING
            }

            var grantAcquired = false
            var grantTransferred = false
            try {
                acquireTargetGrant()
                grantAcquired = true
                if (enqueueAndConfirm()) {
                    grantTransferred = true
                    DownloadUniqueEnqueueResult.ENQUEUED
                } else {
                    DownloadUniqueEnqueueResult.KEPT_EXISTING
                }
            } finally {
                if (grantAcquired && !grantTransferred) reconcileUnacceptedGrant()
            }
        }
    }
}
