package org.openlist.mobile.data.upload

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlinx.coroutines.test.runTest
import org.junit.Test

class JobBoundUploadSessionTest {
    @Test
    fun `bound client keeps captured server and raw token after ambient session changes`() {
        var ambientBaseUrl = "https://one.example/openlist/"
        var ambientUsername = "alice"
        var ambientToken = "token-one"
        val expectedBinding = UploadSessionBinding.create(
            org.openlist.mobile.core.model.ServerProfile(
                ambientBaseUrl,
                ambientUsername,
                allowInsecureHttp = false,
            ),
            ambientToken,
        )
        val session = JobBoundUploadSession.capture(
            expectedBaseUrl = ambientBaseUrl,
            expectedUsername = ambientUsername,
            expectedAllowInsecureHttp = false,
            expectedSessionBinding = expectedBinding,
            currentBaseUrl = ambientBaseUrl,
            currentUsername = ambientUsername,
            currentAllowInsecureHttp = false,
            currentToken = ambientToken,
        )

        ambientBaseUrl = "https://two.example"
        ambientUsername = "bob"
        ambientToken = "token-two"
        val request = session.newHttpClient(OkHttpClient(), Gson()) { true }
            .requestBuilder("/api/fs/multipart/status")
            .build()

        assertThat(request.url.toString())
            .isEqualTo("https://one.example/openlist/api/fs/multipart/status")
        assertThat(request.header("Authorization")).isEqualTo("token-one")
        assertThat(request.header("Authorization")).isNotEqualTo(ambientToken)
        assertThat(request.url.host).isNotEqualTo("two.example")
    }

    @Test
    fun `capture rejects a different account before exposing a client`() {
        val error = runCatching {
            JobBoundUploadSession.capture(
                expectedBaseUrl = "https://one.example",
                expectedUsername = "alice",
                expectedAllowInsecureHttp = false,
                expectedSessionBinding = UploadSessionBinding.create(
                    org.openlist.mobile.core.model.ServerProfile("https://one.example", "alice"),
                    "token",
                ),
                currentBaseUrl = "https://one.example",
                currentUsername = "bob",
                currentAllowInsecureHttp = false,
                currentToken = "token",
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(UploadPermanentException::class.java)
    }

    @Test
    fun `network guard terminates requests after logout or account change`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
            var currentToken = "token-one"
            val baseUrl = server.url("/").toString()
            val session = JobBoundUploadSession.capture(
                expectedBaseUrl = baseUrl,
                expectedUsername = "alice",
                expectedAllowInsecureHttp = true,
                expectedSessionBinding = UploadSessionBinding.create(
                    org.openlist.mobile.core.model.ServerProfile(baseUrl, "alice", true),
                    currentToken,
                ),
                currentBaseUrl = baseUrl,
                currentUsername = "alice",
                currentAllowInsecureHttp = true,
                currentToken = currentToken,
            )
            val client = session.newHttpClient(OkHttpClient(), Gson()) {
                session.matchesCurrent(baseUrl, "alice", true, currentToken)
            }
            client.raw(client.requestBuilder("/first").build()).close()

            currentToken = ""
            val error = runCatching {
                client.raw(client.requestBuilder("/second").build()).close()
            }.exceptionOrNull()

            assertThat(error).isInstanceOf(UploadPermanentException::class.java)
            assertThat(UploadWorkPolicy.classifyFailure(requireNotNull(error)))
                .isEqualTo(UploadWorkDisposition.PERMANENT_FAILURE)
            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    @Test
    fun `capture rejects token rotation that happened before the worker started`() {
        val profile = org.openlist.mobile.core.model.ServerProfile(
            "https://one.example",
            "alice",
        )
        val queuedBinding = UploadSessionBinding.create(profile, "token-at-enqueue")

        val error = runCatching {
            JobBoundUploadSession.capture(
                expectedBaseUrl = profile.baseUrl,
                expectedUsername = profile.username,
                expectedAllowInsecureHttp = profile.allowInsecureHttp,
                expectedSessionBinding = queuedBinding,
                currentBaseUrl = profile.baseUrl,
                currentUsername = profile.username,
                currentAllowInsecureHttp = profile.allowInsecureHttp,
                currentToken = "token-after-login",
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(UploadPermanentException::class.java)
        assertThat(error).hasMessageThat().contains("凭据已变化")
    }
}
