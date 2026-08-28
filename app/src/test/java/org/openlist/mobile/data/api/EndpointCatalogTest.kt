package org.openlist.mobile.data.api

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.openlist.mobile.data.api.catalog.ApiAccess
import org.openlist.mobile.data.api.catalog.ApiHttpMethod
import org.openlist.mobile.data.api.catalog.EndpointAliasKind
import org.openlist.mobile.data.api.catalog.EndpointCatalog
import org.openlist.mobile.data.api.catalog.NonApiTransportCatalog
import org.openlist.mobile.data.api.catalog.TaskAction
import org.openlist.mobile.data.api.catalog.TaskKind
import org.openlist.mobile.data.api.catalog.TransportProtocol

class EndpointCatalogTest {
    @Test
    fun `catalog exactly matches the pinned upstream route snapshot`() {
        val resource = checkNotNull(javaClass.classLoader?.getResourceAsStream(ROUTE_SNAPSHOT)) {
            "Missing pinned OpenList route snapshot: $ROUTE_SNAPSHOT"
        }
        val expected = resource.bufferedReader().useLines { lines ->
            lines
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith('#') }
                .toList()
        }
        val actual = EndpointCatalog.endpoints
            .sortedBy { it.path }
            .map { "${it.declaredMethod.name}\t${it.path}" }

