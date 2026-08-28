package org.openlist.mobile.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UploadStatusPolicyTest {
    @Test
    fun `known upload progress is clamped to the valid range`() {
        assertThat(uploadProgress(uploadedBytes = -1, totalBytes = 100)).isEqualTo(0f)
        assertThat(uploadProgress(uploadedBytes = 25, totalBytes = 100)).isEqualTo(0.25f)
        assertThat(uploadProgress(uploadedBytes = 101, totalBytes = 100)).isEqualTo(1f)
    }

    @Test
    fun `unknown or empty total remains indeterminate`() {
        assertThat(uploadProgress(uploadedBytes = 10, totalBytes = null)).isNull()
        assertThat(uploadProgress(uploadedBytes = 10, totalBytes = 0)).isNull()
    }
}
