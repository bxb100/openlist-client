package org.openlist.mobile.data.api

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonObject
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class OpenListHttpClientRequestBodyTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OpenListHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OpenListHttpClient(
            baseUrl = { server.url("/").toString() },
            token = { null },
            allowInsecureHttp = { true },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `post with null body sends zero bytes without a JSON content type`() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":200,"data":{"accepted":true}}"""))

        val result: JsonObject = client.post("/api/test", body = null)

        assertThat(result["accepted"].asBoolean).isTrue()
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.body.size).isEqualTo(0L)
        assertThat(request.getHeader("Content-Type")).isNull()
    }

    @Test
    fun `post with an explicit empty map sends a JSON object`() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":200,"data":{}}"""))

        client.post<JsonObject>("/api/test", body = emptyMap<String, Any>())

        val request = server.takeRequest()
        assertThat(request.body.readUtf8()).isEqualTo("{}")
        assertThat(request.getHeader("Content-Type")).isEqualTo("application/json; charset=utf-8")
    }
}
