package org.openlist.mobile.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class LoginEndpointDraftTest {
    @Test
    fun `new login defaults to http on the OpenList port`() {
        val draft = LoginEndpointDraft(host = "192.168.1.20")

        assertThat(draft.protocol).isEqualTo(LoginProtocol.HTTP)
        assertThat(draft.port).isEqualTo("5244")
        assertThat(draft.baseUrl()).isEqualTo("http://192.168.1.20:5244")
        assertThat(draft.serverProfile("admin").normalizedBaseUrl())
            .isEqualTo("http://192.168.1.20:5244")
    }

    @Test
    fun `scheme-less endpoint input receives the new connection defaults`() {
        val draft = LoginEndpointDraft.fromBaseUrl("192.168.1.20/openlist")

        assertThat(draft.protocol).isEqualTo(LoginProtocol.HTTP)
        assertThat(draft.port).isEqualTo("5244")
        assertThat(draft.basePath).isEqualTo("/openlist")
        assertThat(draft.baseUrl()).isEqualTo("http://192.168.1.20:5244/openlist")
    }

    @Test
    fun `existing https endpoint without a port keeps its identity`() {
        val draft = LoginEndpointDraft.fromBaseUrl("https://files.example.com/openlist")

        assertThat(draft.protocol).isEqualTo(LoginProtocol.HTTPS)
        assertThat(draft.port).isEmpty()
        assertThat(draft.basePath).isEqualTo("/openlist")
        assertThat(draft.baseUrl()).isEqualTo("https://files.example.com/openlist")
    }

    @Test
    fun `ipv6 and reverse proxy path round trip`() {
        val source = "http://[fd00::2]:5244/openlist"

        val draft = LoginEndpointDraft.fromBaseUrl(source)

        assertThat(draft.host).isEqualTo("fd00::2")
        assertThat(draft.baseUrl()).isEqualTo(source)
    }

    @Test
    fun `invalid ports are rejected`() {
        listOf("not-a-port", "0", "65536").forEach { port ->
            assertThrows(IllegalArgumentException::class.java) {
                LoginEndpointDraft(host = "nas.local", port = port).baseUrl()
            }
        }
    }

    @Test
    fun `default http explicitly opts in for a public host`() {
        val profile = LoginEndpointDraft(host = "files.example.com").serverProfile("admin")

        assertThat(profile.allowInsecureHttp).isTrue()
        assertThat(profile.normalizedBaseUrl()).isEqualTo("http://files.example.com:5244")
    }
}
