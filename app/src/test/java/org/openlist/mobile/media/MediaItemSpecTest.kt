package org.openlist.mobile.media

import androidx.media3.common.MimeTypes
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.openlist.mobile.core.model.MediaKind

class MediaItemSpecTest {
    @Test
    fun `hls spec uses explicit HLS mime and contains no remote path or cache key`() {
        OpenListMediaRequestRegistry.clearForTest()
        val remotePath = "/private/secret album/live.m3u8"
        val spec = entry(
            remotePath = remotePath,
            name = "/private/secret album/live.m3u8",
            mimeType = MimeTypes.APPLICATION_M3U8,
        ).toSecureMediaItemSpec()

        assertThat(spec.mimeType).isEqualTo(MimeTypes.APPLICATION_M3U8)
        assertThat(spec.customCacheKey).isNull()
        assertThat(spec.mediaId).doesNotContain("private")
        assertThat(spec.mediaId).doesNotContain("live")
        assertThat(spec.uri).startsWith("openlist-media://resolve/")
        assertThat(spec.uri).doesNotContain("secret")
        assertThat(spec.displayName).isEqualTo("live.m3u8")
        assertThat(OpenListMediaRequestRegistry.remotePathOrNull(spec.mediaId)).isEqualTo(remotePath)
    }

    @Test
    fun `progressive spec keeps its stable non URL cache key`() {
        val spec = entry(
            remotePath = "/private/movie.mp4",
            name = "movie.mp4",
            mimeType = "video/mp4",
        ).toSecureMediaItemSpec()

        assertThat(spec.customCacheKey).isEqualTo(STABLE_KEY)
        assertThat(spec.customCacheKey).doesNotContain("https://")
        assertThat(OpenListMediaRequestRegistry.detailsOrNull(spec.mediaId)?.knownSize)
            .isEqualTo(10L)
    }

    @Test
    fun `subtitle specs expose opaque uris while registry retains their paths`() {
        OpenListMediaRequestRegistry.clearForTest()
        val spec = entry(
            remotePath = "/private/show/episode.mkv",
            name = "episode.mkv",
            mimeType = "video/x-matroska",
            subtitles = listOf(
                SubtitleEntry(
                    remotePath = "/private/show/episode.zh-CN.ass",
                    name = "episode.zh-CN.ass",
                    mimeType = MimeTypes.TEXT_SSA,
                ),
            ),
        ).toSecureMediaItemSpec()

        val subtitle = spec.subtitles.single()
        assertThat(subtitle.uri).startsWith("openlist-media://resolve/")
        assertThat(subtitle.uri).doesNotContain("private")
        assertThat(subtitle.uri).doesNotContain("episode")
        assertThat(subtitle.displayName).isEqualTo("episode.zh-CN.ass")
        assertThat(subtitle.mimeType).isEqualTo(MimeTypes.TEXT_SSA)
        assertThat(subtitle.isDefault).isTrue()
        assertThat(OpenListMediaRequestRegistry.remotePathOrNull(subtitle.mediaId))
            .isEqualTo("/private/show/episode.zh-CN.ass")
        assertThat(OpenListMediaRequestRegistry.detailsOrNull(subtitle.mediaId)?.knownSize).isNull()
    }

    private fun entry(
        remotePath: String,
        name: String,
        mimeType: String,
        subtitles: List<SubtitleEntry> = emptyList(),
    ) = MediaEntry(
        remotePath = remotePath,
        name = name,
        kind = MediaKind.VIDEO,
        size = 10,
        modified = "revision",
        contentKey = ContentKey(STABLE_KEY),
        mimeType = mimeType,
        subtitles = subtitles,
    )

    private companion object {
        val STABLE_KEY = "openlist-content-v2:" + "a".repeat(64)
    }
}
