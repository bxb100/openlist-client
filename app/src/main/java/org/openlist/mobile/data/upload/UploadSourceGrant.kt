package org.openlist.mobile.data.upload

/**
 * Owns the persisted read grant used to copy an upload source into private staging.
 *
 * A retry before staging still needs the grant. Once staging has completed, or the work reaches a
 * terminal outcome, the private copy is sufficient and the persisted grant can be released.
 */
internal class UploadSourceGrant(
    private val releasePersistedReadGrant: () -> Unit,
) {
    private var stagingSucceeded = false
    private var releaseResolved = false

    fun onStagingSucceeded() {
        stagingSucceeded = true
        releaseSafely()
    }

    fun onWorkDisposition(disposition: UploadWorkDisposition) {
        if (stagingSucceeded || disposition.shouldReleaseSourceGrant) {
            releaseSafely()
        }
    }

    private fun releaseSafely() {
        if (releaseResolved) return
        try {
            releasePersistedReadGrant()
            releaseResolved = true
        } catch (_: SecurityException) {
            // A restored staging file can outlive a process whose grant was already released.
            releaseResolved = true
        } catch (_: IllegalArgumentException) {
            // Treat a provider reporting no matching persistable grant as the desired end state.
            releaseResolved = true
        } catch (_: Exception) {
            // Cleanup is best effort. Leave it unresolved so the terminal path can retry once.
        }
    }
}
