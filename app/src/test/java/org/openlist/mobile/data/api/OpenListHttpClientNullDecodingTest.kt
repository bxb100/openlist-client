package org.openlist.mobile.data.api

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.openlist.mobile.core.model.DirectoryListing
import org.openlist.mobile.core.model.FileDetails
import org.openlist.mobile.core.model.SearchResult
import org.openlist.mobile.data.api.dto.SearchData

class OpenListHttpClientNullDecodingTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OpenListHttpClient
    private lateinit var api: OpenListApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OpenListHttpClient(
            baseUrl = { server.url("/").toString() },
            token = { null },
            allowInsecureHttp = { true },
        )
        api = OpenListApi(client)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `successful null data becomes empty list map or set`() = runTest {
        repeat(3) {
            server.enqueue(MockResponse().setBody("""{"code":200,"data":null}"""))
        }

        val list: List<String> = client.get("/api/test/list")
        val map: Map<String, String> = client.get("/api/test/map")
        val set: Set<Int> = client.get("/api/test/set")

        assertThat(list).isEmpty()
        assertThat(map).isEmpty()
        assertThat(set).isEmpty()
    }

    @Test
    fun `null collection fields remain non-null through real API calls`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"code":200,"data":{"content":null,"direct_upload_tools":null}}""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"code":200,"data":{"name":"song.flac","related":null}}""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"code":200,"data":{"content":null,"total":0}}""",
            ),
        )

        val listing = api.list("/")
        val details = api.get("/song.flac")
        val search = api.search("/", "song")

        assertThat(listing.content).isEmpty()
        assertThat(listing.directUploadTools).isEmpty()
        assertThat(details.related).isEmpty()
        assertThat(search.content).isEmpty()
    }

    @Test
    fun `field adapters normalize explicit wire null with plain Gson`() {
        val gson = Gson()

        val listing = gson.fromJson(
            """{"content":null,"direct_upload_tools":null}""",
            DirectoryListing::class.java,
        )
        val details = gson.fromJson("""{"related":null}""", FileDetails::class.java)
        val search = gson.fromJson("""{"content":null}""", SearchData::class.java)
        val legacySearch = gson.fromJson("""{"content":null}""", SearchResult::class.java)

        assertThat(listing.content).isEmpty()
        assertThat(listing.directUploadTools).isEmpty()
        assertThat(details.related).isEmpty()
        assertThat(search.content).isEmpty()
        assertThat(legacySearch.content).isEmpty()
    }
}
