package org.openlist.mobile.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ServerProfileTest {
    @Test
    fun `normalizes https by default`() {
        assertThat(ServerProfile("files.example.com/openlist").normalizedBaseUrl())
            .isEqualTo("https://files.example.com/openlist")
    }

    @Test
    fun `local http requires explicit opt in`() {
        val failure = runCatching {
            ServerProfile("http://192.168.1.20:5244").normalizedBaseUrl()
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failure).hasMessageThat().contains("显式允许")
    }

    @Test
    fun `opted in local http is accepted`() {
        val profile = ServerProfile(
            baseUrl = "http://192.168.1.20:5244/openlist/",
            allowInsecureHttp = true,
        )

        assertThat(profile.normalizedBaseUrl())
            .isEqualTo("http://192.168.1.20:5244/openlist")
    }

    @Test
    fun `explicitly opted in http accepts a public host`() {
        val profile = ServerProfile(
            baseUrl = "http://files.example.com",
            allowInsecureHttp = true,
        )

        assertThat(profile.normalizedBaseUrl()).isEqualTo("http://files.example.com")
    }
}
