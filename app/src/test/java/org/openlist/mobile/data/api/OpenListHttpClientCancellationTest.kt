package org.openlist.mobile.data.api

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Test
import kotlin.system.measureTimeMillis

class OpenListHttpClientCancellationTest {
    @Test
    fun `coroutine timeout cancels the physical okhttp call`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client = OpenListHttpClient(
                baseUrl = { server.url("/").toString() },
                token = { null },
                allowInsecureHttp = { true },
            )

            var timeout: Throwable? = null
            val elapsed = measureTimeMillis {
                timeout = runCatching {
                    withTimeout(250L) { client.get<Map<String, String>>("/api/never") }
                }.exceptionOrNull()
            }

            assertThat(timeout).isInstanceOf(TimeoutCancellationException::class.java)
            assertThat(elapsed).isLessThan(2_000L)
        }
    }
}
