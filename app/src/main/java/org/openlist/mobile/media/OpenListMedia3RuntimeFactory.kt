@file:androidx.annotation.OptIn(
    markerClass = [androidx.media3.common.util.UnstableApi::class],
)

package org.openlist.mobile.media

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackGroup
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.trackselection.DecodeTrackSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer

/** Media3 components shared by the playback service and device capability tests. */
internal object OpenListMedia3RuntimeFactory {
    /**
     * Retains a small compressed-sample window so the common -10s gesture does not reopen HTTP.
     * Larger backward seeks are served by the bounded disk cache instead of growing player RAM.
     */
    fun createLoadControl(): DefaultLoadControl = DefaultLoadControl.Builder()
        .setBackBuffer(PLAYBACK_BACK_BUFFER_MS, false)
        .build()

    fun createRenderersFactory(context: Context): DefaultRenderersFactory =
        DefaultRenderersFactory(context.applicationContext)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

    fun createTrackSelector(context: Context): DecodeTrackSelector =
        OpenListDecodeTrackSelector(context.applicationContext)
}

internal const val PLAYBACK_BACK_BUFFER_MS = 30_000

/**
 * Keeps normal formats hardware-first while prerouting track groups that are known to require
 * FFmpeg before codec startup. Today that covers Windows Media audio/video and a platform AAC
 * crash case that cannot be recovered in Java after MediaCodec starts.
 */
private class OpenListDecodeTrackSelector(
    context: Context,
) : DecodeTrackSelector(context) {
    override fun isRendererAllowed(
        rendererCapability: RendererCapabilities,
        group: TrackGroup,
    ): Boolean {
        if (!super.isRendererAllowed(rendererCapability, group)) return false
        return when (group.type) {
            C.TRACK_TYPE_AUDIO ->
                rendererCapability !is MediaCodecAudioRenderer ||
                    !shouldUseSoftwareAudioBeforeCodecStart(group)

            C.TRACK_TYPE_VIDEO ->
                rendererCapability !is MediaCodecVideoRenderer ||
                    !shouldUseSoftwareVideoBeforeCodecStart(group)

            else -> true
        }
    }
}

/**
 * Renderer admission is decided for a whole TrackGroup. Only move the group to FFmpeg when it
 * contains an audio format that must avoid MediaCodec before startup and every possible selection
 * in that group is clear audio that FFmpeg can decode. This avoids making mixed adaptive/DRM
 * groups unplayable.
 */
internal fun shouldUseSoftwareAudioBeforeCodecStart(
    group: TrackGroup,
    supportsSoftwareDecoder: (Format) -> Boolean = FfmpegLibrary::supportsFormat,
): Boolean {
    var requiresSoftwarePrerouting = false
    repeat(group.length) { index ->
        val format = group.getFormat(index)
        if (
            !MimeTypes.isAudio(format.sampleMimeType) ||
            format.cryptoType != C.CRYPTO_TYPE_NONE ||
            !supportsSoftwareDecoder(format)
        ) {
            return false
        }
        requiresSoftwarePrerouting = requiresSoftwarePrerouting ||
            shouldUseSoftwareAudioBeforeCodecStart(format, supportsSoftwareDecoder)
    }
    return requiresSoftwarePrerouting
}

internal fun shouldUseSoftwareAudioBeforeCodecStart(
    format: Format,
    supportsSoftwareDecoder: (Format) -> Boolean = FfmpegLibrary::supportsFormat,
): Boolean =
    format.cryptoType == C.CRYPTO_TYPE_NONE &&
        supportsSoftwareDecoder(format) &&
        (
            isWindowsMediaAudioMimeType(format.sampleMimeType) ||
                (
                    format.sampleMimeType == MimeTypes.AUDIO_AAC &&
                        format.channelCount <= 0
                    )
            )

internal fun shouldUseSoftwareVideoBeforeCodecStart(
    group: TrackGroup,
    supportsSoftwareDecoder: (Format) -> Boolean = FfmpegLibrary::supportsFormat,
): Boolean {
    var containsWindowsMediaVideo = false
    repeat(group.length) { index ->
        val format = group.getFormat(index)
        if (
            !MimeTypes.isVideo(format.sampleMimeType) ||
            format.cryptoType != C.CRYPTO_TYPE_NONE ||
            !supportsSoftwareDecoder(format)
        ) {
            return false
        }
        containsWindowsMediaVideo = containsWindowsMediaVideo ||
            shouldUseSoftwareVideoBeforeCodecStart(format, supportsSoftwareDecoder)
    }
    return containsWindowsMediaVideo
}

internal fun shouldUseSoftwareVideoBeforeCodecStart(
    format: Format,
    supportsSoftwareDecoder: (Format) -> Boolean = FfmpegLibrary::supportsFormat,
): Boolean =
    format.cryptoType == C.CRYPTO_TYPE_NONE &&
        supportsSoftwareDecoder(format) &&
        isWindowsMediaVideoMimeType(format.sampleMimeType)

