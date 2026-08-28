package org.openlist.mobile.media

import android.net.TestUri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.ArrayDeque

class OpenListSegmentedRangeDataSourceTest {
    @Test
    fun `content range parser preserves positions above four gib`() {
        val start = 5L * GIB + 17L
        val end = start + 1023L
        val total = 9L * GIB + 31L

        assertThat(parseOpenListContentRange("bytes $start-$end/$total")).isEqualTo(
            OpenListContentRange.Satisfied(start, end, total),
        )
        assertThat(parseOpenListContentRange("bytes */$total")).isEqualTo(
            OpenListContentRange.Unsatisfied(total),
        )
    }

    @Test
    fun `open reports resource remainder and capped 206 responses resume`() {
        val upstream = ScriptedHttpDataSource(
            response(206, "bytes 0-2/8", "abc"),
            response(206, "bytes 3-4/8", "de"),
            response(206, "bytes 5-7/8", "fgh"),
        )
        val source = OpenListSegmentedRangeDataSource(upstream)

        assertThat(source.open(dataSpec(position = 0L))).isEqualTo(8L)
        assertThat(readAll(source)).isEqualTo("abcdefgh")
        assertThat(upstream.openedSpecs.map(DataSpec::position)).containsExactly(0L, 3L, 5L)
            .inOrder()
        assertThat(upstream.openedSpecs.map(DataSpec::length))
            .containsExactly(C.LENGTH_UNSET.toLong(), 5L, 3L)
            .inOrder()
        source.close()
    }

    @Test
    fun `explicit request length stops resumed reads before resource end`() {
        val upstream = ScriptedHttpDataSource(
            response(206, "bytes 5-6/100", "ab"),
            response(206, "bytes 7-9/100", "cde"),
        )
        val source = OpenListSegmentedRangeDataSource(upstream)

        assertThat(source.open(dataSpec(position = 5L, length = 4L))).isEqualTo(4L)
        assertThat(readAll(source)).isEqualTo("abcd")
        assertThat(upstream.openedSpecs.map(DataSpec::position)).containsExactly(5L, 7L).inOrder()
        assertThat(upstream.openedSpecs.map(DataSpec::length)).containsExactly(4L, 2L).inOrder()
        source.close()
    }

    @Test
    fun `unknown total resumes until exact eof 416`() {
        val upstream = ScriptedHttpDataSource(
            response(206, "bytes 0-1/*", "ab"),
            response(206, "bytes 2-3/*", "cd"),
            response(416, "bytes */4", ""),
        )
        val source = OpenListSegmentedRangeDataSource(upstream)

        assertThat(source.open(dataSpec(position = 0L))).isEqualTo(C.LENGTH_UNSET.toLong())
        assertThat(readAll(source)).isEqualTo("abcd")
        assertThat(upstream.openedSpecs.map(DataSpec::position)).containsExactly(0L, 2L, 4L)
            .inOrder()
        source.close()
    }

    @Test
    fun `exact eof 416 opens as empty input`() {
        val eof = 6L * GIB + 23L
        val upstream = ScriptedHttpDataSource(response(416, "bytes */$eof", ""))
        val source = OpenListSegmentedRangeDataSource(upstream)

        assertThat(source.open(dataSpec(position = eof))).isEqualTo(0L)
        assertThat(source.read(ByteArray(1), 0, 1)).isEqualTo(C.RESULT_END_OF_INPUT)
        source.close()
    }

    @Test
    fun `large range returns a Long remainder without truncation`() {
        val start = 5L * GIB + 7L
        val total = 10L * GIB + 19L
        val upstream = ScriptedHttpDataSource(
            response(206, "bytes $start-${start + 1L}/$total", "ab"),
        )
        val source = OpenListSegmentedRangeDataSource(upstream)

        val remaining = source.open(dataSpec(position = start))

        assertThat(remaining).isEqualTo(total - start)
        assertThat(remaining).isGreaterThan(Int.MAX_VALUE.toLong())
        source.close()
    }

