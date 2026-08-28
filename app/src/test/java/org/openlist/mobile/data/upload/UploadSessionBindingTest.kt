package org.openlist.mobile.data.upload

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.openlist.mobile.core.model.ServerProfile

class UploadSessionBindingTest {
    private val profile = ServerProfile("https://openlist.example/openlist", "alice")

    @Test
    fun `binding is stable but changes with token server or account`() {
        val binding = UploadSessionBinding.create(profile, "secret-token")

        assertThat(binding.matches(UploadSessionBinding.create(profile, "secret-token"))).isTrue()
        assertThat(binding.matches(UploadSessionBinding.create(profile, "other-token"))).isFalse()
        assertThat(
            binding.matches(
                UploadSessionBinding.create(profile.copy(username = "bob"), "secret-token"),
            ),
        ).isFalse()
        assertThat(
            binding.matches(
                UploadSessionBinding.create(profile.copy(baseUrl = "https://other.example"), "secret-token"),
            ),
        ).isFalse()
    }

    @Test
    fun `binding contains neither token nor raw server identity`() {
        val token = "secret-bearer-token"
        val binding = UploadSessionBinding.create(profile, token)

        assertThat(binding.value).matches("[0-9a-f]{64}")
        assertThat(binding.value).doesNotContain(token)
        assertThat(binding.value).doesNotContain(profile.baseUrl)
        assertThat(binding.value).doesNotContain(profile.username)
    }

    @Test
    fun `malformed persisted binding is rejected`() {
        assertThat(runCatching { UploadSessionBinding.parse("not-a-digest") }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
