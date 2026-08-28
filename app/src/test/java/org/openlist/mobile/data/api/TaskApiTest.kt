package org.openlist.mobile.data.api

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class TaskApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: TaskApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = TaskApi(
            OpenListHttpClient(
                { server.url("/").toString() },
                { "token" },
                { true },
            ),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `targeted operation sends tid query`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"code":200,"data":{"id":"task-1","name":"upload","state":1}}""",
            ),
        )

        val info = api.info(TaskKind.UPLOAD, "task-1")

        assertThat(info.id).isEqualTo("task-1")
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/api/task/upload/info?tid=task-1")
    }

    @Test
    fun `batch operation sends task id array body`() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"code":200,"data":{"missing":"task not found"}}"""),
        )

        val failures = api.cancelSome(TaskKind.COPY, listOf("first", "missing"))

        assertThat(failures).containsExactly("missing", "task not found")
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/api/task/copy/cancel_some")
        assertThat(request.body.readUtf8()).isEqualTo("[\"first\",\"missing\"]")
    }

    @Test
    fun `legacy flag selects admin task alias`() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":200,"data":[]}"""))

        val tasks: List<org.openlist.mobile.data.api.dto.TaskInfo> = api.execute(
            kind = TaskKind.DECOMPRESS_UPLOAD,
            action = TaskAction.DONE,
            legacyAdminAlias = true,
        )

        assertThat(tasks).isEmpty()
        assertThat(server.takeRequest().path)
            .isEqualTo("/api/admin/task/decompress_upload/done")
    }

    @Test
    fun `request shape validation rejects missing id before network`() = runTest {
        val failure = runCatching {
            api.execute<Unit>(TaskKind.MOVE, TaskAction.RETRY)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(server.requestCount).isEqualTo(0)
    }
}