    @Test
    fun `a 200 response cannot satisfy a nonzero range`() {
        val source = OpenListSegmentedRangeDataSource(
            ScriptedHttpDataSource(response(200, null, "from the beginning")),
        )

        val error = assertThrows(IOException::class.java) {
            source.open(dataSpec(position = 5L * GIB))
        }

        assertThat(error).hasMessageThat().isEqualTo("Server ignored a non-zero HTTP range request")
    }

    @Test
    fun `a full response ending before an explicit request is rejected`() {
        val source = OpenListSegmentedRangeDataSource(
            ScriptedHttpDataSource(response(200, null, "short")),
        )
        source.open(dataSpec(position = 0L, length = 10L))

        val error = assertThrows(IOException::class.java) { readAll(source) }

        assertThat(error).hasMessageThat()
            .isEqualTo("HTTP response ended before the requested range")
        source.close()
    }

    @Test
    fun `a mismatched content range start is rejected`() {
        val source = OpenListSegmentedRangeDataSource(
            ScriptedHttpDataSource(response(206, "bytes 99-100/200", "ab")),
        )

        val error = assertThrows(IOException::class.java) {
            source.open(dataSpec(position = 100L))
        }

        assertThat(error).hasMessageThat()
            .isEqualTo("HTTP range response started at an unexpected position")
    }

    @Test
    fun `an empty partial response fails instead of reopening forever`() {
        val source = OpenListSegmentedRangeDataSource(
            ScriptedHttpDataSource(response(206, "bytes 0-4/10", "")),
        )
        source.open(dataSpec(position = 0L))

        val error = assertThrows(IOException::class.java) {
            source.read(ByteArray(8), 0, 8)
        }

        assertThat(error).hasMessageThat().isEqualTo("HTTP range response made no progress")
        source.close()
    }