        assertThat(actual).containsExactlyElementsIn(expected).inOrder()
    }

    @Test
    fun `catalog contains exactly 199 unique canonical v4_2_5 paths`() {
        assertThat(EndpointCatalog.OPENLIST_VERSION).isEqualTo("v4.2.5")
        assertThat(EndpointCatalog.OPENLIST_COMMIT)
            .isEqualTo("cc87e88f038a5a27c8782afc7b66a3c1a3cdcb77")
        assertThat(EndpointCatalog.endpoints).hasSize(EndpointCatalog.CANONICAL_PATH_COUNT)
        assertThat(EndpointCatalog.canonicalPaths).hasSize(199)
        assertThat(EndpointCatalog.endpoints.map { it.id }.distinct()).hasSize(199)
        assertThat(EndpointCatalog.endpoints.all { it.path.startsWith("/api/") }).isTrue()
    }

    @Test
    fun `logical method distribution matches upstream router`() {
        val counts = EndpointCatalog.endpoints.groupingBy { it.method }.eachCount()

        assertThat(counts[ApiHttpMethod.ANY]).isEqualTo(11)
        assertThat(counts[ApiHttpMethod.GET]).isEqualTo(40)
        assertThat(counts[ApiHttpMethod.POST]).isEqualTo(145)
        assertThat(counts[ApiHttpMethod.PUT]).isEqualTo(3)
        assertThat(counts.values.sum()).isEqualTo(199)
    }

    @Test
    fun `access policies retain middleware distinctions`() {
        val counts = EndpointCatalog.endpoints.groupingBy { it.access }.eachCount()

        assertThat(counts[ApiAccess.PUBLIC]).isEqualTo(12)
        assertThat(counts[ApiAccess.GUEST_IF_ENABLED]).isEqualTo(32)
        assertThat(counts.getOrDefault(ApiAccess.GUEST_ALWAYS, 0)).isEqualTo(0)
        assertThat(counts[ApiAccess.AUTHN_CONTEXT]).isEqualTo(4)
        assertThat(counts[ApiAccess.NON_GUEST]).isEqualTo(97)
        assertThat(counts[ApiAccess.ADMIN]).isEqualTo(54)
    }

    @Test
    fun `split read handlers allow disabled guests only for sharing paths`() {
        listOf(
            "/api/fs/list",
            "/api/fs/get",
            "/api/fs/archive/meta",
            "/api/fs/archive/list",
        ).forEach { path ->
            val endpoint = EndpointCatalog.requireCanonical(path)
            assertThat(endpoint.accessForRequestPath("/regular/path"))
                .isEqualTo(ApiAccess.GUEST_IF_ENABLED)
            assertThat(endpoint.accessForRequestPath("/@s/share-token/file"))
                .isEqualTo(ApiAccess.GUEST_ALWAYS)
        }
    }

    @Test
    fun `task routes are generated as seven by twelve matrix with admin aliases`() {
        val expectedCanonical = TaskKind.entries.flatMap { kind ->
            TaskAction.entries.map { action -> "/api/task/${kind.segment}/${action.segment}" }
        }
        val expectedAliases = expectedCanonical.map { it.replace("/api/task/", "/api/admin/task/") }

        assertThat(TaskKind.entries).hasSize(7)
        assertThat(TaskAction.entries).hasSize(12)
        assertThat(expectedCanonical).hasSize(84)
        assertThat(EndpointCatalog.canonicalPaths).containsAtLeastElementsIn(expectedCanonical)
        assertThat(EndpointCatalog.aliases.map { it.path }).containsExactlyElementsIn(expectedAliases)
        assertThat(EndpointCatalog.aliases.map { it.path }.distinct()).hasSize(84)

        TaskKind.entries.forEach { kind ->
            TaskAction.entries.forEach { action ->
                val endpoint = EndpointCatalog.task(kind, action)
                assertThat(endpoint.preferredMethod).isEqualTo(action.method)
                assertThat(endpoint.aliases.single().kind)
                    .isEqualTo(EndpointAliasKind.LEGACY_ADMIN_TASK)
                assertThat(endpoint.aliases.single().access).isEqualTo(ApiAccess.ADMIN)
            }
        }
    }

    @Test
    fun `core paths resolve with expected methods`() {
        assertRoute("/api/auth/login", ApiHttpMethod.POST)
        assertRoute("/api/fs/list", ApiHttpMethod.ANY)
        assertRoute("/api/fs/put", ApiHttpMethod.PUT)
        assertRoute("/api/fs/multipart/chunk", ApiHttpMethod.PUT)
        assertRoute("/api/fs/get_direct_upload_info", ApiHttpMethod.POST)
        assertRoute("/api/share/list", ApiHttpMethod.ANY)
        assertRoute("/api/admin/storage/list", ApiHttpMethod.GET)
        assertRoute("/api/task/upload/retry_failed", ApiHttpMethod.POST)

        val legacy = EndpointCatalog.resolve("/api/admin/task/upload/retry_failed")
        assertThat(legacy).isNotNull()
        assertThat(legacy!!.endpoint.path).isEqualTo("/api/task/upload/retry_failed")
        assertThat(legacy.access).isEqualTo(ApiAccess.ADMIN)
    }

    @Test
    fun `admin enum covers every canonical non-task admin route`() {
        val canonicalAdmin = EndpointCatalog.endpoints.filter { it.path.startsWith("/api/admin/") }

        assertThat(AdminRoute.entries).hasSize(53)
        assertThat(AdminRoute.entries.map { it.endpoint })
            .containsExactlyElementsIn(canonicalAdmin)
        assertThat(AdminRoute.entries.map { it.endpoint.path }.distinct()).hasSize(53)
    }

    @Test
    fun `non api protocol catalog includes every server transport family`() {
        assertThat(NonApiTransportCatalog.endpoints.map { it.pathPattern }).containsAtLeast(
            "/ping",
            "/d/*path",
            "/p/*path",
            "/ad/*path",
            "/ap/*path",
            "/ae/*path",
            "/sd/:sid",
            "/sd/:sid/*path",
            "/sad/:sid",
            "/sad/:sid/*path",
            "/dav",
            "/dav/*path",
            "/s3/*path",
            "/*path",
            "/mcp",
        )
        assertThat(NonApiTransportCatalog.endpoints.map { it.protocol }.toSet()).containsExactly(
            TransportProtocol.HEALTH,
            TransportProtocol.DOWNLOAD,
            TransportProtocol.WEBDAV,
            TransportProtocol.S3,
            TransportProtocol.MCP,
        )
    }

    private fun assertRoute(path: String, method: ApiHttpMethod) {
        val endpoint = EndpointCatalog.requireCanonical(path)
        assertThat(endpoint.method).isEqualTo(method)
    }

    private companion object {
        const val ROUTE_SNAPSHOT = "openlist/v4.2.5/routes.tsv"
    }
}
