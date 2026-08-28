package org.openlist.mobile.data.download

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DownloadRetryClassifierTest {
    @Test
    fun `transient statuses retry while auth and missing files fail`() {
        assertThat(DownloadRetryClassifier.classifyHttp(429).retryable).isTrue()
        assertThat(DownloadRetryClassifier.classifyHttp(503).retryable).isTrue()
        assertThat(DownloadRetryClassifier.classifyHttp(408).retryable).isTrue()
        assertThat(DownloadRetryClassifier.classifyHttp(403).retryable).isFalse()
        assertThat(DownloadRetryClassifier.classifyHttp(404).retryable).isFalse()
    }

    @Test
    fun `target and integrity failures are permanent`() {
        assertThat(DownloadRetryClassifier.classify(DownloadTargetException("target")).retryable)
            .isFalse()
        assertThat(DownloadRetryClassifier.classify(DownloadIntegrityException("size")).retryable)
            .isFalse()
        val sessionFailure = DownloadRetryClassifier.classify(DownloadSessionChangedException())
        assertThat(sessionFailure.code).isEqualTo(DownloadFailureCode.SESSION_CHANGED)
        assertThat(sessionFailure.retryable).isFalse()
    }
}
