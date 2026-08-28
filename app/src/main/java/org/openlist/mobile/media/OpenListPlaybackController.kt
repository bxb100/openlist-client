@file:androidx.annotation.OptIn(
    markerClass = [androidx.media3.common.util.UnstableApi::class],
)

package org.openlist.mobile.media

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.openlist.mobile.core.model.MediaKind

data class PlaybackControllerState(
    val queue: List<MediaItem>,
    val currentIndex: Int,
    val isPlaying: Boolean,
    val playWhenReady: Boolean,
    @Player.State val playbackState: Int,
    @Player.RepeatMode val repeatMode: Int,
    val shuffleEnabled: Boolean,
    val playbackSpeed: Float,
    val canSeek: Boolean,
    val canChangeSpeed: Boolean,
) {
    val currentItem: MediaItem?
        get() = queue.getOrNull(currentIndex)
}

/** Typed queue operations on top of a Media3 controller. */
class OpenListPlaybackController(
    val mediaController: MediaController,
) : AutoCloseable {
    private val mutableState = MutableStateFlow(mediaController.snapshot())
    val state: StateFlow<PlaybackControllerState> = mutableState.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            mutableState.value = mediaController.snapshot()
        }
    }

    init {
        mediaController.addListener(listener)
    }

    fun setQueue(
        sequence: MediaSequence,
        playWhenReady: Boolean = true,
        startPositionMs: Long = 0L,
    ) {
        require(sequence.kind == MediaKind.AUDIO || sequence.kind == MediaKind.VIDEO) {
            "Only audio and video sequences can be sent to ExoPlayer"
        }
        mediaController.setMediaItems(
            sequence.items.map(MediaEntry::toMediaItem),
            sequence.currentIndex,
            startPositionMs.coerceAtLeast(0L),
        )
        mediaController.prepare()
        if (playWhenReady) mediaController.play() else mediaController.pause()
    }

    fun play(index: Int? = null, positionMs: Long = C.TIME_UNSET) {
        if (index != null) {
            require(index in 0 until mediaController.mediaItemCount) { "Queue index is out of bounds" }
            if (positionMs == C.TIME_UNSET) mediaController.seekToDefaultPosition(index)
            else mediaController.seekTo(index, positionMs.coerceAtLeast(0L))
        } else if (positionMs != C.TIME_UNSET) {
            mediaController.seekTo(positionMs.coerceAtLeast(0L))
        }
        mediaController.prepare()
        mediaController.play()
    }

    fun pause() = mediaController.pause()

    fun skipToNext() = mediaController.seekToNextMediaItem()

    fun skipToPrevious() = mediaController.seekToPreviousMediaItem()

    fun seekTo(positionMs: Long) {
        mediaController.dispatchSeek(positionMs.coerceAtLeast(0L))
    }

    fun seekBack() = seekBy(-SEEK_INTERVAL_MS)

    fun seekForward() = seekBy(SEEK_INTERVAL_MS)

    /**
     * Uses an absolute seek instead of Player.seekBack/seekForward so the in-app controls do not
     * depend on the MediaSession exposing the two optional convenience commands. Media3 still
     * validates the authoritative seek command at the controller boundary.
     */
    fun seekBy(offsetMs: Long) {
        val target = playbackSeekTarget(
            currentPositionMs = mediaController.currentPosition,
            offsetMs = offsetMs,
            durationMs = mediaController.duration,
        )
        mediaController.dispatchSeek(target)
    }

    fun setPlaybackSpeed(speed: Float) = mediaController.setPlaybackSpeed(speed)

    fun clear() = mediaController.clearMediaItems()

    /** Stops foreground video immediately and removes its queue from the background session. */
    fun stopAndClear() {
        mediaController.stop()
        mediaController.clearMediaItems()
    }

    override fun close() {
        mediaController.removeListener(listener)
        mediaController.release()
    }

    companion object {
        /** The returned future should be cancelled if its owner disappears before connection. */
        fun connect(context: Context): ListenableFuture<OpenListPlaybackController> {
            val applicationContext = context.applicationContext
            val token = SessionToken(
                applicationContext,
                ComponentName(applicationContext, OpenListPlaybackService::class.java),
            )
            val controllerFuture = MediaController.Builder(applicationContext, token).buildAsync()
            return Futures.transform(
                controllerFuture,
                ::OpenListPlaybackController,
                MoreExecutors.directExecutor(),
            )
        }
    }
}

