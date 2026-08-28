package org.openlist.mobile.data.upload

import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException

enum class UploadWorkDisposition(
    val shouldCleanup: Boolean,
    val shouldReleaseSourceGrant: Boolean,
) {
    SUCCESS(shouldCleanup = true, shouldReleaseSourceGrant = true),
    PERMANENT_FAILURE(shouldCleanup = true, shouldReleaseSourceGrant = true),
    RETRY(shouldCleanup = false, shouldReleaseSourceGrant = false),
    CANCELLED(shouldCleanup = false, shouldReleaseSourceGrant = true),
}

object UploadWorkPolicy {
    fun classifyFailure(error: Throwable): UploadWorkDisposition {
        if (error.findCause<UploadPermanentException>() != null) {
            return UploadWorkDisposition.PERMANENT_FAILURE
        }

        error.findCause<UploadProtocolException>()?.let { protocol ->
            val retryable = protocol.effectiveCode == 408 ||
                protocol.effectiveCode == 409 ||
                protocol.effectiveCode == 425 ||
                protocol.effectiveCode == 429 ||
                protocol.effectiveCode >= 500
            return if (retryable) {
                UploadWorkDisposition.RETRY
            } else {
                UploadWorkDisposition.PERMANENT_FAILURE
            }
        }

        if (error.findCause<FileNotFoundException>() != null ||
            error.findCause<EOFException>() != null ||
            error.findCause<SecurityException>() != null ||
            error.findCause<IllegalArgumentException>() != null ||
            error.findCause<IllegalStateException>() != null
        ) {
            return UploadWorkDisposition.PERMANENT_FAILURE
        }

        val retryable = error.findCause<IOException>() != null
        return if (retryable) {
            UploadWorkDisposition.RETRY
        } else {
            UploadWorkDisposition.PERMANENT_FAILURE
        }
    }

    private inline fun <reified T : Throwable> Throwable.findCause(): T? {
        var candidate: Throwable? = this
        val visited = HashSet<Throwable>()
        while (candidate != null && visited.add(candidate)) {
            if (candidate is T) return candidate
            candidate = candidate.cause
        }
        return null
    }
}