/**
 * DecodeTrackSelector handles all deterministic prerouting before codec startup. This controller
 * exists only for one runtime recovery path: after a platform audio decoder failure on the current
 * item, retry once with FFmpeg audio while keeping video hardware-first.
 */
internal class MediaItemDecodePreferenceController(
    private val setRendererDecodePreferences: (audioDecode: Int, videoDecode: Int) -> Unit,
) {
    private var currentMediaId: String? = null
    private var softwareAudioFallbackEnabled = false
    private var appliedPreferences: RendererDecodePreferences? = null

    fun update(mediaId: String?) {
        if (mediaId != currentMediaId) {
            currentMediaId = mediaId
            softwareAudioFallbackEnabled = false
        }
        applyPreferences()
    }

    /** Enables FFmpeg audio for the current item at most once after MediaCodec has failed. */
    fun enableSoftwareAudioFallback(): Boolean {
        val basePreferences = rendererDecodePreferences()
        if (softwareAudioFallbackEnabled || basePreferences.audioDecode == C.DECODE_SOFTWARE) {
            return false
        }
        softwareAudioFallbackEnabled = true
        applyPreferences()
        return true
    }

    private fun applyPreferences() {
        val basePreferences = rendererDecodePreferences()
        val preferences = if (softwareAudioFallbackEnabled) {
            basePreferences.copy(audioDecode = C.DECODE_SOFTWARE)
        } else {
            basePreferences
        }
        if (preferences == appliedPreferences) return
        appliedPreferences = preferences
        setRendererDecodePreferences(preferences.audioDecode, preferences.videoDecode)
    }
}

/**
 * Media3's decoder fallback only tries another platform codec during initialization. A platform
 * codec can still crash after it starts, in which case retrying the same item with FFmpeg is the
 * only useful renderer-level recovery. Keep the test deliberately narrow so source, video, DRM,
 * AudioTrack and FFmpeg failures retain their original error instead of entering a retry loop.
 */
internal fun shouldRetryWithSoftwareAudio(
    error: PlaybackException,
    supportsSoftwareDecoder: (Format) -> Boolean = FfmpegLibrary::supportsFormat,
): Boolean {
    val exoError = error as? ExoPlaybackException ?: return false
    if (exoError.type != ExoPlaybackException.TYPE_RENDERER) return false
    val format = exoError.rendererFormat ?: return false
    return shouldRetryWithSoftwareAudio(
        rendererName = exoError.rendererName,
        errorCode = exoError.errorCode,
        format = format,
        supportsSoftwareDecoder = supportsSoftwareDecoder,
    )
}

internal fun shouldRetryWithSoftwareAudio(
    rendererName: String?,
    errorCode: Int,
    format: Format,
    supportsSoftwareDecoder: (Format) -> Boolean,
): Boolean =
    rendererName == MEDIA_CODEC_AUDIO_RENDERER_NAME &&
        errorCode in SOFTWARE_AUDIO_FALLBACK_ERROR_CODES &&
        MimeTypes.isAudio(format.sampleMimeType) &&
        format.cryptoType == C.CRYPTO_TYPE_NONE &&
        supportsSoftwareDecoder(format)

internal data class RendererDecodePreferences(
    val audioDecode: Int,
    val videoDecode: Int,
)

internal fun rendererDecodePreferences(): RendererDecodePreferences = RendererDecodePreferences(
    audioDecode = C.DECODE_HARDWARE,
    videoDecode = C.DECODE_HARDWARE,
)

internal fun isWindowsMediaAudioMimeType(mimeType: String?): Boolean =
    mimeType != null && mimeType in WINDOWS_MEDIA_AUDIO_MIME_TYPES
internal fun isWindowsMediaVideoMimeType(mimeType: String?): Boolean =
    mimeType != null && mimeType in WINDOWS_MEDIA_VIDEO_MIME_TYPES

private val WINDOWS_MEDIA_VIDEO_MIME_TYPES = setOf(
    MimeTypes.VIDEO_VC1,
    MimeTypes.VIDEO_WMV,
    MimeTypes.VIDEO_WMV1,
    MimeTypes.VIDEO_WMV2,
)

private val WINDOWS_MEDIA_AUDIO_MIME_TYPES = setOf(
    MimeTypes.AUDIO_WMA,
    MimeTypes.AUDIO_WMA1,
    MimeTypes.AUDIO_WMA2,
    MimeTypes.AUDIO_WMA_PRO,
    MimeTypes.AUDIO_WMA_LOSSLESS,
    MimeTypes.AUDIO_WMA_VOICE,
)

private const val MEDIA_CODEC_AUDIO_RENDERER_NAME = "MediaCodecAudioRenderer"

private val SOFTWARE_AUDIO_FALLBACK_ERROR_CODES = setOf(
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
)