private fun MediaController.snapshot(): PlaybackControllerState = PlaybackControllerState(
    queue = List(mediaItemCount, ::getMediaItemAt),
    currentIndex = currentMediaItemIndex,
    isPlaying = isPlaying,
    playWhenReady = playWhenReady,
    playbackState = playbackState,
    repeatMode = repeatMode,
    shuffleEnabled = shuffleModeEnabled,
    playbackSpeed = playbackParameters.speed,
    canSeek = currentSeekDispatch() != PlaybackSeekDispatch.NONE,
    canChangeSpeed = isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH),
)

private fun MediaController.dispatchSeek(positionMs: Long) {
    val currentIndex = currentMediaItemIndex
    val dispatch = playbackSeekDispatch(
        canSeekInCurrentItem = isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM),
        canSeekToMediaItem = isCommandAvailable(Player.COMMAND_SEEK_TO_MEDIA_ITEM),
        isCurrentItemSeekable = isCurrentMediaItemSeekable,
        isCurrentItemLive = isCurrentMediaItemLive,
        currentIndex = currentIndex,
        mediaItemCount = mediaItemCount,
    )
    Log.d(
        PLAYBACK_SEEK_LOG_TAG,
        "targetMs=$positionMs, currentMs=$currentPosition, durationMs=$duration, dispatch=$dispatch",
    )
    when (dispatch) {
        PlaybackSeekDispatch.CURRENT_ITEM -> seekTo(positionMs)
        PlaybackSeekDispatch.INDEXED_ITEM -> seekTo(currentIndex, positionMs)
        PlaybackSeekDispatch.NONE -> Unit
    }
}

private fun MediaController.currentSeekDispatch(): PlaybackSeekDispatch = playbackSeekDispatch(
    canSeekInCurrentItem = isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM),
    canSeekToMediaItem = isCommandAvailable(Player.COMMAND_SEEK_TO_MEDIA_ITEM),
    isCurrentItemSeekable = isCurrentMediaItemSeekable,
    isCurrentItemLive = isCurrentMediaItemLive,
    currentIndex = currentMediaItemIndex,
    mediaItemCount = mediaItemCount,
)

internal enum class PlaybackSeekDispatch {
    CURRENT_ITEM,
    INDEXED_ITEM,
    NONE,
}

/**
 * Chooses the strongest seek command exposed by the connected MediaSession. Some compatible
 * sessions omit the current-item shortcut while still accepting an indexed seek. The indexed
 * form is safe only for a stable, non-live queue item.
 */
internal fun playbackSeekDispatch(
    canSeekInCurrentItem: Boolean,
    canSeekToMediaItem: Boolean,
    isCurrentItemSeekable: Boolean,
    isCurrentItemLive: Boolean,
    currentIndex: Int,
    mediaItemCount: Int,
): PlaybackSeekDispatch = when {
    canSeekInCurrentItem -> PlaybackSeekDispatch.CURRENT_ITEM
    canSeekToMediaItem &&
        isCurrentItemSeekable &&
        !isCurrentItemLive &&
        currentIndex >= 0 &&
        currentIndex < mediaItemCount -> PlaybackSeekDispatch.INDEXED_ITEM
    else -> PlaybackSeekDispatch.NONE
}

internal const val SEEK_INTERVAL_MS = 10_000L
private const val PLAYBACK_SEEK_LOG_TAG = "OpenListSeekUi"

