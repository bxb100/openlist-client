package org.openlist.mobile.media

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.openlist.mobile.core.model.FileDetails
import org.openlist.mobile.core.model.OpenListObject

class ContentKeyFactoryTest {
    @Test
    fun `raw url and sign are not part of file details key`() {
        val first = details(rawUrl = "https://storage.test/file?token=first", sign = "one")
        val second = details(rawUrl = "https://other.test/file?token=second", sign = "two")

        assertThat(ContentKeyFactory.forDetails("https://server.test", "/a/file.mp3", first))
            .isEqualTo(ContentKeyFactory.forDetails("https://server.test", "/a/file.mp3", second))
    }

    @Test
    fun `hash revision wins over mutable metadata`() {
        val first = OpenListObject(
            name = "file.mp3",
            size = 10,
            modified = "yesterday",
            hashes = mapOf("SHA-256" to "ABC123"),
        )
        val second = first.copy(size = 999, modified = "today", hashes = mapOf("sha256" to "abc123"))

        assertThat(ContentKeyFactory.forObject("https://server.test", "/file.mp3", first))
            .isEqualTo(ContentKeyFactory.forObject("https://server.test", "/file.mp3", second))
    }

    @Test
    fun `strongest available hash is deterministic across map order`() {
        val first = details(hashes = linkedMapOf("md5" to "weak", "sha256" to "strong"))
        val second = details(hashes = linkedMapOf("sha256" to "strong", "md5" to "changed"))

        assertThat(ContentKeyFactory.forDetails("server", "/file.mp3", first))
            .isEqualTo(ContentKeyFactory.forDetails("server", "/file.mp3", second))
    }

    @Test
    fun `changed preferred hash changes key`() {
        val first = details(hashes = mapOf("sha256" to "one"))
        val second = details(hashes = mapOf("sha256" to "two"))

        assertThat(ContentKeyFactory.forDetails("server", "/file.mp3", first))
            .isNotEqualTo(ContentKeyFactory.forDetails("server", "/file.mp3", second))
    }

    @Test
    fun `metadata is revision fallback when hash is unavailable`() {
        val first = details(size = 10, modified = "one", hashes = null, hashInfo = "")
        val second = first.copy(modified = "two")

        assertThat(ContentKeyFactory.forDetails("server", "/file.mp3", first))
            .isNotEqualTo(ContentKeyFactory.forDetails("server", "/file.mp3", second))
    }

    @Test
    fun `server credentials query and default port do not affect key`() {
        val item = details()
        val withCredentials = ContentKeyFactory.forDetails(
            "https://user:password@SERVER.test:443/root/?token=secret",
            "/file.mp3",
            item,
        )
        val clean = ContentKeyFactory.forDetails("https://server.test/root", "/file.mp3", item)

        assertThat(withCredentials).isEqualTo(clean)
        assertThat(withCredentials.value).doesNotContain("secret")
        assertThat(withCredentials.value).doesNotContain("password")
    }

    @Test
    fun `same server path and revision are isolated by account identity`() {
        val item = details()
        val alice = ContentKeyFactory.forDetails(
            serverIdentity = "https://server.test",
            accountIdentity = "alice",
            remotePath = "/private/file.mp3",
            details = item,
        )
        val bob = ContentKeyFactory.forDetails(
            serverIdentity = "https://server.test",
            accountIdentity = "bob",
            remotePath = "/private/file.mp3",
            details = item,
        )

        assertThat(alice).isNotEqualTo(bob)
        assertThat(alice.value).doesNotContain("alice")
        assertThat(bob.value).doesNotContain("bob")
    }

    private fun details(
        rawUrl: String = "https://storage.test/file",
        sign: String = "sign",
        size: Long = 1,
        modified: String = "revision",
        hashes: Map<String, String>? = mapOf("sha1" to "content"),
        hashInfo: String = "",
    ) = FileDetails(
        name = "file.mp3",
        rawUrl = rawUrl,
        sign = sign,
        size = size,
        modified = modified,
        hashes = hashes,
        hashinfo = hashInfo,
    )
}
