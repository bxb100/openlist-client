package org.openlist.mobile.data.download

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DownloadWorkPolicyTest {
    @Test
    fun `only terminal outcomes release persisted target grant`() {
        assertThat(DownloadWorkDisposition.SUCCESS.shouldReleasePersistedGrant).isTrue()
        assertThat(DownloadWorkDisposition.CANCELLED.shouldReleasePersistedGrant).isTrue()
        assertThat(
            DownloadWorkPolicy.forFailure(
                DownloadFailure(DownloadFailureCode.NOT_FOUND, false, "missing"),
            ).shouldReleasePersistedGrant,
        ).isTrue()
        assertThat(
            DownloadWorkPolicy.forFailure(
                DownloadFailure(DownloadFailureCode.NETWORK, true, "offline"),
            ).shouldReleasePersistedGrant,
        ).isFalse()
    }

    @Test
    fun `target work name is stable distinct and does not disclose uri`() {
        val firstUri = "content://documents/private/report.pdf"
        val first = DownloadTargetWork.uniqueName(firstUri)
        val again = DownloadTargetWork.uniqueName(firstUri)
        val second = DownloadTargetWork.uniqueName("content://documents/private/other.pdf")

        assertThat(first).isEqualTo(again)
        assertThat(first).isNotEqualTo(second)
        assertThat(first).doesNotContain(firstUri)
        assertThat(first).matches("openlist-download-[0-9a-f]{64}")
    }
}
