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

    @Test
    fun `pasting a full URL replaces all previous advanced options`() {
        val previous = LoginEndpointDraft("nas.local", LoginProtocol.HTTP, "5244", "/old")
        val pasted = previous.withAddressInput(" https://files.example.com/team%2Ffiles ")

        assertThat(pasted.protocol).isEqualTo(LoginProtocol.HTTPS)
        assertThat(pasted.port).isEmpty()
        assertThat(pasted.baseUrl()).isEqualTo("https://files.example.com/team%2Ffiles")
    }

    @Test
    fun `proxy paths preserve encoded separators and encode new spaces`() {
        val endpoint = LoginEndpointDraft.fromBaseUrl("https://files.example.com/team%2Ffiles/%E6%96%87%E4%BB%B6")

        assertThat(endpoint.baseUrl()).isEqualTo("https://files.example.com/team%2Ffiles/%E6%96%87%E4%BB%B6")
        assertThat(endpoint.copy(basePath = "/team files").baseUrl())
            .isEqualTo("https://files.example.com/team%20files")
    }

    @Test
    fun `editing a plain hostname retains manually selected connection options`() {
        val previous = LoginEndpointDraft("old.local", LoginProtocol.HTTPS, "8443", "/proxy")

        assertThat(previous.withAddressInput("new.local").baseUrl())
            .isEqualTo("https://new.local:8443/proxy")
    }

    @Test
    fun `pasted explicit IPv6 URL retains its scheme and port`() {
        val pasted = LoginEndpointDraft().withAddressInput("https://[fd00::2]:8443/openlist")

        assertThat(pasted.baseUrl()).isEqualTo("https://[fd00::2]:8443/openlist")
    }

    @Test
    fun `invalid pasted endpoints are not silently replaced by valid defaults`() {
        listOf(
            "ftp://files.example.com",
            "https://files.example.com:wrong",
            "https://files.example.com:65536",
            "https://user:password@files.example.com",
            "https://files.example.com/path?token=secret",
            "https://files.example.com/#fragment",
        ).forEach { source ->
            val pasted = LoginEndpointDraft(host = "old.local").withAddressInput(source)
            assertThat(runCatching { pasted.baseUrl() }.isFailure).isTrue()
        }
    }

    @Test
    fun `pasted proxy path preserves repeated leading slashes`() {
        val pasted = LoginEndpointDraft().withAddressInput("https://files.example.com//tenant/team%2Ffiles")

        assertThat(pasted.basePath).isEqualTo("//tenant/team%2Ffiles")
        assertThat(pasted.baseUrl()).isEqualTo("https://files.example.com//tenant/team%2Ffiles")
        assertThat(pasted.copy(basePath = "//tenant/team files").baseUrl())
            .isEqualTo("https://files.example.com//tenant/team%20files")
    }
}
