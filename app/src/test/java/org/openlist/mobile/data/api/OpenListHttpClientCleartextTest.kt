package org.openlist.mobile.data.api

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OpenListHttpClientCleartextTest {
    @Test
    fun `request uses one atomic session snapshot for host policy and token`() {
        var snapshotReads = 0
        val client = OpenListHttpClient(
            baseUrl = { error("legacy baseUrl supplier must not be read") },
            token = { error("legacy token supplier must not be read") },
            allowInsecureHttp = { error("legacy cleartext supplier must not be read") },
            sessionSnapshot = {
                snapshotReads += 1
                HttpSessionSnapshot(
                    baseUrl = "https://files.example.com/openlist",
                    token = "account-a-token",
                    allowInsecureHttp = false,
                )
            },
        )

        val request = client.requestBuilder("/api/me").build()

        assertThat(snapshotReads).isEqualTo(1)
        assertThat(request.url.toString()).isEqualTo("https://files.example.com/openlist/api/me")
        assertThat(request.header("Authorization")).isEqualTo("account-a-token")
    }

    @Test
    fun `https is accepted without cleartext opt in`() {
        val client = OpenListHttpClient(
            baseUrl = { "https://files.example.com/openlist" },
            token = { null },
        )

        assertThat(client.resolveUrl("/api/me").toString())
            .isEqualTo("https://files.example.com/openlist/api/me")
    }

    @Test
    fun `local http requires explicit opt in`() {
        val client = OpenListHttpClient(
            baseUrl = { "http://192.168.1.20:5244" },
            token = { null },
        )

        val failure = runCatching { client.resolveUrl("/api/me") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failure).hasMessageThat().contains("显式允许")
    }

    @Test
    fun `opted in local http is accepted`() {
        val client = OpenListHttpClient(
            baseUrl = { "http://nas.local:5244" },
            token = { null },
            allowInsecureHttp = { true },
        )

        assertThat(client.resolveUrl("/api/me").toString())
            .isEqualTo("http://nas.local:5244/api/me")
    }

    @Test
    fun `public http is accepted after explicit opt in`() {
        val client = OpenListHttpClient(
            baseUrl = { "http://files.example.com" },
            token = { null },
            allowInsecureHttp = { true },
        )

        assertThat(client.resolveUrl("/api/me").toString())
            .isEqualTo("http://files.example.com/api/me")
    }
}
