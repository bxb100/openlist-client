package org.openlist.mobile.data.api

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonObject
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.openlist.mobile.data.api.catalog.ApiHttpMethod
import org.openlist.mobile.data.api.catalog.EndpointCatalog
import org.openlist.mobile.data.api.catalog.GenericOpenListService
import org.openlist.mobile.data.api.catalog.NonApiTransportCatalog

class GenericOpenListServiceTest {
    private lateinit var server: MockWebServer
    private lateinit var service: GenericOpenListService

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        service = GenericOpenListService(
            OpenListHttpClient(
                baseUrl = { server.url("/openlist/").toString() },
                token = { "jwt-token" },
                allowInsecureHttp = { true },
            ),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `catalog call uses configured base path auth and envelope parsing`() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"code":200,"message":"success","data":{"ok":true}}"""),
        )

        val result: JsonObject = service.call(
            EndpointCatalog.requireCanonical("/api/fs/get"),
            body = mapOf("path" to "/music/song.flac"),
        )

        assertThat(result["ok"].asBoolean).isTrue()
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/openlist/api/fs/get")
        assertThat(request.getHeader("Authorization")).isEqualTo("jwt-token")
        assertThat(request.getHeader("Content-Type")).isEqualTo("application/json; charset=utf-8")
        assertThat(request.body.readUtf8()).contains("/music/song.flac")
    }

    @Test
    fun `any route accepts an alternate registered wire method`() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":200,"data":{"theme":"dark"}}"""))

        val result: JsonObject = service.call(
            endpoint = EndpointCatalog.requireCanonical("/api/public/settings"),
            method = ApiHttpMethod.POST,
        )

        assertThat(result["theme"].asString).isEqualTo("dark")
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.body.size).isEqualTo(0L)
        assertThat(request.getHeader("Content-Type")).isNull()
    }

    @Test
    fun `catalog call preserves an explicit empty JSON object`() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":200,"data":{}}"""))

        service.call<JsonObject>(
            endpoint = EndpointCatalog.requireCanonical("/api/public/settings"),
            method = ApiHttpMethod.POST,
            body = emptyMap<String, Any>(),
        )

        val request = server.takeRequest()
        assertThat(request.body.readUtf8()).isEqualTo("{}")
        assertThat(request.getHeader("Content-Type")).isEqualTo("application/json; charset=utf-8")
    }

    @Test
    fun `dynamic path overload resolves registered legacy alias`() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":200,"data":[]}"""))

        val result: List<org.openlist.mobile.data.api.dto.TaskInfo> =
            service.call("/api/admin/task/upload/done")

        assertThat(result).isEmpty()
        assertThat(server.takeRequest().path).isEqualTo("/openlist/api/admin/task/upload/done")
    }

    @Test
    fun `catalog call rejects a verb not registered for route`() = runTest {
        val failure = runCatching {
            service.callUnit(
                endpoint = EndpointCatalog.requireCanonical("/api/auth/login"),
                method = ApiHttpMethod.GET,
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `raw escape hatch returns non envelope protocol response`() = runTest {
        server.enqueue(MockResponse().setResponseCode(206).setBody("bytes"))

        service.raw(
            path = "/p/media/song.flac",
            method = "GET",
            headers = mapOf("Range" to "bytes=10-14"),
        ).use { response ->
            assertThat(response.code).isEqualTo(206)
            assertThat(response.body.string()).isEqualTo("bytes")
        }

        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/openlist/p/media/song.flac")
        assertThat(request.getHeader("Range")).isEqualTo("bytes=10-14")
    }

    @Test
    fun `transport overload preserves protocol authorization instead of api token`() = runTest {
        server.enqueue(MockResponse().setResponseCode(207).setBody("<multistatus />"))
        val webDav = NonApiTransportCatalog.endpoints.single { it.id == "webdav.path" }

        service.raw(
            transport = webDav,
            concretePath = "/dav/music",
            method = ApiHttpMethod.PROPFIND,
            headers = mapOf("Authorization" to "Basic dXNlcjpwYXNz"),
        ).use { response ->
            assertThat(response.code).isEqualTo(207)
        }

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("PROPFIND")
        assertThat(request.getHeader("Authorization")).isEqualTo("Basic dXNlcjpwYXNz")
    }

    @Test
    fun `mcp transport preserves an explicit administrator token`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val mcp = NonApiTransportCatalog.endpoints.single { it.id == "mcp" }

        service.raw(
            transport = mcp,
            concretePath = "/mcp",
            method = ApiHttpMethod.POST,
            headers = mapOf("Authorization" to "dedicated-admin-token"),
        ).use { response ->
            assertThat(response.code).isEqualTo(200)
        }

        val request = server.takeRequest()
        assertThat(request.getHeader("Authorization")).isEqualTo("dedicated-admin-token")
        assertThat(request.body.size).isEqualTo(0L)
        assertThat(request.getHeader("Content-Type")).isNull()
    }
}
