package org.openlist.mobile.data.download

enum class DownloadWorkDisposition(
    val shouldReleasePersistedGrant: Boolean,
) {
    SUCCESS(shouldReleasePersistedGrant = true),
    PERMANENT_FAILURE(shouldReleasePersistedGrant = true),
    CANCELLED(shouldReleasePersistedGrant = true),
    RETRY(shouldReleasePersistedGrant = false),
}

object DownloadWorkPolicy {
    fun forFailure(failure: DownloadFailure): DownloadWorkDisposition =
        if (failure.retryable) {
            DownloadWorkDisposition.RETRY
        } else {
            DownloadWorkDisposition.PERMANENT_FAILURE
        }
}
