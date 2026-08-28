package org.openlist.mobile.media

import com.google.common.truth.Truth.assertThat
import androidx.media3.common.C
import androidx.media3.common.FileTypes
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import org.junit.Test
import org.openlist.mobile.core.model.MediaKind
import org.openlist.mobile.core.model.OpenListObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class MediaSecurityTest {
    @Test
    fun `sensitive authentication headers are stripped case insensitively`() {
        val cleaned = SensitiveMediaHeaders.removeFrom(
            linkedMapOf(
                "Authorization" to "Bearer secret",
                "COOKIE" to "session=secret",
                "X-OpenList-Token" to "secret",
                "Range" to "bytes=10-",
                "Accept" to "video/*",
            ),
        )

        assertThat(cleaned).containsExactly(
            "Range", "bytes=10-",
            "Accept", "video/*",
        )
    }

    @Test
    fun `known server type wins and unknown type uses extension`() {
        assertThat(MediaTypeDetector.kindFromName("TRACK.OpUs")).isEqualTo(MediaKind.AUDIO)
        assertThat(MediaTypeDetector.kindFromName("movie.MKV")).isEqualTo(MediaKind.VIDEO)
        assertThat(MediaTypeDetector.kindFromName("photo.AvIf")).isEqualTo(MediaKind.IMAGE)
        assertThat(MediaTypeDetector.kindFromName("README")).isEqualTo(MediaKind.OTHER)
    }

    @Test
    fun `mkv wmv and wma extensions override incorrect server classifications`() {
        listOf(3, 4, 5).forEach { incorrectType ->
            assertThat(MediaTypeDetector.kind(OpenListObject(name = "movie.MKV", type = incorrectType)))
                .isEqualTo(MediaKind.VIDEO)
            assertThat(MediaTypeDetector.kind(OpenListObject(name = "legacy.WMV", type = incorrectType)))
                .isEqualTo(MediaKind.VIDEO)
        }
        listOf(2, 4, 5).forEach { incorrectType ->
            assertThat(MediaTypeDetector.kind(OpenListObject(name = "track.WMA", type = incorrectType)))
                .isEqualTo(MediaKind.AUDIO)
        }
        assertThat(MediaTypeDetector.mimeType("movie.mkv")).isEqualTo("video/x-matroska")
        assertThat(MediaTypeDetector.mimeType("legacy.wmv")).isEqualTo("video/x-ms-wmv")
        assertThat(MediaTypeDetector.mimeType("track.wma")).isEqualTo("audio/x-ms-wma")
    }

    @Test
    fun `windows media decoder selection is scoped away from common hardware formats`() {
        listOf(
            MimeTypes.VIDEO_VC1,
            MimeTypes.VIDEO_WMV,
            MimeTypes.VIDEO_WMV1,
            MimeTypes.VIDEO_WMV2,
        ).forEach { mimeType ->
            assertThat(isWindowsMediaVideoMimeType(mimeType)).isTrue()
        }

        listOf(
            MimeTypes.AUDIO_WMA,
            MimeTypes.AUDIO_WMA1,
            MimeTypes.AUDIO_WMA2,
            MimeTypes.AUDIO_WMA_PRO,
            MimeTypes.AUDIO_WMA_LOSSLESS,
            MimeTypes.AUDIO_WMA_VOICE,
        ).forEach { mimeType ->
            assertThat(isWindowsMediaAudioMimeType(mimeType)).isTrue()
        }

        assertThat(isWindowsMediaVideoMimeType(MimeTypes.VIDEO_H264)).isFalse()
        assertThat(isWindowsMediaAudioMimeType(MimeTypes.AUDIO_AAC)).isFalse()
    }

    @Test
    fun `windows media container mime types route to the asf extractor family`() {
        assertThat(FileTypes.inferFileTypeFromMimeType(MimeTypes.VIDEO_WMV))
            .isEqualTo(FileTypes.ASF)
        assertThat(FileTypes.inferFileTypeFromMimeType(MimeTypes.AUDIO_WMA))
            .isEqualTo(FileTypes.ASF)
    }

    @Test
    fun `ordinary codecs retain hardware decode preferences`() {
        assertThat(rendererDecodePreferences()).isEqualTo(
            RendererDecodePreferences(
                audioDecode = C.DECODE_HARDWARE,
                videoDecode = C.DECODE_HARDWARE,
            ),
        )
    }

    @Test
    fun `windows media prerouting keys off actual sample mime rather than container mime`() {
        val wma = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_WMA).build()
        val vc1 = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_VC1).build()
        val aacInWmvContainer = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setContainerMimeType(MimeTypes.VIDEO_WMV)
            .setChannelCount(2)
            .setSampleRate(48_000)
            .build()
        val h264InWmvContainer = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setContainerMimeType(MimeTypes.VIDEO_WMV)
            .build()

        assertThat(
            shouldUseSoftwareAudioBeforeCodecStart(
                TrackGroup(wma),
                supportsSoftwareDecoder = { true },
            ),
        ).isTrue()
        assertThat(
            shouldUseSoftwareVideoBeforeCodecStart(
                TrackGroup(vc1),
                supportsSoftwareDecoder = { true },
            ),
        ).isTrue()
        assertThat(
            shouldUseSoftwareAudioBeforeCodecStart(
                TrackGroup(aacInWmvContainer),
                supportsSoftwareDecoder = { true },
            ),
        ).isFalse()
        assertThat(
            shouldUseSoftwareVideoBeforeCodecStart(
                TrackGroup(h264InWmvContainer),
                supportsSoftwareDecoder = { true },
            ),
        ).isFalse()
    }

    @Test
    fun `software audio fallback controller resets when the item changes`() {
        val applied = mutableListOf<RendererDecodePreferences>()
        val controller = MediaItemDecodePreferenceController { audioDecode, videoDecode ->
            applied += RendererDecodePreferences(audioDecode, videoDecode)
        }

        controller.update("first")
        assertThat(controller.enableSoftwareAudioFallback()).isTrue()
        assertThat(controller.enableSoftwareAudioFallback()).isFalse()
        controller.update("second")

        assertThat(applied).containsExactly(
            RendererDecodePreferences(C.DECODE_HARDWARE, C.DECODE_HARDWARE),
            RendererDecodePreferences(C.DECODE_SOFTWARE, C.DECODE_HARDWARE),
            RendererDecodePreferences(C.DECODE_HARDWARE, C.DECODE_HARDWARE),
        ).inOrder()
    }

    @Test
    fun `software audio fallback only admits platform audio decoder failures`() {
        val aac = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setChannelCount(0)
            .setSampleRate(48_000)
            .build()
        val supported: (Format) -> Boolean = { true }

        assertThat(
            shouldRetryWithSoftwareAudio(
                rendererName = "MediaCodecAudioRenderer",
                errorCode = PlaybackException.ERROR_CODE_DECODING_FAILED,
                format = aac,
                supportsSoftwareDecoder = supported,
            ),
        ).isTrue()
        assertThat(
            shouldRetryWithSoftwareAudio(
                rendererName = "FfmpegAudioRenderer",
                errorCode = PlaybackException.ERROR_CODE_DECODING_FAILED,
                format = aac,
                supportsSoftwareDecoder = supported,
            ),
        ).isFalse()
        assertThat(
            shouldRetryWithSoftwareAudio(
                rendererName = "MediaCodecAudioRenderer",
                errorCode = PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
                format = aac,
                supportsSoftwareDecoder = supported,
            ),
        ).isFalse()
        assertThat(
            shouldRetryWithSoftwareAudio(
                rendererName = "MediaCodecAudioRenderer",
                errorCode = PlaybackException.ERROR_CODE_DECODING_FAILED,
                format = aac,
                supportsSoftwareDecoder = { false },
            ),
        ).isFalse()
    }

    @Test
    fun `aac with an implicit channel layout bypasses the crashing platform decoder`() {
        val implicitChannelLayoutAac = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setChannelCount(0)
            .setSampleRate(48_000)
            .build()
        val explicitMonoAac = implicitChannelLayoutAac.buildUpon().setChannelCount(1).build()

        assertThat(
            shouldUseSoftwareAudioBeforeCodecStart(
                implicitChannelLayoutAac,
                supportsSoftwareDecoder = { true },
            ),
        ).isTrue()
        assertThat(
            shouldUseSoftwareAudioBeforeCodecStart(
                explicitMonoAac,
                supportsSoftwareDecoder = { true },
            ),
        ).isFalse()
        assertThat(
            shouldUseSoftwareAudioBeforeCodecStart(
                implicitChannelLayoutAac,
                supportsSoftwareDecoder = { false },
            ),
        ).isFalse()
    }

    @Test
    fun `software prerouting requires every format in an adaptive group to be safe`() {
        val implicitChannelLayoutAac = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setChannelCount(0)
            .setSampleRate(48_000)
            .build()
        val explicitStereoAac = implicitChannelLayoutAac.buildUpon().setChannelCount(2).build()

        assertThat(
            shouldUseSoftwareAudioBeforeCodecStart(
                TrackGroup(implicitChannelLayoutAac, explicitStereoAac),
                supportsSoftwareDecoder = { true },
            ),
        ).isTrue()
        assertThat(
            shouldUseSoftwareAudioBeforeCodecStart(
                TrackGroup(implicitChannelLayoutAac, explicitStereoAac),
                supportsSoftwareDecoder = { format -> format.channelCount <= 0 },
            ),
        ).isFalse()
        assertThat(
            shouldUseSoftwareAudioBeforeCodecStart(
                TrackGroup(explicitStereoAac),
                supportsSoftwareDecoder = { true },
            ),
        ).isFalse()
    }

    @Test
    fun `windows media video prerouting requires a clear software decodable group`() {
        val vc1 = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_VC1).build()
        val h264 = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build()

        assertThat(
            shouldUseSoftwareVideoBeforeCodecStart(
                TrackGroup(vc1),
                supportsSoftwareDecoder = { true },
            ),
        ).isTrue()
        assertThat(
            shouldUseSoftwareVideoBeforeCodecStart(
                TrackGroup(vc1, h264),
                supportsSoftwareDecoder = { format -> format.sampleMimeType == MimeTypes.VIDEO_VC1 },
            ),
        ).isFalse()
        assertThat(
            shouldUseSoftwareVideoBeforeCodecStart(
                TrackGroup(h264),
                supportsSoftwareDecoder = { true },
            ),
        ).isFalse()
    }

    @Test
    fun `hls manifest is playable even when server classifies its text payload as text`() {
        val manifest = OpenListObject(name = "Live.M3U8", type = 4)

        assertThat(MediaTypeDetector.kind(manifest)).isEqualTo(MediaKind.VIDEO)
        assertThat(MediaTypeDetector.mimeType(manifest.name)).isEqualTo(MimeTypes.APPLICATION_M3U8)
        assertThat(MediaTypeDetector.kindFromName("movie.rmvb")).isEqualTo(MediaKind.VIDEO)
        assertThat(MediaTypeDetector.mimeType("movie.rmvb"))
            .isEqualTo("application/vnd.rn-realmedia-vbr")
    }

    @Test
    fun `controller trust admits this app and trusted system controllers only`() {
        assertThat(PlaybackControllerTrust.isAllowed(appUid = 100, controllerUid = 100, isTrusted = false))
            .isTrue()
        assertThat(PlaybackControllerTrust.isAllowed(appUid = 100, controllerUid = 200, isTrusted = true))
            .isTrue()
        assertThat(PlaybackControllerTrust.isAllowed(appUid = 100, controllerUid = 200, isTrusted = false))
            .isFalse()
    }

    @Test
    @Suppress("DEPRECATION")
    fun `trusted external controllers keep transport controls but cannot replace queue`() {
        assertThat(PlaybackControllerTrust.mayTrustedExternalControllerIssue(Player.COMMAND_PLAY_PAUSE))
            .isTrue()
        assertThat(PlaybackControllerTrust.mayTrustedExternalControllerIssue(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
            .isTrue()
        assertThat(PlaybackControllerTrust.mayTrustedExternalControllerIssue(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
            .isTrue()
        assertThat(PlaybackControllerTrust.mayTrustedExternalControllerIssue(Player.COMMAND_GET_METADATA))
            .isTrue()
        assertThat(PlaybackControllerTrust.mayTrustedExternalControllerIssue(Player.COMMAND_SET_MEDIA_ITEM))
            .isFalse()
        assertThat(PlaybackControllerTrust.mayTrustedExternalControllerIssue(Player.COMMAND_CHANGE_MEDIA_ITEMS))
            .isFalse()
        assertThat(PlaybackControllerTrust.mayTrustedExternalControllerIssue(Player.COMMAND_SET_MEDIA_ITEMS_METADATA))
            .isFalse()
        assertThat(PlaybackControllerTrust.mayTrustedExternalControllerIssue(Player.COMMAND_SET_PLAYLIST_METADATA))
            .isFalse()
    }

    @Test
    fun `media request registry exposes only an opaque process local reference`() {
        OpenListMediaRequestRegistry.clearForTest()
        val remotePath = "/private/secret album/live.m3u8"

        val request = OpenListMediaRequestRegistry.register(remotePath)

        assertThat(request.mediaId).doesNotContain("private")
        assertThat(request.mediaId).doesNotContain("live")
        assertThat(request.uri).startsWith("openlist-media://resolve/")
        assertThat(request.uri).doesNotContain("private")
        assertThat(request.uri).doesNotContain("secret")
        assertThat(request.uri).doesNotContain("%2F")
        assertThat(OpenListMediaRequestRegistry.remotePathOrNull(request.mediaId)).isEqualTo(remotePath)
        assertThat(OpenListMediaRequestRegistry.remotePathOrNull("unknown")).isNull()

        val replacement = OpenListMediaRequestRegistry.register("/replacement.mp4")
        OpenListMediaRequestRegistry.retainOnly(setOf(replacement.mediaId))
        assertThat(OpenListMediaRequestRegistry.remotePathOrNull(request.mediaId)).isNull()
        assertThat(OpenListMediaRequestRegistry.remotePathOrNull(replacement.mediaId))
            .isEqualTo("/replacement.mp4")
    }

    @Test(expected = MediaUrlResolutionException::class)
    fun `resolved media rejects non-http schemes`() {
        validateHttpUrl("file:///private/token")
    }

    @Test
    fun `network boundary strips credentials added by application interceptors`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
            val baseClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    chain.proceed(
                        chain.request().newBuilder()
                            .header("Authorization", "Bearer openlist-secret")
                            .header("Cookie", "openlist=secret")
                            .header("Range", "bytes=10-")
                            .build(),
                    )
                }
                .build()

            tokenSafeMediaClient(baseClient)
                .newCall(Request.Builder().url(server.url("/media")).build())
                .execute()
                .use { response -> assertThat(response.isSuccessful).isTrue() }

            val request = server.takeRequest()
            assertThat(request.headers["Authorization"]).isNull()
            assertThat(request.headers["Cookie"]).isNull()
            assertThat(request.headers["Range"]).isEqualTo("bytes=10-")
        }
    }
}