internal fun playbackSeekTarget(
    currentPositionMs: Long,
    offsetMs: Long,
    durationMs: Long,
): Long {
    val safeCurrent = currentPositionMs.coerceAtLeast(0L)
    val target = when {
        offsetMs > 0L && safeCurrent > Long.MAX_VALUE - offsetMs -> Long.MAX_VALUE
        offsetMs == Long.MIN_VALUE -> 0L
        offsetMs < 0L && safeCurrent < -offsetMs -> 0L
        else -> safeCurrent + offsetMs
    }.coerceAtLeast(0L)
    return durationMs
        .takeIf { it != C.TIME_UNSET && it > 0L }
        ?.let(target::coerceAtMost)
        ?: target
}

internal data class SecureMediaItemSpec(
    val mediaId: String,
    val uri: String,
    val displayName: String,
    val mimeType: String?,
    val customCacheKey: String?,
    val subtitles: List<SecureSubtitleSpec>,
)

internal data class SecureSubtitleSpec(
    val mediaId: String,
    val uri: String,
    val displayName: String,
    val mimeType: String,
    val isDefault: Boolean,
)

internal fun MediaEntry.toSecureMediaItemSpec(): SecureMediaItemSpec {
    val request = OpenListMediaRequestRegistry.register(
        remotePath = remotePath,
        knownSize = size.takeIf { it > 0L },
    )
    val safeDisplayName = name
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .ifBlank { "媒体" }
    val selectedSubtitleIndex = defaultSubtitleIndex(name, subtitles)
    return SecureMediaItemSpec(
        mediaId = request.mediaId,
        uri = request.uri,
        displayName = safeDisplayName,
        mimeType = mimeType,
        // HLS manifests and segments are independent resources. Reusing the parent content key
        // would make their byte ranges collide. They bypass disk cache unless a future
        // segment-aware logical-key scheme is introduced.
        customCacheKey = contentKey.value.takeUnless { mimeType == MimeTypes.APPLICATION_M3U8 },
        subtitles = subtitles.mapIndexed { index, subtitle ->
            val subtitleRequest = OpenListMediaRequestRegistry.register(
                remotePath = subtitle.remotePath,
                knownSize = null,
            )
            SecureSubtitleSpec(
                mediaId = subtitleRequest.mediaId,
                uri = subtitleRequest.uri,
                displayName = subtitle.name
                    .substringAfterLast('/')
                    .substringAfterLast('\\')
                    .ifBlank { "字幕" },
                mimeType = subtitle.mimeType,
                isDefault = index == selectedSubtitleIndex,
            )
        },
    )
}

fun MediaEntry.toMediaItem(): MediaItem {
    val spec = toSecureMediaItemSpec()
    val metadata = MediaMetadata.Builder()
        .setTitle(spec.displayName)
        .setDisplayTitle(spec.displayName)
        .setIsPlayable(true)
        .setMediaType(
            when (kind) {
                MediaKind.AUDIO -> MediaMetadata.MEDIA_TYPE_MUSIC
                MediaKind.VIDEO -> MediaMetadata.MEDIA_TYPE_VIDEO
                else -> MediaMetadata.MEDIA_TYPE_MIXED
            },
        )
        .build()
    val builder = MediaItem.Builder()
        .setMediaId(spec.mediaId)
        .setUri(spec.uri)
        .setMimeType(spec.mimeType)
        .setMediaMetadata(metadata)
    spec.customCacheKey?.let(builder::setCustomCacheKey)
    if (spec.subtitles.isNotEmpty()) {
        builder.setSubtitleConfigurations(
            spec.subtitles.map { subtitle ->
                MediaItem.SubtitleConfiguration.Builder(subtitle.uri.toUri())
                    .setId(subtitle.mediaId)
                    .setMimeType(subtitle.mimeType)
                    .setLabel(subtitle.displayName)
                    .setRoleFlags(C.ROLE_FLAG_SUBTITLE)
                    .setSelectionFlags(
                        if (subtitle.isDefault) {
                            C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_AUTOSELECT
                        } else {
                            0
                        },
                    )
                    .build()
            },
        )
    }
    return builder.build()
}
