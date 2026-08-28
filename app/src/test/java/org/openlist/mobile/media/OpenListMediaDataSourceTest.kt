package org.openlist.mobile.media

import android.net.TestUri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test

class OpenListMediaDataSourceTest {
    @After
    fun tearDown() {
        OpenListMediaRequestRegistry.clearForTest()
    }

    @Test
    fun `registry retains normalized path and optional known size`() {
        val knownSize = 5L * GIB + 37L

        val request = OpenListMediaRequestRegistry.register(
            remotePath = "//video/large.mp4/",
            knownSize = knownSize,
        )

        assertThat(OpenListMediaRequestRegistry.detailsOrNull(request.mediaId)).isEqualTo(
            RegisteredMediaRequestDetails(
                remotePath = "/video/large.mp4",
                knownSize = knownSize,
            ),
        )
    }

    @Test
    fun `known size above four gib is preserved without integer truncation`() {
        val knownSize = 5L * GIB + 123L
        val original = dataSpec(position = 0L)

        val resolved = original.withKnownRemainingLength(knownSize)

        assertThat(resolved.position).isEqualTo(0L)
        assertThat(resolved.length).isEqualTo(knownSize)
        assertThat(resolved.uri).isEqualTo(original.uri)
    }

    @Test
    fun `position above two gib produces the exact remaining length`() {
        val knownSize = 7L * GIB + 29L
        val position = 3L * GIB + 11L
        val original = dataSpec(position = position)

        val resolved = original.withKnownRemainingLength(knownSize)

        assertThat(resolved.position).isEqualTo(position)
        assertThat(resolved.length).isEqualTo(knownSize - position)
    }

    @Test
    fun `explicit range length is never replaced by known object size`() {
        val position = 4L * GIB + 9L
        val explicitLength = 128L * 1024L
        val original = dataSpec(position = position, length = explicitLength)

        val resolved = original.withKnownRemainingLength(9L * GIB)

        assertThat(resolved).isSameInstanceAs(original)
        assertThat(resolved.position).isEqualTo(position)
        assertThat(resolved.length).isEqualTo(explicitLength)
    }

    @Test
    fun `position at or beyond known size remains unset without overflow`() {
        val knownSize = Long.MAX_VALUE - 1L

        listOf(knownSize, Long.MAX_VALUE).forEach { position ->
            val original = dataSpec(position = position)

            val resolved = original.withKnownRemainingLength(knownSize)

            assertThat(resolved).isSameInstanceAs(original)
            assertThat(resolved.position).isEqualTo(position)
            assertThat(resolved.length).isEqualTo(C.LENGTH_UNSET.toLong())
        }
    }

    private fun dataSpec(
        position: Long,
        length: Long = C.LENGTH_UNSET.toLong(),
    ): DataSpec = DataSpec.Builder()
        .setUri(TestUri.INSTANCE)
        .setPosition(position)
        .setLength(length)
        .build()

    private companion object {
        const val GIB = 1024L * 1024L * 1024L
    }
}
