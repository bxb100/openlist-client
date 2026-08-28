package org.openlist.mobile.data.upload

import androidx.work.ExistingWorkPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/** Stable, non-disclosing identity used to serialize writes to one OpenList destination. */
object UploadTargetWork {
    val existingWorkPolicy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP

    /**
     * The session binding already identifies the exact server, account, and token generation.
     * Source identity is deliberately omitted: different sources still conflict when they target
     * the same remote path, so the first unfinished upload must remain the sole writer.
     */
    fun uniqueName(
        sessionBinding: UploadSessionBinding,
        serverAllowInsecureHttp: Boolean,
        remotePath: String,
    ): String {
        val canonicalPath = canonicalRemotePath(remotePath)
        val digest = sha256(
            "openlist-upload-target-v1\u0000${sessionBinding.value}\u0000" +
                "$serverAllowInsecureHttp\u0000$canonicalPath",
        )
        return "$UNIQUE_WORK_PREFIX$digest"
    }

    internal fun canonicalRemotePath(remotePath: String): String {
        require(remotePath.isNotBlank()) { "remotePath must not be blank" }
        val components = mutableListOf<String>()
        remotePath.split('/').forEach { component ->
            when (component) {
                "", "." -> Unit
                ".." -> {
                    require(components.isNotEmpty()) { "remotePath must not escape the root" }
                    components.removeAt(components.lastIndex)
                }
                else -> components += component
            }
        }
        require(components.isNotEmpty()) { "remotePath must identify a file" }
        return "/${components.joinToString("/")}"
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private const val UNIQUE_WORK_PREFIX = "openlist-upload-"
}

/** Result of deciding whether a unique upload owns its source grant. */
internal enum class UploadUniqueEnqueueResult {
    ENQUEUED,
    KEPT_EXISTING,
}

/**
 * Serializes the WorkManager KEEP decision with acquisition of a persistable source grant.
 *
 * WorkManager's enqueue [androidx.work.Operation] completes successfully even when KEEP discards
 * the new request. Keeping grant acquisition inside this gate lets the caller confirm that its
 * request was actually inserted before ownership is handed to the worker. Once a grant has been
 * acquired, cancellation must not strand it between that acquisition and the final decision.
 */
internal class UploadUniqueEnqueueGate {
    private val mutex = Mutex()

    suspend fun enqueue(
        hasActiveWork: suspend () -> Boolean,
        acquireSourceGrant: () -> Unit,
        enqueueAndConfirm: suspend () -> Boolean,
        releaseSourceGrant: suspend () -> Unit,
    ): UploadUniqueEnqueueResult = mutex.withLock {
        withContext(NonCancellable + Dispatchers.IO) {
            if (hasActiveWork()) {
                return@withContext UploadUniqueEnqueueResult.KEPT_EXISTING
            }

            var grantAcquired = false
            var grantTransferred = false
            try {
                acquireSourceGrant()
                grantAcquired = true
                if (enqueueAndConfirm()) {
                    grantTransferred = true
                    UploadUniqueEnqueueResult.ENQUEUED
                } else {
                    UploadUniqueEnqueueResult.KEPT_EXISTING
                }
            } finally {
                if (grantAcquired && !grantTransferred) {
                    releaseSourceGrant()
                }
            }
        }
    }
}
