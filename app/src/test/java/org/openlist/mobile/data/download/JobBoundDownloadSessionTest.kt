package org.openlist.mobile.data.download

import com.google.gson.Gson
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Test
import org.openlist.mobile.core.model.ServerProfile
import org.openlist.mobile.data.api.OpenListHttpClient
import org.openlist.mobile.data.preferences.AppSettings

class JobBoundDownloadSessionTest {
    @Test
    fun `capture rejects changed or missing signed in session`() {
        val settings = AppSettings(
            server = ServerProfile("https://files.example.com", "alice"),
            token = "secret-token",
        )
        val binding = DownloadSessionBinding.create(settings.server, settings.token)

        val session = JobBoundDownloadSession.capture(binding, settings)
        assertThat(session.matchesCurrent(settings)).isTrue()
        assertThat(
            session.matchesCurrent(
                settings.copy(token = "other-token"),
            ),
        ).isFalse()

        val changedBinding = DownloadSessionBinding.create(
            settings.server.copy(username = "bob"),
            settings.token,
        )
        val missingToken = settings.copy(token = "")

        val changedError = runCatching {
            JobBoundDownloadSession.capture(changedBinding, settings)
        }.exceptionOrNull()
        val missingTokenError = runCatching {
            JobBoundDownloadSession.capture(binding, missingToken)
        }.exceptionOrNull()

        assertThat(changedError).isInstanceOf(DownloadSessionChangedException::class.java)
        assertThat(missingTokenError).isInstanceOf(DownloadSessionChangedException::class.java)
    }

    @Test
    fun `guarded http client keeps captured identity and blocks drifted requests`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
            val settings = AppSettings(
                server = ServerProfile(
                    baseUrl = server.url("/base").toString(),
                    username = "alice",
                    allowInsecureHttp = true,
                ),
                token = "secret-token",
            )
            val binding = DownloadSessionBinding.create(settings.server, settings.token)
            val session = JobBoundDownloadSession.capture(binding, settings)
            var sessionCurrent = true
            val http = session.newHttpClient(
                okHttpClient = OpenListHttpClient.defaultOkHttpClient(),
                gson = Gson(),
                isSessionCurrent = { sessionCurrent },
            )

            val request = http.requestBuilder("/api/ping").build()
            assertThat(request.url.toString()).isEqualTo(server.url("/base/api/ping").toString())
            assertThat(request.header("Authorization")).isEqualTo("secret-token")

            http.raw(request).use { response ->
                assertThat(response.code).isEqualTo(200)
            }
            assertThat(server.takeRequest().path).isEqualTo("/base/api/ping")

            sessionCurrent = false
            val error = runCatching { http.raw(http.requestBuilder("/api/again").build()) }.exceptionOrNull()

            assertThat(error).isInstanceOf(DownloadSessionChangedException::class.java)
            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    @Test
    fun `network guard checks every redirect hop`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", server.url("/second")),
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody("must-not-arrive"))
            val settings = localSettings(server)
            val binding = DownloadSessionBinding.create(settings.server, settings.token)
            val session = JobBoundDownloadSession.capture(binding, settings)
            var checks = 0
            val http = session.newHttpClient(
                okHttpClient = OpenListHttpClient.defaultOkHttpClient(),
                gson = Gson(),
                // Application guard, first network hop, then redirected network hop.
                isSessionCurrent = { ++checks < 3 },
            )

            val error = runCatching {
                http.raw(http.requestBuilder("/first").build()).close()
            }.exceptionOrNull()

            assertThat(error).isInstanceOf(DownloadSessionChangedException::class.java)
            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    @Test
    fun `fixed resolver uses captured password and rejects a session change after fs get`() = runTest {
        MockWebServer().use { server ->
            var sessionCurrent = true
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    sessionCurrent = false
                    return MockResponse().setResponseCode(200).setBody(
                        """{"code":200,"data":{"raw_url":"https://cdn.example/file"}}""",
                    )
                }
            }
            val settings = localSettings(server)
            val binding = DownloadSessionBinding.create(settings.server, settings.token)
            val session = JobBoundDownloadSession.capture(binding, settings)
            val resolver = session.newResolver(
                okHttpClient = OpenListHttpClient.defaultOkHttpClient(),
                gson = Gson(),
                remotePath = "/protected/file.bin",
                pathPassword = "path-secret",
                isSessionCurrent = { sessionCurrent },
            )

            val error = runCatching {
                resolver.resolve("/protected/file.bin")
            }.exceptionOrNull()

            assertThat(error).isInstanceOf(DownloadSessionChangedException::class.java)
            val request = server.takeRequest()
            assertThat(request.getHeader("Authorization")).isEqualTo("secret-token")
            assertThat(request.body.readUtf8()).contains("path-secret")
            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    private fun localSettings(server: MockWebServer) = AppSettings(
        server = ServerProfile(
            baseUrl = server.url("/base").toString(),
            username = "alice",
            allowInsecureHttp = true,
        ),
        token = "secret-token",
    )
}
