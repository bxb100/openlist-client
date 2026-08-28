package org.openlist.mobile.data.upload

/** Applies the retention contract for private staging data and multipart checkpoints. */
internal class UploadWorkCleanup(
    private val stagingStore: UploadStagingStore,
    private val checkpointStore: UploadCheckpointStore,
) {
    suspend fun apply(
        disposition: UploadWorkDisposition,
        stagingKey: String,
        checkpointKey: String?,
    ) {
        if (!disposition.shouldCleanup) return
        if (disposition == UploadWorkDisposition.PERMANENT_FAILURE && checkpointKey != null) {
            try {
                checkpointStore.remove(checkpointKey)
            } catch (_: Exception) {
                // Cleanup must not replace the actionable upload failure returned to the user.
            }
        }
        try {
            stagingStore.remove(stagingKey)
        } catch (_: Exception) {
            // The private orphan can be removed on a future upload without repeating the upload.
        }
    }
}
