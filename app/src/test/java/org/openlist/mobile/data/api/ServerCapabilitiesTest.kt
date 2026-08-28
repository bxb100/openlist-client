package org.openlist.mobile.data.api

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ServerCapabilitiesTest {
    @Test
    fun `parses string settings and clamps chunk size`() {
        val capabilities = ServerCapabilities.from(
            mapOf(
                "version" to "v4.2.5",
                "multipart_enabled" to "true",
                "multipart_chunk_size" to "0",
                "ldap_login_enabled" to "1",
                "webauthn_login_enabled" to "true",
            ),
        )

        assertThat(capabilities.version).isEqualTo("v4.2.5")
        assertThat(capabilities.multipartEnabled).isTrue()
        assertThat(capabilities.multipartChunkSizeMiB).isEqualTo(1)
        assertThat(capabilities.ldapEnabled).isTrue()
        assertThat(capabilities.webAuthnEnabled).isTrue()
    }
}
