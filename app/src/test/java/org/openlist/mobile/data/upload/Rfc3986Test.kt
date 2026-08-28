package org.openlist.mobile.data.upload

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class Rfc3986Test {
    @Test
    fun `encodes file path as UTF-8 RFC3986 without form-url plus semantics`() {
        assertThat(Rfc3986.encode("/音乐/A B+~.flac"))
            .isEqualTo("%2F%E9%9F%B3%E4%B9%90%2FA%20B%2B~.flac")
    }

    @Test
    fun `encodes percent and slash while preserving only unreserved characters`() {
        assertThat(Rfc3986.encode("azAZ09-._~/%"))
            .isEqualTo("azAZ09-._~%2F%25")
    }
}
