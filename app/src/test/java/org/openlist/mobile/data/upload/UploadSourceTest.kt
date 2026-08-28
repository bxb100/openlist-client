package org.openlist.mobile.data.upload

import com.google.common.truth.Truth.assertThat
import okio.Buffer
import org.junit.Test

class UploadSourceTest {
    @Test
    fun `request body reads exactly the requested random range`() {
        val source = ByteArrayUploadSource("0123456789".encodeToByteArray())
        val body = source.requestBody(offset = 3, byteCount = 4)
        val sink = Buffer()

        body.writeTo(sink)

        assertThat(body.contentLength()).isEqualTo(4)
        assertThat(sink.readUtf8()).isEqualTo("3456")
    }
}