    @Test
    fun `network guard rejects ignored range before a response body can be skipped`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("unrequested prefix"))
            val client = OkHttpClient().withOpenListStrictRangeResponses()
            val request = Request.Builder()
                .url(server.url("/media"))
                .header("Range", "bytes=${5L * GIB}-")
                .build()

            val error = assertThrows(IOException::class.java) {
                client.newCall(request).execute().use { }
            }

            assertThat(error).hasMessageThat()
                .isEqualTo("Server ignored a non-zero HTTP range request")
            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    @Test
    fun `real okhttp source turns exact eof 416 into an empty read`() {
        val eof = 6L * GIB + 23L
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(416)
                    .setHeader("Content-Range", "bytes */$eof"),
            )
            val client = OkHttpClient().withOpenListStrictRangeResponses()
            val source = OpenListSegmentedRangeDataSource(
                OkHttpDataSource.Factory(client).createDataSource(),
            )

            assertThat(source.open(dataSpec(server.url("/media").toString(), eof))).isEqualTo(0L)
            assertThat(source.read(ByteArray(1), 0, 1)).isEqualTo(C.RESULT_END_OF_INPUT)
            source.close()

            assertThat(server.takeRequest().getHeader("Range")).isEqualTo("bytes=$eof-")
        }
    }

    @Test
    fun `real okhttp source rejects a 416 beyond eof`() {
        val eof = 6L * GIB + 23L
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(416)
                    .setHeader("Content-Range", "bytes */$eof"),
            )
            val client = OkHttpClient().withOpenListStrictRangeResponses()
            val source = OpenListSegmentedRangeDataSource(
                OkHttpDataSource.Factory(client).createDataSource(),
            )

            val error = assertThrows(HttpDataSource.InvalidResponseCodeException::class.java) {
                source.open(dataSpec(server.url("/media").toString(), eof + 1L))
            }

            assertThat(error.responseCode).isEqualTo(416)
            assertThat(server.takeRequest().getHeader("Range")).isEqualTo("bytes=${eof + 1L}-")
        }
    }

    @Test
    fun `real okhttp source resumes capped responses at a large nonzero position`() {
        val start = 5L * GIB + 17L
        val total = start + 8L
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes $start-${start + 2L}/$total")
                    .setBody("abc"),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes ${start + 3L}-${total - 1L}/$total")
                    .setBody("defgh"),
            )
            val client = OkHttpClient().withOpenListStrictRangeResponses()
            val source = OpenListSegmentedRangeDataSource(
                OkHttpDataSource.Factory(client).createDataSource(),
            )

            assertThat(source.open(dataSpec(server.url("/media").toString(), start)))
                .isEqualTo(total - start)
            assertThat(readAll(source)).isEqualTo("abcdefgh")
            source.close()

            assertThat(server.takeRequest().getHeader("Range")).isEqualTo("bytes=$start-")
            assertThat(server.takeRequest().getHeader("Range"))
                .isEqualTo("bytes=${start + 3L}-${total - 1L}")
        }
    }

    @Test
    fun `invalid and overflowing content ranges are rejected`() {
        assertThat(parseOpenListContentRange("bytes 10-9/20")).isNull()
        assertThat(parseOpenListContentRange("bytes 0-20/20")).isNull()
        assertThat(parseOpenListContentRange("bytes 0-${Long.MAX_VALUE}/*")).isNull()
        assertThat(parseOpenListContentRange("bytes */*")).isNull()
    }

    private fun dataSpec(
        position: Long,
        length: Long = C.LENGTH_UNSET.toLong(),
    ): DataSpec = DataSpec.Builder()
        .setUri(TestUri.INSTANCE)
        .setPosition(position)
        .setLength(length)
        .build()

    private fun dataSpec(
        uri: String,
        position: Long,
        length: Long = C.LENGTH_UNSET.toLong(),
    ): DataSpec = DataSpec.Builder()
        .setUri(TestUri.from(uri))
        .setPosition(position)
        .setLength(length)
        .build()

    private fun readAll(source: OpenListSegmentedRangeDataSource): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(3)
        repeat(32) {
            val read = source.read(buffer, 0, buffer.size)
            if (read == C.RESULT_END_OF_INPUT) return output.toString(Charsets.UTF_8.name())
            output.write(buffer, 0, read)
        }
        throw AssertionError("DataSource did not reach end of input")
    }

    private fun response(code: Int, contentRange: String?, body: String): FakeResponse =
        FakeResponse(
            code = code,
            headers = contentRange?.let { mapOf("Content-Range" to listOf(it)) }.orEmpty(),
            body = body.toByteArray(),
        )

    private data class FakeResponse(
        val code: Int,
        val headers: Map<String, List<String>>,
        val body: ByteArray,
    )

    private class ScriptedHttpDataSource(
        vararg responses: FakeResponse,
    ) : HttpDataSource {
        private val responses = ArrayDeque(responses.toList())
        private var current: FakeResponse? = null
        private var readPosition = 0
        val openedSpecs = mutableListOf<DataSpec>()

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long {
            openedSpecs += dataSpec
            current = responses.removeFirst()
            readPosition = 0
            return current?.body?.size?.toLong() ?: 0L
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val body = checkNotNull(current).body
            if (readPosition == body.size) return C.RESULT_END_OF_INPUT
            val count = minOf(length, body.size - readPosition)
            body.copyInto(buffer, offset, readPosition, readPosition + count)
            readPosition += count
            return count
        }

        override fun getUri() = TestUri.INSTANCE

        override fun getResponseCode(): Int = current?.code ?: -1

        override fun getResponseHeaders(): Map<String, List<String>> = current?.headers.orEmpty()

        override fun setRequestProperty(name: String, value: String) = Unit

        override fun clearRequestProperty(name: String) = Unit

        override fun clearAllRequestProperties() = Unit

        override fun close() {
            current = null
            readPosition = 0
        }
    }

    private companion object {
        const val GIB = 1024L * 1024L * 1024L
    }
}
