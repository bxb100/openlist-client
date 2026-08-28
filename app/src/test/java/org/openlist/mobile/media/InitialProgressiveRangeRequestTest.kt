package org.openlist.mobile.media

import android.net.TestUri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test

class InitialProgressiveRangeRequestTest {
    @Test
    fun `fongmi forced open ended initial range becomes a normal get`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("complete"))
            val source = OpenListSegmentedRangeDataSource(
                OkHttpDataSource.Factory(
                    OkHttpClient().withOpenListStrictRangeResponses(),
                ).createDataSource(),
            )

            assertThat(source.open(dataSpec(server.url("/movie.mp4").toString())))
                .isEqualTo(8L)
            source.close()

            assertThat(server.takeRequest().getHeader("Range")).isNull()
        }
    }

    @Test
    fun `bounded initial range is preserved`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 0-3/100")
                    .setBody("test"),
            )
            val source = OpenListSegmentedRangeDataSource(
                OkHttpDataSource.Factory(
                    OkHttpClient().withOpenListStrictRangeResponses(),
                ).createDataSource(),
            )

            assertThat(
                source.open(
                    dataSpec(server.url("/movie.mp4").toString(), length = 4L),
                ),
            ).isEqualTo(4L)
            source.close()

            assertThat(server.takeRequest().getHeader("Range")).isEqualTo("bytes=0-3")
        }
    }

    private fun dataSpec(
        uri: String,
        position: Long = 0L,
        length: Long = C.LENGTH_UNSET.toLong(),
    ): DataSpec = DataSpec.Builder()
        .setUri(TestUri.from(uri))
        .setPosition(position)
        .setLength(length)
        .build()
}
