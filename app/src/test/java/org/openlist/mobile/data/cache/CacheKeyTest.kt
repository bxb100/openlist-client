package org.openlist.mobile.data.cache

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CacheKeyTest {
    @Test
    fun stableKey_normalizesPathAndRepresentation() {
        val first = CacheKey.stable(
            serverProfileId = " server-a ",
            canonicalPath = "//music///album/song.flac/",
            revision = " sha256:abc ",
            representation = "MEDIA-ORIGINAL",
        )
        val second = CacheIdentity(
            serverProfileId = "server-a",
            canonicalPath = "/music/album/song.flac",
            revision = "sha256:abc",
            representation = CacheRepresentation.MEDIA_ORIGINAL,
        ).toCacheKey()

        assertThat(first).isEqualTo(second)
        assertThat(first.diskId).matches("[0-9a-f]{64}")
    }

    @Test
    fun stableKey_changesForRevisionRepresentationPathAndServer() {
        fun key(server: String, path: String, revision: String, representation: String) =
            CacheKey.stable(server, path, revision, representation)

        val base = key("server-a", "/photo.jpg", "hash:sha256:1", "image-original")

        assertThat(key("server-b", "/photo.jpg", "hash:sha256:1", "image-original")).isNotEqualTo(base)
        assertThat(key("server-a", "/other.jpg", "hash:sha256:1", "image-original")).isNotEqualTo(base)
        assertThat(key("server-a", "/photo.jpg", "hash:sha256:2", "image-original")).isNotEqualTo(base)
        assertThat(key("server-a", "/photo.jpg", "hash:sha256:1", "image-thumbnail")).isNotEqualTo(base)
    }

    @Test
    fun revision_prefersStrongHashThenMetadataThenUnknown() {
        assertThat(
            CacheRevision.from(
                hashes = linkedMapOf("md5" to "DEAD", "SHA256" to "BEEF"),
                modifiedAtMillis = 12,
                sizeBytes = 34,
            ),
        ).isEqualTo("hash:sha256:beef")
        assertThat(CacheRevision.from(modifiedAtMillis = 12, sizeBytes = 34))
            .isEqualTo("metadata:12:34")
        assertThat(CacheRevision.from()).isEqualTo("unknown")
    }

    @Test
    fun lengthPrefixPreventsFieldBoundaryCollisions() {
        val first = CacheKey.stable("ab", "/c", "d", "e")
        val second = CacheKey.stable("a", "/bc", "d", "e")

        assertThat(first).isNotEqualTo(second)
    }
}
