package org.openlist.mobile.data.upload

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException

class UploadWorkPolicyTest {
    @Test
    fun `network and temporary server failures remain retryable without an attempt cap`() {
        val network = IOException("connection reset")
        val server = UploadProtocolException(503, 503, "unavailable")
        val malformedServerFailure = UploadProtocolException(
            httpStatus = 503,
            apiCode = 503,
            message = "unparseable maintenance response",
            malformedResponse = true,
            cause = IllegalStateException("not JSON"),
        )

        assertThat(UploadWorkPolicy.classifyFailure(network))
            .isEqualTo(UploadWorkDisposition.RETRY)
        assertThat(UploadWorkPolicy.classifyFailure(server))
            .isEqualTo(UploadWorkDisposition.RETRY)
        assertThat(UploadWorkPolicy.classifyFailure(malformedServerFailure))
            .isEqualTo(UploadWorkDisposition.RETRY)
    }

    @Test
    fun `provider file not found and eof failures never retry`() {
        assertThat(UploadWorkPolicy.classifyFailure(FileNotFoundException()))
            .isEqualTo(UploadWorkDisposition.PERMANENT_FAILURE)
        assertThat(UploadWorkPolicy.classifyFailure(IOException(EOFException())))
            .isEqualTo(UploadWorkDisposition.PERMANENT_FAILURE)
        assertThat(UploadWorkPolicy.classifyFailure(SecurityException()))
            .isEqualTo(UploadWorkDisposition.PERMANENT_FAILURE)
    }

    @Test
    fun `cleanup is limited to success and permanent terminal failures`() {
        assertThat(UploadWorkDisposition.SUCCESS.shouldCleanup).isTrue()
        assertThat(UploadWorkDisposition.PERMANENT_FAILURE.shouldCleanup).isTrue()
        assertThat(UploadWorkDisposition.RETRY.shouldCleanup).isFalse()
        assertThat(UploadWorkDisposition.CANCELLED.shouldCleanup).isFalse()
    }

    @Test
    fun `source grant survives only a real retry`() {
        assertThat(UploadWorkDisposition.SUCCESS.shouldReleaseSourceGrant).isTrue()
        assertThat(UploadWorkDisposition.PERMANENT_FAILURE.shouldReleaseSourceGrant).isTrue()
        assertThat(UploadWorkDisposition.CANCELLED.shouldReleaseSourceGrant).isTrue()
        assertThat(UploadWorkDisposition.RETRY.shouldReleaseSourceGrant).isFalse()
    }

    @Test
    fun `ordinary client error is permanent while throttling can retry`() {
        val badRequest = UploadProtocolException(400, 400, "bad path")
        val throttled = UploadProtocolException(429, 429, "slow down")

        assertThat(UploadWorkPolicy.classifyFailure(badRequest))
            .isEqualTo(UploadWorkDisposition.PERMANENT_FAILURE)
        assertThat(UploadWorkPolicy.classifyFailure(throttled))
            .isEqualTo(UploadWorkDisposition.RETRY)
    }
}
