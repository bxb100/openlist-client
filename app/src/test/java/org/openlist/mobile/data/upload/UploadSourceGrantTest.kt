package org.openlist.mobile.data.upload

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UploadSourceGrantTest {
    @Test
    fun `grant is not released before staging succeeds`() {
        var releases = 0
        val grant = UploadSourceGrant { releases += 1 }

        assertThat(releases).isEqualTo(0)

        grant.onWorkDisposition(UploadWorkDisposition.RETRY)

        assertThat(releases).isEqualTo(0)
    }

    @Test
    fun `staging success releases immediately and success is idempotent`() {
        var releases = 0
        val grant = UploadSourceGrant { releases += 1 }

        grant.onStagingSucceeded()

        assertThat(releases).isEqualTo(1)

        grant.onWorkDisposition(UploadWorkDisposition.SUCCESS)
        assertThat(releases).isEqualTo(1)
    }

    @Test
    fun `retry before staging retains grant for the next attempt`() {
        var releases = 0
        val grant = UploadSourceGrant { releases += 1 }

        grant.onWorkDisposition(UploadWorkDisposition.RETRY)
        assertThat(releases).isEqualTo(0)

        grant.onStagingSucceeded()
        assertThat(releases).isEqualTo(1)
    }

    @Test
    fun `restored staging tolerates an already absent grant`() {
        var attempts = 0
        val grant = UploadSourceGrant {
            attempts += 1
            throw SecurityException("no persisted grant")
        }

        grant.onStagingSucceeded()
        grant.onWorkDisposition(UploadWorkDisposition.SUCCESS)

        assertThat(attempts).isEqualTo(1)
    }

    @Test
    fun `permanent failure before staging releases the grant`() {
        var releases = 0
        val grant = UploadSourceGrant { releases += 1 }

        grant.onWorkDisposition(UploadWorkDisposition.PERMANENT_FAILURE)

        assertThat(releases).isEqualTo(1)
    }

    @Test
    fun `explicit cancellation before staging releases the grant`() {
        var releases = 0
        val grant = UploadSourceGrant { releases += 1 }

        grant.onWorkDisposition(UploadWorkDisposition.CANCELLED)

        assertThat(releases).isEqualTo(1)
    }

    @Test
    fun `failed immediate release is retried at the work outcome`() {
        var attempts = 0
        val grant = UploadSourceGrant {
            attempts += 1
            if (attempts == 1) throw IllegalStateException("provider temporarily unavailable")
        }

        grant.onStagingSucceeded()
        assertThat(attempts).isEqualTo(1)

        grant.onWorkDisposition(UploadWorkDisposition.RETRY)
        assertThat(attempts).isEqualTo(2)
    }
}
