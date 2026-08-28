@file:androidx.annotation.OptIn(
    markerClass = [androidx.media3.common.util.UnstableApi::class],
)

package org.openlist.mobile.media

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackGroup
import androidx.media3.common.text.CueGroup
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.decoder.ffmpeg.FfmpegVideoRenderer
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.trackselection.DecodeTrackSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.extractor.asf.AsfExtractor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenListMedia3RuntimeInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun defaultExtractorsIncludeAsfForWmv() {
        val extractors = OpenListExtractorsFactory().createExtractors(
            Uri.parse("https://example.invalid/media.wmv"),
            emptyMap(),
        )

        assertTrue(extractors.any { it.underlyingImplementation is AsfExtractor })
    }

    @Test
    fun runtimeFactoryCreatesFfmpegAudioAndVideoRenderers() {
        val renderers = createRenderers()

        assertTrue(renderers.any { it is FfmpegAudioRenderer })
        assertTrue(renderers.any { it is FfmpegVideoRenderer })
    }

    @Test
    fun runtimeFactoryExtendsForkDecodeTrackSelector() {
        val trackSelector = OpenListMedia3RuntimeFactory.createTrackSelector(context)

        assertEquals(DecodeTrackSelector::class.java, trackSelector.javaClass.superclass)
    }

    @Test
    fun bundledFfmpegSupportsWmaAndVc1OnArm() {
        assumeTrue(
            "The bundled FongMi FFmpeg native libraries target ARM only",
            Build.SUPPORTED_ABIS.firstOrNull()?.startsWith("arm") == true,
        )

        assertTrue("FFmpeg JNI library is unavailable", FfmpegLibrary.isAvailable())
        listOf(
            MimeTypes.AUDIO_WMA,
            MimeTypes.AUDIO_WMA1,
            MimeTypes.AUDIO_WMA2,
            MimeTypes.AUDIO_WMA_PRO,
            MimeTypes.AUDIO_WMA_LOSSLESS,
            MimeTypes.AUDIO_WMA_VOICE,
            MimeTypes.VIDEO_VC1,
            MimeTypes.VIDEO_WMV,
            MimeTypes.VIDEO_WMV1,
            MimeTypes.VIDEO_WMV2,
        ).forEach { mimeType ->
            assertTrue(
                "Bundled FFmpeg does not support $mimeType",
                FfmpegLibrary.supportsFormat(
                    Format.Builder().setSampleMimeType(mimeType).build(),
                ),
            )
        }

        val vc1 = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_VC1).build()
        val renderers = createRenderers()
        val videoRenderer = renderers.filterIsInstance<FfmpegVideoRenderer>().single()
        assertEquals(
            "FFmpeg video renderer cannot produce a Surface output on this device",
            C.FORMAT_HANDLED,
            RendererCapabilities.getFormatSupport(videoRenderer.supportsFormat(vc1)),
        )

        // AAC channel_configuration=0 stores its layout in the PCE/bitstream. The failing platform
        // codec cannot handle it, while FFmpeg resolves the real channel count while decoding.
        val implicitChannelLayoutAac = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setChannelCount(0)
            .setSampleRate(48_000)
            .build()
        assertTrue(
            "Bundled FFmpeg does not contain the AAC decoder",
            FfmpegLibrary.supportsFormat(implicitChannelLayoutAac),
        )
        val audioFormatSupport = AtomicInteger(C.FORMAT_UNSUPPORTED_TYPE)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val audioRenderer = createRenderers().filterIsInstance<FfmpegAudioRenderer>().single()
            audioFormatSupport.set(
                RendererCapabilities.getFormatSupport(
                    audioRenderer.supportsFormat(implicitChannelLayoutAac),
                ),
            )
        }
        assertEquals(
            "FFmpeg audio renderer rejects AAC with an implicit channel layout",
            C.FORMAT_HANDLED,
            audioFormatSupport.get(),
        )
    }

    @Test
    fun platformAacDecoderFailureIsEligibleForFfmpegRecovery() {
        assumeTrue(
            "The bundled FongMi FFmpeg native libraries target ARM only",
            Build.SUPPORTED_ABIS.firstOrNull()?.startsWith("arm") == true,
        )
        val implicitChannelLayoutAac = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setChannelCount(0)
            .setSampleRate(48_000)
            .build()
        val error = ExoPlaybackException.createForRenderer(
            IllegalStateException("synthetic platform codec failure"),
            "MediaCodecAudioRenderer",
            /* rendererIndex = */ 1,
            implicitChannelLayoutAac,
            C.FORMAT_HANDLED,
            /* mediaPeriodId = */ null,
            /* isRecoverable = */ false,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
        )

        assertTrue(shouldRetryWithSoftwareAudio(error))
    }

    @Test
    fun implicitChannelLayoutAacSkipsMediaCodecBeforeItCanReleaseItsInputBuffer() {
        assumeTrue(
            "The bundled FongMi FFmpeg native libraries target ARM only",
            Build.SUPPORTED_ABIS.firstOrNull()?.startsWith("arm") == true,
        )
        val implicitChannelLayoutAac = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setChannelCount(0)
            .setSampleRate(48_000)
            .build()
        val explicitMonoAac = implicitChannelLayoutAac.buildUpon().setChannelCount(1).build()
        val selector = OpenListMedia3RuntimeFactory.createTrackSelector(context)
        val isRendererAllowed = selector.javaClass.getDeclaredMethod(
            "isRendererAllowed",
            RendererCapabilities::class.java,
            TrackGroup::class.java,
        ).apply { isAccessible = true }
        val renderers = createRenderers()
        val mediaCodecAudio = renderers.single { it.name == "MediaCodecAudioRenderer" }
        val ffmpegAudio = renderers.single { it.name == "FfmpegAudioRenderer" }

        assertFalse(
            isRendererAllowed.invoke(
                selector,
                mediaCodecAudio,
                TrackGroup(implicitChannelLayoutAac),
            ) as Boolean,
        )
        assertTrue(
            isRendererAllowed.invoke(
                selector,
                ffmpegAudio,
                TrackGroup(implicitChannelLayoutAac),
            ) as Boolean,
        )
        assertTrue(
            isRendererAllowed.invoke(
                selector,
                mediaCodecAudio,
                TrackGroup(explicitMonoAac),
            ) as Boolean,
        )
    }

    @Test
    fun windowsMediaTrackGroupsSkipMediaCodecBeforeDecoderStart() {
        assumeTrue(
            "The bundled FongMi FFmpeg native libraries target ARM only",
            Build.SUPPORTED_ABIS.firstOrNull()?.startsWith("arm") == true,
        )
        val wma = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_WMA).build()
        val vc1 = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_VC1).build()
        val selector = OpenListMedia3RuntimeFactory.createTrackSelector(context)
        val isRendererAllowed = selector.javaClass.getDeclaredMethod(
            "isRendererAllowed",
            RendererCapabilities::class.java,
            TrackGroup::class.java,
        ).apply { isAccessible = true }
        val renderers = createRenderers()
        val mediaCodecAudio = renderers.single { it.name == "MediaCodecAudioRenderer" }
        val ffmpegAudio = renderers.single { it.name == "FfmpegAudioRenderer" }
        val mediaCodecVideo = renderers.single { it.name == "MediaCodecVideoRenderer" }
        val ffmpegVideo = renderers.single { it.name == "FfmpegVideoRenderer" }

        assertFalse(
            isRendererAllowed.invoke(
                selector,
                mediaCodecAudio,
                TrackGroup(wma),
            ) as Boolean,
        )
        assertTrue(
            isRendererAllowed.invoke(
                selector,
                ffmpegAudio,
                TrackGroup(wma),
            ) as Boolean,
        )
        assertFalse(
            isRendererAllowed.invoke(
                selector,
                mediaCodecVideo,
                TrackGroup(vc1),
            ) as Boolean,
        )
        assertTrue(
            isRendererAllowed.invoke(
                selector,
                ffmpegVideo,
                TrackGroup(vc1),
            ) as Boolean,
        )
    }

    private fun createRenderers(): Array<Renderer> =
        OpenListMedia3RuntimeFactory.createRenderersFactory(context).createRenderers(
            Handler(Looper.getMainLooper()),
            object : VideoRendererEventListener {},
            object : AudioRendererEventListener {},
            TextOutput { _: CueGroup -> },
            MetadataOutput { _: Metadata -> },
        )
}
