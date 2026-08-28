package org.openlist.mobile.data.api

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonArray
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class AdminApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: AdminApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = AdminApi(
            OpenListHttpClient(
                { server.url("/").toString() },
                { "admin-token" },
                { true },
            ),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `typed route dispatches with its declared verb`() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":200,"data":[]}"""))

        val result: JsonArray = api.get(AdminRoute.STORAGE_LIST)

        assertThat(result.size()).isEqualTo(0)
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.path).isEqualTo("/api/admin/storage/list")
    }

    @Test
    fun `dynamic accepts relative canonical admin path`() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":200,"data":{"running":true}}"""))

        val result: com.google.gson.JsonObject = api.dynamic("scan/progress")

        assertThat(result["running"].asBoolean).isTrue()
        assertThat(server.takeRequest().path).isEqualTo("/api/admin/scan/progress")
    }

    @Test
    fun `dynamic resolves legacy task alias without duplicating catalog endpoint`() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":200,"data":[]}"""))

        val result: JsonArray = api.dynamic("task/upload/undone")

        assertThat(result.size()).isEqualTo(0)
        assertThat(server.takeRequest().path).isEqualTo("/api/admin/task/upload/undone")
    }
}
