package org.openlist.mobile.data.api

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.openlist.mobile.core.model.OpenListUser

class OpenListApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: OpenListApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = OpenListApi(
            OpenListHttpClient(
                baseUrl = { server.url("/").toString() },
                token = { "token" },
                allowInsecureHttp = { true },
            ),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `update me sends both upstream identity fields and documented old password`() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":200,"data":null}"""))

        api.updateMe(
            OpenListUser(username = "renamed", ssoId = "sso-user", permission = 123),
            password = "new-password",
            currentPassword = "old-password",
        )

        val body = server.takeRequest().body.readUtf8()
        assertThat(body).contains("\"username\":\"renamed\"")
        assertThat(body).contains("\"password\":\"new-password\"")
        assertThat(body).contains("\"sso_id\":\"sso-user\"")
        assertThat(body).contains("\"old_password\":\"old-password\"")
        assertThat(body).doesNotContain("permission")
    }

    @Test
    fun `list defaults satisfy documented page bounds`() = runTest {
        server.enqueue(MockResponse().setBody(listingResponse(emptyList(), total = 0)))

        api.list("/documents")

        val request = server.takeRequest()
        val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertThat(request.path).isEqualTo("/api/fs/list")
        assertThat(body.get("page").asInt).isEqualTo(1)
        assertThat(body.get("per_page").asInt).isEqualTo(100)
    }

    @Test
    fun `list rejects pages outside documented bounds before making a request`() = runTest {
        val invalidPage = runCatching { api.list("/", page = 0) }.exceptionOrNull()
        val invalidSmallPage = runCatching { api.list("/", perPage = 0) }.exceptionOrNull()
        val invalidLargePage = runCatching { api.list("/", perPage = 101) }.exceptionOrNull()

        assertThat(invalidPage).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(invalidSmallPage).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(invalidLargePage).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `list all aggregates pages and refreshes only the first request`() = runTest {
        val firstPageNames = (1..100).map { "entry-$it" }
        server.enqueue(MockResponse().setBody(listingResponse(firstPageNames, total = 101)))
        server.enqueue(MockResponse().setBody(listingResponse(listOf("entry-101"), total = 101)))

        val listing = api.listAll(path = "/documents", password = "directory-secret", refresh = true)

        assertThat(listing.content.map { it.name })
            .containsExactlyElementsIn(firstPageNames + "entry-101")
            .inOrder()
        assertThat(listing.total).isEqualTo(101)

        val firstRequest = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        val secondRequest = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        assertThat(firstRequest.get("page").asInt).isEqualTo(1)
        assertThat(firstRequest.get("per_page").asInt).isEqualTo(100)
        assertThat(firstRequest.get("refresh").asBoolean).isTrue()
        assertThat(firstRequest.get("password").asString).isEqualTo("directory-secret")
        assertThat(secondRequest.get("page").asInt).isEqualTo(2)
        assertThat(secondRequest.get("per_page").asInt).isEqualTo(100)
        assertThat(secondRequest.get("refresh").asBoolean).isFalse()
        assertThat(secondRequest.get("password").asString).isEqualTo("directory-secret")
    }

    @Test
    fun `two factor setup accepts documented and upstream QR field names`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"code":200,"data":{"qr_code":"documented-qr","secret":"one"}}""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"code":200,"data":{"qr":"upstream-qr","secret":"two"}}""",
            ),
        )

        val documented = api.generateTwoFactor()
        val upstream = api.generateTwoFactor()

        assertThat(documented.qr).isEqualTo("documented-qr")
        assertThat(documented.secret).isEqualTo("one")
        assertThat(upstream.qr).isEqualTo("upstream-qr")
        assertThat(upstream.secret).isEqualTo("two")
    }

    @Test
    fun `directory entries retain upstream metadata and decode documented path`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"code":200,"data":[{"name":"photos","path":"/archive/photos","modified":"revision"}]}""",
            ),
        )

        val entry = api.directories("/archive").single()

        assertThat(entry.name).isEqualTo("photos")
        assertThat(entry.path).isEqualTo("/archive/photos")
        assertThat(entry.modified).isEqualTo("revision")
    }

    @Test
    fun `file details decode documented object and storage fields without dropping extensions`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{
                    "code":200,
                    "data":{
                        "id":"object-id",
                        "path":"/documents/report.pdf",
                        "name":"report.pdf",
                        "mount_details":{
                            "driver_name":"Local",
                            "total_space":1000,
                            "used_space":400,
                            "free_space":600
                        }
                    }
                }""".trimIndent(),
            ),
        )

        val details = api.get("/documents/report.pdf")
        val item = details.asObject()

        assertThat(details.id).isEqualTo("object-id")
        assertThat(details.path).isEqualTo("/documents/report.pdf")
        assertThat(details.mountDetails?.driverName).isEqualTo("Local")
        assertThat(details.mountDetails?.usedSpace).isEqualTo(400)
        assertThat(item.id).isEqualTo(details.id)
        assertThat(item.path).isEqualTo(details.path)
    }

    @Test
    fun `offline download exposes every server delete policy`() = runTest {
        OfflineDownloadDeletePolicy.entries.forEach { policy ->
            server.enqueue(MockResponse().setBody("""{"code":200,"data":{}}"""))

            api.addOfflineDownload("/downloads", listOf("https://example.test/file"), "tool", policy)

            assertThat(server.takeRequest().body.readUtf8())
                .contains("\"delete_policy\":\"${policy.wireValue}\"")
        }
    }

    private fun listingResponse(names: List<String>, total: Long): String {
        val content = names.joinToString(",") { name -> """{"name":"$name"}""" }
        return """{"code":200,"data":{"content":[$content],"total":$total}}"""
    }
}
