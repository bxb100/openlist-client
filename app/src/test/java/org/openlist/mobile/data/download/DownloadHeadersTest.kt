package org.openlist.mobile.data.download

import com.google.common.truth.Truth.assertThat
import okhttp3.Headers
import org.junit.Test
import org.openlist.mobile.media.ResolvedMediaUrl

class DownloadHeadersTest {
    @Test
    fun `resolved request strips credentials and transport-owned headers`() {
        val request = DownloadHeaders.request(
            ResolvedMediaUrl(
                url = "https://cdn.example/file",
                requestHeaders = mapOf(
                    "Authorization" to "Bearer secret",
                    "Cookie" to "session=secret",
                    "Password" to "directory-password",
                    "Host" to "attacker.example",
                    "Content-Length" to "999",
                    "X-Safe-Source" to "required",
                    "Accept-Encoding" to "gzip",
                ),
            ),
        )

        assertThat(request.header("Authorization")).isNull()
        assertThat(request.header("Cookie")).isNull()
        assertThat(request.header("Password")).isNull()
        assertThat(request.header("Host")).isNull()
        assertThat(request.header("Content-Length")).isNull()
        assertThat(request.header("X-Safe-Source")).isEqualTo("required")
        assertThat(request.header("Accept-Encoding")).isEqualTo("identity")
    }

    @Test
    fun `network scrub removes credentials but preserves OkHttp transport headers`() {
        val headers = Headers.Builder()
            .add("Host", "cdn.example")
            .add("Connection", "Keep-Alive")
            .add("Authorization", "secret")
            .add("X-OpenList-Token", "secret")
            .build()

        val safe = DownloadHeaders.sanitize(headers)

        assertThat(safe["Host"]).isEqualTo("cdn.example")
        assertThat(safe["Connection"]).isEqualTo("Keep-Alive")
        assertThat(safe["Authorization"]).isNull()
        assertThat(safe["X-OpenList-Token"]).isNull()
    }
}
