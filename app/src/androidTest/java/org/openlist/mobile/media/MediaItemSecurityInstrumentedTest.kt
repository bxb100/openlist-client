@file:androidx.annotation.OptIn(
    markerClass = [androidx.media3.common.util.UnstableApi::class],
)

package org.openlist.mobile.media

import androidx.media3.common.MimeTypes
import androidx.media3.common.MediaMetadata
import androidx.media3.common.C
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.openlist.mobile.core.model.MediaKind

@RunWith(AndroidJUnit4::class)
class MediaItemSecurityInstrumentedTest {
    @Test
    fun hlsMediaItemContainsOnlyOpaqueRequestMetadata() {
        OpenListMediaRequestRegistry.clearForTest()
        val remotePath = "/private/secret album/live.m3u8"
        val item = MediaEntry(
            remotePath = remotePath,
            name = "/private/secret album/live.m3u8",
            kind = MediaKind.VIDEO,
            size = 10,
            modified = "revision",
            contentKey = ContentKey("openlist-content-v2:" + "a".repeat(64)),
        ).toMediaItem()

        assertEquals(MimeTypes.APPLICATION_M3U8, item.localConfiguration?.mimeType)
        assertNull(item.localConfiguration?.customCacheKey)
        assertFalse(item.mediaId.contains("private"))
        assertTrue(item.localConfiguration?.uri.toString().startsWith("openlist-media://resolve/"))
        assertFalse(item.localConfiguration?.uri.toString().contains("secret"))
        assertEquals("live.m3u8", item.mediaMetadata.title.toString())
        assertNull(item.mediaMetadata.extras)
        assertEquals(remotePath, OpenListMediaRequestRegistry.remotePathOrNull(item.mediaId))
    }

    @Test
    fun externalSubtitleConfigurationUsesOpaqueUriAndExpectedMimeType() {
        OpenListMediaRequestRegistry.clearForTest()
        val subtitlePath = "/private/show/episode.srt"
        val item = MediaEntry(
            remotePath = "/private/show/episode.mkv",
            name = "episode.mkv",
            kind = MediaKind.VIDEO,
            size = 10,
            modified = "revision",
            contentKey = ContentKey("openlist-content-v2:" + "b".repeat(64)),
            subtitles = listOf(
                SubtitleEntry(
                    remotePath = subtitlePath,
                    name = "episode.srt",
                    mimeType = MimeTypes.APPLICATION_SUBRIP,
                ),
            ),
        ).toMediaItem()

        val subtitle = item.localConfiguration?.subtitleConfigurations?.single()
        assertEquals(MimeTypes.APPLICATION_SUBRIP, subtitle?.mimeType)
        assertTrue(subtitle?.uri.toString().startsWith("openlist-media://resolve/"))
        assertFalse(subtitle?.uri.toString().contains("private"))
        assertFalse(subtitle?.uri.toString().contains("episode"))
        assertEquals("episode.srt", subtitle?.label)
        assertEquals(C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_AUTOSELECT, subtitle?.selectionFlags)
        assertEquals(subtitlePath, subtitle?.id?.let(OpenListMediaRequestRegistry::remotePathOrNull))
    }

    @Test
    fun multipleLanguageQualifiedSubtitlesAreNotEnabledByDirectoryOrder() {
        val item = MediaEntry(
            remotePath = "/private/show/episode.mkv",
            name = "episode.mkv",
            kind = MediaKind.VIDEO,
            size = 10,
            modified = "revision",
            contentKey = ContentKey("openlist-content-v2:" + "e".repeat(64)),
            subtitles = listOf(
                SubtitleEntry("/private/show/episode.zh-CN.ass", "episode.zh-CN.ass", MimeTypes.TEXT_SSA),
                SubtitleEntry("/private/show/episode.en.srt", "episode.en.srt", MimeTypes.APPLICATION_SUBRIP),
            ),
        ).toMediaItem()

        assertTrue(item.localConfiguration?.subtitleConfigurations.orEmpty().all { it.selectionFlags == 0 })
    }

    @Test
    fun wmaMediaItemUsesAudioMimeAndMusicMetadata() {
        val item = MediaEntry(
            remotePath = "/music/track.wma",
            name = "track.wma",
            kind = MediaKind.AUDIO,
            size = 10,
            modified = "revision",
            contentKey = ContentKey("openlist-content-v2:" + "c".repeat(64)),
        ).toMediaItem()

        assertEquals("audio/x-ms-wma", item.localConfiguration?.mimeType)
        assertEquals(MediaMetadata.MEDIA_TYPE_MUSIC, item.mediaMetadata.mediaType)
    }

    @Test
    fun wmvMediaItemUsesVideoMimeAndVideoMetadata() {
        val item = MediaEntry(
            remotePath = "/video/legacy.wmv",
            name = "legacy.wmv",
            kind = MediaKind.VIDEO,
            size = 10,
            modified = "revision",
            contentKey = ContentKey("openlist-content-v2:" + "d".repeat(64)),
        ).toMediaItem()

        assertEquals("video/x-ms-wmv", item.localConfiguration?.mimeType)
        assertEquals(MediaMetadata.MEDIA_TYPE_VIDEO, item.mediaMetadata.mediaType)
    }
}
