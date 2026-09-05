package org.openlist.mobile.ui.transfers

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TransferProgressTest {
    @Test
    fun `known transfer progress is clamped to the valid range`() {
        assertThat(transferProgress(transferredBytes = -1, totalBytes = 100)).isEqualTo(0f)
        assertThat(transferProgress(transferredBytes = 25, totalBytes = 100)).isEqualTo(0.25f)
        assertThat(transferProgress(transferredBytes = 101, totalBytes = 100)).isEqualTo(1f)
    }

    @Test
    fun `unknown or empty total remains indeterminate`() {
        assertThat(transferProgress(transferredBytes = 10, totalBytes = null)).isNull()
        assertThat(transferProgress(transferredBytes = 10, totalBytes = 0)).isNull()
    }
}
