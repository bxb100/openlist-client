package org.openlist.mobile.data.download

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Test
import org.openlist.mobile.media.MediaUrlResolver
import org.openlist.mobile.media.ResolvedMediaUrl
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.ArrayDeque

class DownloadEngineTest {
    @Test
    fun `download resolves lazily strips credentials and truncates stale target`() = runBlocking {
        val resolver = RecordingResolver(
            headers = mapOf("Authorization" to "secret", "Cookie" to "secret", "X-Safe" to "yes"),
        )
        val transport = QueueTransport(ResponseSpec(200, "fresh".encodeToByteArray()))
        val target = MemoryTarget("stale trailing bytes".encodeToByteArray())
        val engine = DownloadEngine(resolver, transport.client)

        assertThat(resolver.calls).isEqualTo(0)
        val result = engine.download(DownloadRequest("/file.bin", expectedBytes = 5), target)

        assertThat(resolver.calls).isEqualTo(1)
        assertThat(result.downloadedBytes).isEqualTo(5)
        assertThat(target.bytes.toString(Charsets.UTF_8)).isEqualTo("fresh")
        assertThat(target.openCount).isEqualTo(1)
        assertThat(transport.requests.single().header("Authorization")).isNull()
        assertThat(transport.requests.single().header("Cookie")).isNull()
        assertThat(transport.requests.single().header("X-Safe")).isEqualTo("yes")
    }

    @Test
    fun `expired raw url re-resolves once before opening target`() = runBlocking {
        val resolver = RecordingResolver()
        val transport = QueueTransport(
            ResponseSpec(403, ByteArray(0)),
            ResponseSpec(200, "ok".encodeToByteArray()),
        )
        val target = MemoryTarget()

        DownloadEngine(resolver, transport.client)
            .download(DownloadRequest("/file.bin", expectedBytes = 2), target)

        assertThat(resolver.calls).isEqualTo(2)
        assertThat(transport.requests).hasSize(2)
        assertThat(target.openCount).isEqualTo(1)
        assertThat(target.bytes.toString(Charsets.UTF_8)).isEqualTo("ok")
    }

    @Test
    fun `partial unknown-length response clears target and reports integrity failure`() = runBlocking {
        val transport = QueueTransport(
            ResponseSpec(200, "abc".encodeToByteArray(), declaredLength = -1),
        )
        val target = MemoryTarget("old".encodeToByteArray())

        val error = runCatching {
            DownloadEngine(RecordingResolver(), transport.client)
                .download(DownloadRequest("/file.bin", expectedBytes = 5), target)
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(DownloadIntegrityException::class.java)
        assertThat(target.clearCount).isEqualTo(1)
        assertThat(target.bytes).isEmpty()
    }

    @Test
    fun `rate limit is surfaced without touching target`() = runBlocking {
        val target = MemoryTarget("old".encodeToByteArray())

        val error = runCatching {
            DownloadEngine(
                RecordingResolver(),
                QueueTransport(ResponseSpec(429, ByteArray(0))).client,
            ).download(DownloadRequest("/file.bin"), target)
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(DownloadHttpException::class.java)
        assertThat((error as DownloadHttpException).statusCode).isEqualTo(429)
        assertThat(DownloadRetryClassifier.classify(error).retryable).isTrue()
        assertThat(target.openCount).isEqualTo(0)
        assertThat(target.bytes.toString(Charsets.UTF_8)).isEqualTo("old")
    }

    @Test
    fun `session change after lazy resolution blocks raw request and target write`() = runBlocking {
        var sessionCurrent = true
        val transport = QueueTransport(ResponseSpec(200, "unsafe".encodeToByteArray()))
        val target = MemoryTarget("old".encodeToByteArray())
        val resolver = MediaUrlResolver {
            sessionCurrent = false
            ResolvedMediaUrl("https://download.example/file")
        }

        val error = runCatching {
            DownloadEngine(
                resolver = resolver,
                downloadClient = transport.client,
                requireSessionCurrent = {
                    if (!sessionCurrent) throw DownloadSessionChangedException()
                },
            ).download(DownloadRequest("/file.bin"), target)
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(DownloadSessionChangedException::class.java)
        assertThat(transport.requests).isEmpty()
        assertThat(target.openCount).isEqualTo(0)
        assertThat(target.bytes.toString(Charsets.UTF_8)).isEqualTo("old")
    }

    private class RecordingResolver(
        private val headers: Map<String, String> = emptyMap(),
    ) : MediaUrlResolver {
        var calls = 0

        override suspend fun resolve(remotePath: String): ResolvedMediaUrl {
            calls++
            return ResolvedMediaUrl("https://download.example/$calls", headers)
        }
    }

    private data class ResponseSpec(
        val code: Int,
        val bytes: ByteArray,
        val declaredLength: Long = bytes.size.toLong(),
    )

    private class QueueTransport(vararg responses: ResponseSpec) : Interceptor {
        private val responses = ArrayDeque(responses.toList())
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor(this).build()

        override fun intercept(chain: Interceptor.Chain): Response {
            requests += chain.request()
            val spec = responses.removeFirst()
            val body = if (spec.declaredLength >= 0) {
                spec.bytes.toResponseBody(null)
            } else {
                UnknownLengthBody(spec.bytes)
            }
            return Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(spec.code)
                .message("test")
                .body(body)
                .build()
        }
    }

    private class UnknownLengthBody(bytes: ByteArray) : ResponseBody() {
        private val buffer = Buffer().write(bytes)
        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = -1
        override fun source(): BufferedSource = buffer
    }

    private class MemoryTarget(initial: ByteArray = ByteArray(0)) : DownloadTarget {
        private var output = ByteArrayOutputStream().apply { write(initial) }
        var openCount = 0
        var clearCount = 0
        val bytes: ByteArray get() = output.toByteArray()

        override fun openTruncated(): OutputStream {
            openCount++
            output = ByteArrayOutputStream()
            return output
        }

        override fun clear() {
            clearCount++
            output = ByteArrayOutputStream()
        }
    }
}
