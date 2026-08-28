package org.openlist.mobile.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AccountEditorServerProfileTest {
    @Test
    fun `new account reuses login defaults for a scheme-less address`() {
        val profile = accountEditorServerProfile(
            server = " 192.168.1.20 ",
            username = " admin ",
            creating = true,
            allowHttp = false,
        )

        assertThat(profile.baseUrl).isEqualTo("http://192.168.1.20:5244")
        assertThat(profile.username).isEqualTo("admin")
        assertThat(profile.allowInsecureHttp).isTrue()
        assertThat(profile.normalizedBaseUrl()).isEqualTo("http://192.168.1.20:5244")
    }

    @Test
    fun `new account preserves an explicit https endpoint`() {
        val profile = accountEditorServerProfile(
            server = "https://files.example.com/openlist",
            username = "alice",
            creating = true,
            allowHttp = false,
        )

        assertThat(profile.baseUrl).isEqualTo("https://files.example.com/openlist")
        assertThat(profile.allowInsecureHttp).isFalse()
    }
}
