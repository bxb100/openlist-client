package org.openlist.mobile.data.download

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.openlist.mobile.core.model.ServerProfile

class DownloadSessionBindingTest {
    private val profile = ServerProfile("https://openlist.example", "alice")

    @Test
    fun `binding is stable but changes with token server or account`() {
        val binding = DownloadSessionBinding.create(profile, "secret-token")

        assertThat(binding.matches(DownloadSessionBinding.create(profile, "secret-token"))).isTrue()
        assertThat(binding.matches(DownloadSessionBinding.create(profile, "other-token"))).isFalse()
        assertThat(
            binding.matches(
                DownloadSessionBinding.create(profile.copy(username = "bob"), "secret-token"),
            ),
        ).isFalse()
    }

    @Test
    fun `work data stores only one-way binding and never token password or raw url`() {
        val token = "secret-bearer-token"
        val input = DownloadWorkInput(
            remotePath = "/protected/movie.mp4",
            targetUri = "content://documents/movie",
            sessionBinding = DownloadSessionBinding.create(profile, token),
            expectedBytes = 123,
        )

        val data = input.toData()

        assertThat(data.getString(DownloadWorkKeys.SESSION_BINDING)).doesNotContain(token)
        assertThat(data.getString(DownloadWorkKeys.REMOTE_PATH)).isEqualTo("/protected/movie.mp4")
        assertThat(data.getString(DownloadWorkKeys.TARGET_URI)).isEqualTo("content://documents/movie")
        assertThat(DownloadWorkInput.fromData(data)).isEqualTo(input)
    }
}
