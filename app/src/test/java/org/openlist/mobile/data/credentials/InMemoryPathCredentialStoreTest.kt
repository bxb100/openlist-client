package org.openlist.mobile.data.credentials

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.openlist.mobile.core.model.ServerProfile

class InMemoryPathCredentialStoreTest {
    private val primary = ServerProfile("https://files.example.test/openlist", "xiaobo")

    @Test
    fun `most specific ancestor wins and unrelated prefixes do not match`() {
        val store = InMemoryPathCredentialStore()
        store.remember(primary, "/media", "outer-password")
        store.remember(primary, "/media/private", "inner-password")

        assertThat(store.passwordFor(primary, "/media/song.flac")).isEqualTo("outer-password")
        assertThat(store.passwordFor(primary, "/media/private/photo.jpg")).isEqualTo("inner-password")
        assertThat(store.passwordFor(primary, "/media/private")).isEqualTo("inner-password")
        assertThat(store.passwordFor(primary, "/mediator/file.txt")).isEmpty()
    }

    @Test
    fun `credentials are isolated by server and username`() {
        val store = InMemoryPathCredentialStore()
        store.remember(primary, "/private", "secret")

        assertThat(
            store.passwordFor(ServerProfile("https://other.example.test", "xiaobo"), "/private/file"),
        ).isEmpty()
        assertThat(
            store.passwordFor(ServerProfile(primary.baseUrl, "someone-else"), "/private/file"),
        ).isEmpty()
        assertThat(store.passwordFor(primary.copy(baseUrl = "${primary.baseUrl}/"), "/private/file"))
            .isEqualTo("secret")
    }

    @Test
    fun `remembering the same scope replaces an incorrect password`() {
        val store = InMemoryPathCredentialStore()
        store.remember(primary, "/private", "wrong")
        store.remember(primary, "/private/", "correct")

        assertThat(store.passwordFor(primary, "/private/file")).isEqualTo("correct")
    }

    @Test
    fun `paths are canonicalized before ancestor matching`() {
        val store = InMemoryPathCredentialStore()
        store.remember(primary, "//media/./private/../private//", "secret")

        assertThat(store.passwordFor(primary, "/media/private/./album/../photo.jpg"))
            .isEqualTo("secret")
        assertThat(store.passwordFor(primary, "/media/other/../public/photo.jpg")).isEmpty()
    }

    @Test
    fun `forget and clear release the corresponding credentials`() {
        val store = InMemoryPathCredentialStore()
        store.remember(primary, "/one", "first")
        store.remember(primary, "/two", "second")

        store.forget(primary, "/one")
        assertThat(store.passwordFor(primary, "/one/file")).isEmpty()
        assertThat(store.passwordFor(primary, "/two/file")).isEqualTo("second")

        store.clear()
        assertThat(store.passwordFor(primary, "/two/file")).isEmpty()
    }
}
