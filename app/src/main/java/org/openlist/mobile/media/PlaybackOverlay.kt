@file:androidx.annotation.OptIn(
    markerClass = [androidx.media3.common.util.UnstableApi::class],
)

package org.openlist.mobile.media

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.ScaleFactor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.state.rememberErrorState
import androidx.media3.ui.compose.state.rememberProgressStateWithTickInterval
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Full-screen MD3 playback UI for the service-backed [OpenListPlaybackController].
 *
 * Audio keeps playing when this UI is dismissed. An explicitly dismissed video is paused while
 * its queue remains available; an unexpected overlay disposal still stops and clears video.
 */
internal object PlaybackUiTags {
    const val VIDEO_QUEUE_CONTAINER = "video_queue_container"
    const val VIDEO_QUEUE_LIST = "video_queue_list"
    const val VIDEO_GESTURE_SURFACE = "video_gesture_surface"
    const val VIDEO_ZOOM_TOGGLE = "video_zoom_toggle"
    const val PROGRESS_SLIDER = "playback_progress_slider"
    const val PLAYBACK_SPEED = "playback_speed"
    const val PLAYBACK_INFO_BUTTON = "playback_info_button"
    const val PLAYBACK_INFO_DIALOG = "playback_info_dialog"
    const val PLAYBACK_INFO_CLOSE = "playback_info_close"
    const val PLAYBACK_INFO_NETWORK_SPEED = "playback_info_network_speed"
    const val PLAYBACK_INFO_CACHE_STATUS = "playback_info_cache_status"
}

/**
 * Theme-derived colors for the non-fullscreen playback chrome. The video surface and fullscreen
 * cinema controls deliberately retain their black treatment for predictable frame contrast.
 */
internal data class PlaybackChromePalette(
    val pageContainer: Color,
    val pageContent: Color,
    val secondaryContent: Color,
    val controlsContainer: Color,
    val divider: Color,
    val selectedQueueContainer: Color,
    val selectedQueueContent: Color,
)

internal fun playbackChromePalette(colorScheme: ColorScheme): PlaybackChromePalette =
    PlaybackChromePalette(
        pageContainer = colorScheme.surface,
        pageContent = colorScheme.onSurface,
        secondaryContent = colorScheme.onSurfaceVariant,
        controlsContainer = colorScheme.surfaceContainer,
        divider = colorScheme.outlineVariant,
        selectedQueueContainer = colorScheme.secondaryContainer,
        selectedQueueContent = colorScheme.onSecondaryContainer,
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackOverlay(
    controller: OpenListPlaybackController,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activity = LocalContext.current.findActivity()
    val originalOrientation = remember(activity) {
        activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    var isFullscreen by rememberSaveable { mutableStateOf(false) }
    var showPlaybackInfo by rememberSaveable { mutableStateOf(false) }
    val state by controller.state.collectAsStateWithLifecycle()
    val player = controller.mediaController
    val current = state.currentItem
    val isVideo = current?.isVideo == true
    KeepVideoScreenAwake(
        enabled = shouldKeepVideoScreenAwake(
            isVideo = isVideo,
            isPlaying = state.isPlaying,
            playWhenReady = state.playWhenReady,
            playbackState = state.playbackState,
        ),
    )
    val errorState = rememberErrorState(player)
    // The disposer must retain the media type that belongs to its own controller. Without the
    // keyed group, replacing a controller can make the old disposer observe the new controller's
    // media type and clear the wrong session (or fail to clear a video session).
    val stopVideoOnDispose = key(controller) { rememberUpdatedState(isVideo) }
    val dismissedExplicitly = remember(controller) { mutableStateOf(false) }
    val dismissPlayback = {
        // Mark this before notifying the parent: removing the overlay disposes this composition
        // synchronously, and an explicit close must keep the queue available for the shortcut.
        dismissedExplicitly.value = true
        performPlaybackDismissal(
            isVideo = isVideo,
            pauseVideo = controller::pause,
            dismiss = onDismiss,
        )
    }

    BackHandler {
        if (showPlaybackInfo) {
            showPlaybackInfo = false
        } else {
            performPlaybackBack(
                isFullscreen = isFullscreen,
                exitFullscreen = { isFullscreen = false },
                dismissPlayback = dismissPlayback,
            )
        }
    }
    LaunchedEffect(isVideo) {
        if (!isVideo) isFullscreen = false
    }
    LaunchedEffect(activity, isFullscreen, isVideo) {
        activity?.applyPlaybackFullscreen(
            enabled = isFullscreen && isVideo,
            originalOrientation = originalOrientation,
        )
    }
    DisposableEffect(controller) {
        onDispose {
            if (
                shouldStopVideoOnOverlayDispose(
                    isVideo = stopVideoOnDispose.value,
                    dismissedExplicitly = dismissedExplicitly.value,
                )
            ) {
                runCatching(controller::stopAndClear)
            }
        }
    }
    DisposableEffect(activity, originalOrientation) {
        onDispose {
            activity?.applyPlaybackFullscreen(
                enabled = false,
                originalOrientation = originalOrientation,
            )
        }
    }

    if (isFullscreen && isVideo) {
        FullscreenVideoPlaybackContent(
            controller = controller,
            state = state,
            errorMessage = errorState.error?.message,
            playbackInfoVisible = showPlaybackInfo,
            onShowPlaybackInfo = { showPlaybackInfo = true },
            onExitFullscreen = { isFullscreen = false },
            modifier = modifier.fillMaxSize(),
        )
    } else {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            text = current?.displayTitle.orEmpty().ifBlank { "媒体播放" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = dismissPlayback) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "关闭播放界面")
                        }
                    },
                    actions = {
                        if (current != null) {
                            PlaybackInfoButton(
                                onClick = { showPlaybackInfo = true },
                                onVideoSurface = false,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                        actionIconContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
            },
        ) { contentPadding ->
            if (current == null) {
                EmptyPlaybackState(
                    modifier = Modifier.fillMaxSize().padding(contentPadding),
                    onDismiss = onDismiss,
                )
                return@Scaffold
            }

            if (isVideo) {
                VideoPlaybackContent(
                    controller = controller,
                    state = state,
                    errorMessage = errorState.error?.message,
                    onEnterFullscreen = { isFullscreen = true },
                    modifier = Modifier.fillMaxSize().padding(contentPadding),
                )
            } else {
                AudioPlaybackContent(
                    controller = controller,
                    state = state,
                    errorMessage = errorState.error?.message,
                    modifier = Modifier.fillMaxSize().padding(contentPadding),
                )
            }
        }
    }

    if (showPlaybackInfo && current != null) {
        PlaybackInfoDialog(
            controller = controller,
            onDismiss = { showPlaybackInfo = false },
        )
    }
}

/**
 * Prevents Android from dimming or locking the display only while foreground video playback is
 * active. The previous View policy is restored when playback pauses or this overlay disappears.
 */
@Composable
private fun KeepVideoScreenAwake(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        if (!enabled) {
            onDispose {}
        } else {
            val wasKeepingScreenOn = view.keepScreenOn
            view.keepScreenOn = true
            onDispose {
                view.keepScreenOn = wasKeepingScreenOn
            }
        }
    }
}

internal fun shouldKeepVideoScreenAwake(
    isVideo: Boolean,
    isPlaying: Boolean,
    playWhenReady: Boolean,
    @Player.State playbackState: Int,
): Boolean =
    isVideo &&
        (
            isPlaying ||
                (playWhenReady && playbackState == Player.STATE_BUFFERING)
        )

@Composable
private fun VideoPlaybackContent(
    controller: OpenListPlaybackController,
    state: PlaybackControllerState,
    errorMessage: String?,
    onEnterFullscreen: () -> Unit,
    modifier: Modifier,
) {
    val chrome = playbackChromePalette(MaterialTheme.colorScheme)
    Column(modifier.background(chrome.pageContainer)) {
        Box(Modifier.fillMaxWidth().weight(1.15f)) {
            VideoViewport(
                controller = controller,
                playWhenReady = state.playWhenReady,
                playbackState = state.playbackState,
                modifier = Modifier.fillMaxSize(),
            )
            if (state.playbackState == Player.STATE_BUFFERING) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White,
                )
            }
            FullscreenToggleButton(
                isFullscreen = false,
                onToggle = onEnterFullscreen,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            )
        }
        errorMessage?.let { PlaybackError(it, onVideoSurface = false) }
        Surface(color = chrome.controlsContainer) {
            PlaybackControls(
                controller = controller,
                state = state,
                onVideoSurface = false,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        HorizontalDivider(color = chrome.divider)
        VideoQueueList(
            queue = state.queue,
            currentIndex = state.currentIndex,
            isPlaying = state.isPlaying,
            onSelect = controller::play,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

@Composable
private fun FullscreenVideoPlaybackContent(
    controller: OpenListPlaybackController,
    state: PlaybackControllerState,
    errorMessage: String?,
    playbackInfoVisible: Boolean,
    onShowPlaybackInfo: () -> Unit,
    onExitFullscreen: () -> Unit,
    modifier: Modifier,
) {
    var controlsVisible by remember { mutableStateOf(true) }
    var controlInteractionActive by remember { mutableStateOf(false) }
    var videoInteractionActive by remember { mutableStateOf(false) }
    var interactionEpoch by remember { mutableIntStateOf(0) }
    var videoScaleMode by remember(state.currentIndex) { mutableStateOf(VideoScaleMode.FIT) }
    val interactionActive =
        controlInteractionActive || videoInteractionActive || playbackInfoVisible
    val toggleControls = {
        if (controlsVisible) {
            controlsVisible = false
        } else {
            controlsVisible = true
            interactionEpoch += 1
        }
    }
    val currentToggleControls by rememberUpdatedState(toggleControls)
    val currentControlInteraction by rememberUpdatedState<(Boolean) -> Unit> { active ->
        controlInteractionActive = active
        interactionEpoch += 1
    }
    val currentVideoInteraction by rememberUpdatedState<(Boolean) -> Unit> { active ->
        videoInteractionActive = active
        interactionEpoch += 1
    }

    LaunchedEffect(
        controlsVisible,
        interactionActive,
        interactionEpoch,
        state.isPlaying,
        state.playbackState,
        errorMessage,
        playbackInfoVisible,
    ) {
        val controlsMustStayVisible =
            !state.isPlaying ||
                state.playbackState != Player.STATE_READY ||
                errorMessage != null
        when {
            controlsMustStayVisible -> controlsVisible = true
            controlsVisible && !interactionActive -> {
                val epochBeforeDelay = interactionEpoch
                delay(FULLSCREEN_CONTROLS_TIMEOUT_MS)
                if (shouldHideFullscreenControlsAfterTimeout(
                        controlsVisible = controlsVisible,
                        interactionActive = interactionActive,
                        interactionEpoch = interactionEpoch,
                        scheduledEpoch = epochBeforeDelay,
                        isPlaying = state.isPlaying,
                        playbackState = state.playbackState,
                    )
                ) {
                    controlsVisible = false
                }
            }
        }
    }
    LaunchedEffect(state.currentIndex) {
        controlsVisible = true
        interactionEpoch += 1
    }
    LaunchedEffect(playbackInfoVisible) {
        if (!playbackInfoVisible) {
            controlsVisible = true
            interactionEpoch += 1
        }
    }

    Box(modifier.background(Color.Black)) {
        VideoViewport(
            controller = controller,
            playWhenReady = state.playWhenReady,
            playbackState = state.playbackState,
            onTap = currentToggleControls,
            scaleMode = videoScaleMode,
            onInteractionActiveChange = currentVideoInteraction,
            modifier = Modifier.fillMaxSize(),
        )
        if (state.playbackState == Player.STATE_BUFFERING) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color.White)
        }
        AnimatedVisibility(
            visible = controlsVisible,
            modifier = Modifier.align(Alignment.TopEnd),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PlaybackInfoButton(
                    onClick = {
                        interactionEpoch += 1
                        onShowPlaybackInfo()
                    },
                    onVideoSurface = true,
                )
                VideoScaleToggleButton(
                    isZoomed = videoScaleMode == VideoScaleMode.ZOOMED,
                    onToggle = {
                        val result = toggleVideoScale(videoScaleMode)
                        videoScaleMode = result.scaleMode
                        interactionEpoch += 1
                        if (result.hideControls) controlsVisible = false
                    },
                )
                FullscreenToggleButton(
                    isFullscreen = true,
                    onToggle = onExitFullscreen,
                )
            }
        }
        AnimatedVisibility(
            visible = controlsVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            var interactionActive = false
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val hasPressedPointer = event.changes.any { it.pressed }
                                if (hasPressedPointer != interactionActive) {
                                    interactionActive = hasPressedPointer
                                    currentControlInteraction(interactionActive)
                                }
                            }
                        }
                    },
            ) {
                errorMessage?.let { PlaybackError(it, onVideoSurface = true) }
                Surface(color = Color.Black.copy(alpha = 0.72f)) {
                    PlaybackControls(
                        controller = controller,
                        state = state,
                        onVideoSurface = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * Aspect-ratio-aware video viewport with direct-manipulation playback gestures.
 *
 * [ContentFrame] owns video sizing and keeps the decoded frame inside the available bounds. A
 * long press temporarily overrides the current Media3 speed and restores the exact previous speed
 * when the pointer is released, cancelled, or this viewport leaves composition.
 */
@Composable
private fun VideoViewport(
    controller: OpenListPlaybackController,
    playWhenReady: Boolean,
    @Player.State playbackState: Int,
    modifier: Modifier = Modifier,
    onTap: () -> Unit = {},
    scaleMode: VideoScaleMode = VideoScaleMode.FIT,
    onInteractionActiveChange: (Boolean) -> Unit = {},
) {
    val player = controller.mediaController
    var speedBeforeBoost by remember(player) { mutableStateOf<Float?>(null) }
    var gestureSeekPositionMs by remember(player) { mutableStateOf<Long?>(null) }
    val restoreSpeed = {
        speedBeforeBoost?.let { previousSpeed ->
            runCatching { controller.setPlaybackSpeed(previousSpeed) }
        }
        speedBeforeBoost = null
    }

    DisposableEffect(player) {
        onDispose {
            restoreSpeed()
        }
    }
    LaunchedEffect(playWhenReady, playbackState) {
        if (
            !canStartLongPressSpeedBoost(
                playWhenReady = playWhenReady,
                playbackState = playbackState,
                canChangeSpeed = true,
            )
        ) {
            restoreSpeed()
        }
    }

    Box(
        modifier = modifier.background(Color.Black).clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        ContentFrame(
            player = player,
            // Some MIUI/QTI decoder paths repeatedly detach TextureView buffers and render no
            // frames. SurfaceView keeps stable codec ownership; the Compose gesture layer declared
            // after this frame remains the pointer-input target. Scaling changes only the measured
            // surface bounds, so the player keeps ownership of the same SurfaceView instance.
            surfaceType = SURFACE_TYPE_SURFACE_VIEW,
            contentScale = videoContentScale(scaleMode),
            modifier = Modifier.fillMaxSize(),
        )
        // Keep gesture handling in a regular Compose layer above the video surface.
        VideoGestureSurface(
            onTap = onTap,
            canSeek = player::canSeekWithCurrentCommands,
            currentPositionMs = { player.currentPosition },
            durationMs = {
                playbackDurationMs(
                    player.duration,
                    player.contentDuration,
                )
            },
            onSeekBack = {
                if (player.canSeekWithCurrentCommands()) controller.seekBack()
            },
            onSeekForward = {
                if (player.canSeekWithCurrentCommands()) controller.seekForward()
            },
            onHorizontalSeekPreview = { targetMs ->
                if (targetMs != null) restoreSpeed()
                gestureSeekPositionMs = targetMs
            },
            onHorizontalSeek = { targetMs ->
                gestureSeekPositionMs = null
                if (player.canSeekWithCurrentCommands()) controller.seekTo(targetMs)
            },
            onSpeedBoostStart = {
                if (
                    speedBeforeBoost != null ||
                    !canStartLongPressSpeedBoost(
                        playWhenReady = player.playWhenReady,
                        playbackState = player.playbackState,
                        canChangeSpeed = player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH),
                    )
                ) {
                    false
                } else {
                    speedBeforeBoost = player.playbackParameters.speed
                    controller.setPlaybackSpeed(LONG_PRESS_PLAYBACK_SPEED)
                    true
                }
            },
            onSpeedBoostEnd = restoreSpeed,
            onInteractionActiveChange = onInteractionActiveChange,
            modifier = Modifier.matchParentSize(),
        )
        if (speedBeforeBoost != null) {
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
                color = Color.Black.copy(alpha = 0.68f),
                shape = CircleShape,
            ) {
                Text(
                    text = "2× 加速播放",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        gestureSeekPositionMs?.let { targetMs ->
            Surface(
                modifier = Modifier.align(Alignment.Center),
                color = Color.Black.copy(alpha = 0.72f),
                shape = CircleShape,
            ) {
                Text(
                    text = "滑动至 ${formatDuration(targetMs)}",
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

/** A Compose-owned hit target kept separate from Media3's native video surface. */
@Composable
internal fun VideoGestureSurface(
    onTap: () -> Unit,
    canSeek: () -> Boolean,
    currentPositionMs: () -> Long,
    durationMs: () -> Long?,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onHorizontalSeekPreview: (Long?) -> Unit,
    onHorizontalSeek: (Long) -> Unit,
    onSpeedBoostStart: () -> Boolean,
    onSpeedBoostEnd: () -> Unit,
    modifier: Modifier = Modifier,
    onInteractionActiveChange: (Boolean) -> Unit = {},
) {
    val currentOnTap by rememberUpdatedState(onTap)
    val currentCanSeek by rememberUpdatedState(canSeek)
    val currentPositionMsProvider by rememberUpdatedState(currentPositionMs)
    val currentDurationMs by rememberUpdatedState(durationMs)
    val currentSeekBack by rememberUpdatedState(onSeekBack)
    val currentSeekForward by rememberUpdatedState(onSeekForward)
    val currentHorizontalSeekPreview by rememberUpdatedState(onHorizontalSeekPreview)
    val currentHorizontalSeek by rememberUpdatedState(onHorizontalSeek)
    val currentSpeedBoostStart by rememberUpdatedState(onSpeedBoostStart)
    val currentSpeedBoostEnd by rememberUpdatedState(onSpeedBoostEnd)
    val currentInteractionActiveChange by rememberUpdatedState(onInteractionActiveChange)
    val horizontalSeekPixelsPerSecond = with(LocalDensity.current) {
        VIDEO_HORIZONTAL_SEEK_DP_PER_SECOND.dp.toPx()
    }
    var speedBoostActive by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .testTag(PlaybackUiTags.VIDEO_GESTURE_SURFACE)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    var interactionActive = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val hasPressedPointer = event.changes.any { it.pressed }
                            if (hasPressedPointer != interactionActive) {
                                interactionActive = hasPressedPointer
                                currentInteractionActiveChange(interactionActive)
                            }
                        }
                    } finally {
                        if (interactionActive) currentInteractionActiveChange(false)
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { currentOnTap() },
                    onDoubleTap = { tapPosition ->
                        if (!currentCanSeek()) {
                            return@detectTapGestures
                        } else if (tapPosition.x < size.width / 2f) {
                            currentSeekBack()
                        } else {
                            currentSeekForward()
                        }
                    },
                    onLongPress = {
                        speedBoostActive = currentSpeedBoostStart()
                    },
                    onPress = {
                        try {
                            awaitRelease()
                        } finally {
                            if (speedBoostActive) {
                                currentSpeedBoostEnd()
                                speedBoostActive = false
                            }
                        }
                    },
                )
            }
            .pointerInput(horizontalSeekPixelsPerSecond) {
                var pendingSeekPositionMs: Long? = null
                var dragStartPositionMs: Long? = null
                var dragDurationMs: Long? = null
                var accumulatedDragPx = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        if (speedBoostActive) {
                            currentSpeedBoostEnd()
                            speedBoostActive = false
                        }
                        val durationMs = currentDurationMs()
                        dragStartPositionMs = if (currentCanSeek() && durationMs != null) {
                            currentPositionMsProvider()
                        } else {
                            null
                        }
                        dragDurationMs = durationMs.takeIf { dragStartPositionMs != null }
                        accumulatedDragPx = 0f
                        pendingSeekPositionMs = dragStartPositionMs?.let { startPositionMs ->
                            horizontalSeekTarget(
                                startPositionMs = startPositionMs,
                                dragDeltaPx = accumulatedDragPx,
                                pixelsPerSecond = horizontalSeekPixelsPerSecond,
                                durationMs = dragDurationMs,
                            )
                        }
                        currentHorizontalSeekPreview(pendingSeekPositionMs)
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        accumulatedDragPx += dragAmount
                        pendingSeekPositionMs = dragStartPositionMs?.let { startPositionMs ->
                            horizontalSeekTarget(
                                startPositionMs = startPositionMs,
                                dragDeltaPx = accumulatedDragPx,
                                pixelsPerSecond = horizontalSeekPixelsPerSecond,
                                durationMs = dragDurationMs,
                            )
                        }
                        currentHorizontalSeekPreview(pendingSeekPositionMs)
                    },
                    onDragEnd = {
                        val targetMs = pendingSeekPositionMs
                        pendingSeekPositionMs = null
                        dragStartPositionMs = null
                        dragDurationMs = null
                        accumulatedDragPx = 0f
                        currentHorizontalSeekPreview(null)
                        if (targetMs != null && currentCanSeek()) {
                            currentHorizontalSeek(targetMs)
                        }
                    },
                    onDragCancel = {
                        pendingSeekPositionMs = null
                        dragStartPositionMs = null
                        dragDurationMs = null
                        accumulatedDragPx = 0f
                        currentHorizontalSeekPreview(null)
                    },
                )
            },
    )
}

internal enum class VideoScaleMode {
    FIT,
    ZOOMED,
}

internal data class VideoScaleToggleResult(
    val scaleMode: VideoScaleMode,
    val hideControls: Boolean,
)

internal fun toggleVideoScale(currentMode: VideoScaleMode): VideoScaleToggleResult =
    when (currentMode) {
        VideoScaleMode.FIT -> VideoScaleToggleResult(
            scaleMode = VideoScaleMode.ZOOMED,
            hideControls = true,
        )
        VideoScaleMode.ZOOMED -> VideoScaleToggleResult(
            scaleMode = VideoScaleMode.FIT,
            hideControls = false,
        )
    }

private val zoomedVideoContentScale = object : ContentScale {
    override fun computeScaleFactor(srcSize: Size, dstSize: Size): ScaleFactor =
        videoScaleFactor(srcSize, dstSize, VideoScaleMode.ZOOMED)
}

private fun videoContentScale(mode: VideoScaleMode): ContentScale = when (mode) {
    VideoScaleMode.FIT -> ContentScale.Fit
    VideoScaleMode.ZOOMED -> zoomedVideoContentScale
}

internal fun videoScaleFactor(
    srcSize: Size,
    dstSize: Size,
    mode: VideoScaleMode,
): ScaleFactor {
    val fitScale = ContentScale.Fit.computeScaleFactor(srcSize, dstSize)
    val zoom = if (mode == VideoScaleMode.ZOOMED) VIDEO_ZOOM_FACTOR else 1f
    return ScaleFactor(
        scaleX = fitScale.scaleX * zoom,
        scaleY = fitScale.scaleY * zoom,
    )
}

@Composable
internal fun VideoScaleToggleButton(
    isZoomed: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onToggle,
        modifier = modifier
            .testTag(PlaybackUiTags.VIDEO_ZOOM_TOGGLE)
            .background(Color.Black.copy(alpha = 0.62f), CircleShape),
    ) {
        Icon(
            imageVector = if (isZoomed) Icons.Default.ZoomOut else Icons.Default.ZoomIn,
            contentDescription = if (isZoomed) "恢复完整画面" else "放大画面",
            tint = Color.White,
        )
    }
}

@Composable
internal fun FullscreenToggleButton(
    isFullscreen: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onToggle,
        modifier = modifier.background(Color.Black.copy(alpha = 0.62f), CircleShape),
    ) {
        Icon(
            imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
            contentDescription = if (isFullscreen) "退出全屏" else "进入全屏",
            tint = Color.White,
        )
    }
}

@Composable
internal fun VideoQueueList(
    queue: List<MediaItem>,
    currentIndex: Int,
    isPlaying: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chrome = playbackChromePalette(MaterialTheme.colorScheme)
    val targetIndex = currentIndex.takeIf { it in queue.indices } ?: 0
    val queueListState = rememberLazyListState(initialFirstVisibleItemIndex = targetIndex)
    LaunchedEffect(targetIndex, queue.size) {
        if (queue.isNotEmpty() && queueListState.firstVisibleItemIndex != targetIndex) {
            queueListState.scrollToItem(targetIndex)
        }
    }

    Column(
        modifier
            .background(chrome.pageContainer)
            .testTag(PlaybackUiTags.VIDEO_QUEUE_CONTAINER),
    ) {
        Text(
            text = "同目录视频",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = chrome.pageContent,
            style = MaterialTheme.typography.titleSmall,
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag(PlaybackUiTags.VIDEO_QUEUE_LIST),
            state = queueListState,
        ) {
            itemsIndexed(
                items = queue,
                key = { index, item -> "video:${item.mediaId}:$index" },
            ) { index, item ->
                val selected = index == currentIndex
                ListItem(
                    headlineContent = {
                        Text(item.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Text(
                            text = if (selected && isPlaying) {
                                "正在播放"
                            } else {
                                "${index + 1} / ${queue.size}"
                            },
                            maxLines = 1,
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = if (selected) Icons.Default.PlayArrow else Icons.Default.Movie,
                            contentDescription = null,
                        )
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = if (selected) {
                            chrome.selectedQueueContainer
                        } else {
                            chrome.pageContainer
                        },
                        headlineColor = if (selected) {
                            chrome.selectedQueueContent
                        } else {
                            chrome.pageContent
                        },
                        supportingColor = if (selected) {
                            chrome.selectedQueueContent.copy(alpha = 0.78f)
                        } else {
                            chrome.secondaryContent
                        },
                        leadingIconColor = if (selected) {
                            chrome.selectedQueueContent
                        } else {
                            chrome.secondaryContent
                        },
                    ),
                    modifier = Modifier.clickable { onSelect(index) },
                )
            }
        }
    }
}

@Composable
private fun AudioPlaybackContent(
    controller: OpenListPlaybackController,
    state: PlaybackControllerState,
    errorMessage: String?,
    modifier: Modifier,
) {
    val chrome = playbackChromePalette(MaterialTheme.colorScheme)
    Column(modifier.background(chrome.pageContainer)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(152.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(72.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                if (state.playbackState == Player.STATE_BUFFERING) {
                    CircularProgressIndicator(Modifier.fillMaxSize().padding(8.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = state.currentItem?.displayTitle.orEmpty(),
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${state.currentIndex + 1} / ${state.queue.size}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        errorMessage?.let { PlaybackError(it, onVideoSurface = false) }
        PlaybackControls(
            controller = controller,
            state = state,
            onVideoSurface = false,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        )
        HorizontalDivider(color = chrome.divider)
        Text(
            text = "播放队列",
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(
                items = state.queue,
                key = { index, item -> "${item.mediaId}:$index" },
            ) { index, item ->
                val selected = index == state.currentIndex
                ListItem(
                    headlineContent = {
                        Text(item.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Text(
                            text = if (selected && state.isPlaying) "正在播放" else "第 ${index + 1} 项",
                            maxLines = 1,
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = if (selected && state.isPlaying) Icons.Default.PlayArrow else Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = if (selected) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            Color.Transparent
                        },
                    ),
                    modifier = Modifier.clickable { controller.play(index) },
                )
            }
        }
    }
}

@Composable
private fun PlaybackControls(
    controller: OpenListPlaybackController,
    state: PlaybackControllerState,
    onVideoSurface: Boolean,
    modifier: Modifier,
) {
    val player = controller.mediaController
    val progress = rememberProgressStateWithTickInterval(
        player = player,
        tickIntervalMs = PLAYBACK_PROGRESS_TICK_MS,
    )
    val transportButtonColors = if (onVideoSurface) {
        IconButtonDefaults.iconButtonColors(
            contentColor = Color.White,
            disabledContentColor = Color.White.copy(alpha = 0.38f),
        )
    } else {
        IconButtonDefaults.iconButtonColors()
    }
    val playPauseButtonColors = if (onVideoSurface) {
        IconButtonDefaults.filledIconButtonColors(
            containerColor = Color.White,
            contentColor = Color.Black,
            disabledContainerColor = Color.White.copy(alpha = 0.12f),
            disabledContentColor = Color.White.copy(alpha = 0.38f),
        )
    } else {
        IconButtonDefaults.filledIconButtonColors()
    }
    val secondary = if (onVideoSurface) {
        Color.White.copy(alpha = 0.74f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val durationMs = playbackDurationMs(
        progress.durationMs,
        player.duration,
        player.contentDuration,
    )
    // Query the connected controller directly. The collected snapshot is useful for rendering,
    // but a command can be granted or revoked between that snapshot and the user's touch.
    val canSeekNow = player.canSeekWithCurrentCommands()
    val canChangeSpeedNow = player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)
    val currentMediaId = state.currentItem?.mediaId
    var scrubPositionMs by remember(state.currentIndex, currentMediaId) { mutableStateOf<Long?>(null) }
    var pendingSeekPositionMs by remember(state.currentIndex, currentMediaId) {
        mutableStateOf<Long?>(null)
    }
    val displayedPositionMs = scrubPositionMs ?: pendingSeekPositionMs ?: progress.currentPositionMs

    LaunchedEffect(pendingSeekPositionMs, progress.currentPositionMs) {
        val pendingPositionMs = pendingSeekPositionMs ?: return@LaunchedEffect
        if (
            abs(progress.currentPositionMs - pendingPositionMs) <=
            PENDING_SEEK_CONFIRMATION_TOLERANCE_MS
        ) {
            pendingSeekPositionMs = null
        }
    }
    LaunchedEffect(pendingSeekPositionMs) {
        val pendingPositionMs = pendingSeekPositionMs ?: return@LaunchedEffect
        delay(PENDING_SEEK_TIMEOUT_MS)
        if (pendingSeekPositionMs == pendingPositionMs) pendingSeekPositionMs = null
    }

    Column(modifier) {
        SeekablePlaybackSlider(
            currentPositionMs = displayedPositionMs,
            durationMs = durationMs,
            enabled = canSeekNow,
            mediaItemKey = currentMediaId,
            onScrub = {
                pendingSeekPositionMs = null
                scrubPositionMs = it
            },
            onSeek = { positionMs ->
                scrubPositionMs = null
                if (player.canSeekWithCurrentCommands()) {
                    pendingSeekPositionMs = positionMs
                    controller.seekTo(positionMs)
                } else {
                    pendingSeekPositionMs = null
                }
            },
            onVideoSurface = onVideoSurface,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(displayedPositionMs), color = secondary, style = MaterialTheme.typography.labelMedium)
            Text(formatDuration(durationMs ?: C.TIME_UNSET), color = secondary, style = MaterialTheme.typography.labelMedium)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = controller::skipToPrevious,
                enabled = state.currentIndex > 0,
                colors = transportButtonColors,
            ) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "上一个")
            }
            IconButton(
                onClick = {
                    if (player.canSeekWithCurrentCommands()) controller.seekBack()
                },
                enabled = canSeekNow,
                colors = transportButtonColors,
            ) {
                Icon(Icons.Default.Replay10, contentDescription = "后退 10 秒")
            }
            FilledIconButton(
                onClick = {
                    when {
                        state.isPlaying -> controller.pause()
                        state.playbackState == Player.STATE_ENDED -> {
                            controller.seekTo(0L)
                            controller.play()
                        }
                        else -> controller.play()
                    }
                },
                modifier = Modifier.size(64.dp),
                colors = playPauseButtonColors,
            ) {
                Icon(
                    imageVector = when {
                        state.isPlaying -> Icons.Default.Pause
                        state.playbackState == Player.STATE_ENDED -> Icons.Default.Replay
                        else -> Icons.Default.PlayArrow
                    },
                    contentDescription = when {
                        state.isPlaying -> "暂停"
                        state.playbackState == Player.STATE_ENDED -> "重新播放"
                        else -> "播放"
                    },
                    modifier = Modifier.size(34.dp),
                )
            }
            IconButton(
                onClick = {
                    if (player.canSeekWithCurrentCommands()) controller.seekForward()
                },
                enabled = canSeekNow,
                colors = transportButtonColors,
            ) {
                Icon(Icons.Default.Forward10, contentDescription = "前进 10 秒")
            }
            IconButton(
                onClick = {
                    if (player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) {
                        controller.setPlaybackSpeed(nextPlaybackSpeed(player.playbackParameters.speed))
                    }
                },
                enabled = canChangeSpeedNow,
                modifier = Modifier.testTag(PlaybackUiTags.PLAYBACK_SPEED),
                colors = transportButtonColors,
            ) {
                Text(
                    text = formatPlaybackSpeed(state.playbackSpeed),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            IconButton(
                onClick = controller::skipToNext,
                enabled = state.currentIndex in 0 until state.queue.lastIndex,
                colors = transportButtonColors,
            ) {
                Icon(Icons.Default.SkipNext, contentDescription = "下一个")
            }
        }
    }
}

@Composable
internal fun SeekablePlaybackSlider(
    currentPositionMs: Long,
    durationMs: Long?,
    onScrub: (Long) -> Unit,
    onSeek: (Long) -> Unit,
    onVideoSurface: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    mediaItemKey: String? = null,
) {
    val safeDuration = durationMs?.takeIf { it > 0L }
    var draggedFraction by remember(mediaItemKey) { mutableStateOf<Float?>(null) }
    var draggedDurationMs by remember(mediaItemKey) { mutableStateOf<Long?>(null) }
    val currentFraction = playbackPositionFraction(currentPositionMs, safeDuration)
    val sliderValue = (draggedFraction ?: currentFraction).coerceIn(0f, 1f)
    val sliderColors = if (onVideoSurface) {
        SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = Color.White,
            inactiveTrackColor = Color.White.copy(alpha = 0.32f),
            disabledThumbColor = Color.White.copy(alpha = 0.48f),
            disabledActiveTrackColor = Color.White.copy(alpha = 0.32f),
            disabledInactiveTrackColor = Color.White.copy(alpha = 0.16f),
        )
    } else {
        SliderDefaults.colors()
    }

    Slider(
        value = sliderValue,
        onValueChange = { fraction ->
            val clampedFraction = fraction.coerceIn(0f, 1f)
            val durationForDrag = draggedDurationMs ?: safeDuration
            if (draggedDurationMs == null) draggedDurationMs = durationForDrag
            draggedFraction = clampedFraction
            durationForDrag?.let { onScrub(playbackPositionFromFraction(clampedFraction, it)) }
        },
        onValueChangeFinished = {
            val finalFraction = draggedFraction
            val durationForDrag = draggedDurationMs ?: safeDuration
            draggedFraction = null
            draggedDurationMs = null
            if (finalFraction != null && durationForDrag != null) {
                onSeek(playbackPositionFromFraction(finalFraction, durationForDrag))
            }
        },
        enabled = (safeDuration != null || draggedDurationMs != null) && enabled,
        colors = sliderColors,
        modifier = modifier.testTag(PlaybackUiTags.PROGRESS_SLIDER),
    )
}

private const val LONG_PRESS_PLAYBACK_SPEED = 2f
private const val FULLSCREEN_CONTROLS_TIMEOUT_MS = 3_000L
internal const val VIDEO_ZOOM_FACTOR = 1.5f
private const val PLAYBACK_PROGRESS_TICK_MS = 250L
private const val PENDING_SEEK_CONFIRMATION_TOLERANCE_MS = 5_000L
private const val PENDING_SEEK_TIMEOUT_MS = 60_000L
private val PLAYBACK_SPEED_STEPS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

private fun Player.canSeekWithCurrentCommands(): Boolean =
    playbackSeekDispatch(
        canSeekInCurrentItem = isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM),
        canSeekToMediaItem = isCommandAvailable(Player.COMMAND_SEEK_TO_MEDIA_ITEM),
        isCurrentItemSeekable = isCurrentMediaItemSeekable,
        isCurrentItemLive = isCurrentMediaItemLive,
        currentIndex = currentMediaItemIndex,
        mediaItemCount = mediaItemCount,
    ) != PlaybackSeekDispatch.NONE

internal fun shouldHideFullscreenControlsAfterTimeout(
    controlsVisible: Boolean,
    interactionActive: Boolean,
    interactionEpoch: Int,
    scheduledEpoch: Int,
    isPlaying: Boolean,
    @Player.State playbackState: Int,
): Boolean =
    controlsVisible &&
        !interactionActive &&
        interactionEpoch == scheduledEpoch &&
        isPlaying &&
        playbackState == Player.STATE_READY

internal fun canStartLongPressSpeedBoost(
    playWhenReady: Boolean,
    @Player.State playbackState: Int,
    canChangeSpeed: Boolean,
): Boolean =
    canChangeSpeed &&
        playWhenReady &&
        (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING)

internal fun horizontalSeekTarget(
    startPositionMs: Long,
    dragDeltaPx: Float,
    pixelsPerSecond: Float,
    durationMs: Long?,
): Long? {
    val duration = durationMs?.takeIf { it > 0L } ?: return null
    if (!dragDeltaPx.isFinite() || !pixelsPerSecond.isFinite() || pixelsPerSecond <= 0f) {
        return null
    }
    val startPosition = startPositionMs.coerceIn(0L, duration).toDouble()
    val wholeSecondOffset = (dragDeltaPx.toDouble() / pixelsPerSecond.toDouble()).toLong()
    return (startPosition + wholeSecondOffset.toDouble() * 1_000.0)
        .coerceIn(0.0, duration.toDouble())
        .toLong()
}

internal const val VIDEO_HORIZONTAL_SEEK_DP_PER_SECOND = 8f

internal fun playbackDurationMs(vararg candidates: Long): Long? =
    candidates.firstOrNull { it != C.TIME_UNSET && it > 0L }

internal fun playbackPositionFraction(positionMs: Long, durationMs: Long?): Float {
    val duration = durationMs?.takeIf { it > 0L } ?: return 0f
    return (positionMs.coerceIn(0L, duration).toDouble() / duration.toDouble())
        .toFloat()
        .coerceIn(0f, 1f)
}

internal fun playbackPositionFromFraction(fraction: Float, durationMs: Long): Long {
    if (durationMs <= 0L) return 0L
    return (fraction.coerceIn(0f, 1f).toDouble() * durationMs.toDouble())
        .toLong()
        .coerceIn(0L, durationMs)
}

internal fun nextPlaybackSpeed(currentSpeed: Float): Float =
    PLAYBACK_SPEED_STEPS.firstOrNull { it > currentSpeed + PLAYBACK_SPEED_EPSILON }
        ?: PLAYBACK_SPEED_STEPS.first()

private fun formatPlaybackSpeed(speed: Float): String {
    val rounded = speed.toInt()
    return if (abs(speed - rounded) < PLAYBACK_SPEED_EPSILON) {
        "${rounded}×"
    } else {
        "${"%.2f".format(speed).trimEnd('0').trimEnd('.')}×"
    }
}

private const val PLAYBACK_SPEED_EPSILON = 0.01f

@Composable
private fun PlaybackError(message: String, onVideoSurface: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (onVideoSurface) Color(0xCC8C1D18) else MaterialTheme.colorScheme.errorContainer,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = if (onVideoSurface) Color.White else MaterialTheme.colorScheme.onErrorContainer,
        )
        Text(
            text = message.ifBlank { "媒体播放失败" },
            color = if (onVideoSurface) Color.White else MaterialTheme.colorScheme.onErrorContainer,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun EmptyPlaybackState(modifier: Modifier, onDismiss: () -> Unit) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text("播放队列为空", style = MaterialTheme.typography.titleLarge)
        IconButton(onClick = onDismiss) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
    }
}

private val MediaItem.isVideo: Boolean
    get() = mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_VIDEO ||
        localConfiguration?.mimeType?.startsWith("video/") == true

private val MediaItem.displayTitle: String
    get() = mediaMetadata.title?.toString()
        ?.takeIf(String::isNotBlank)
        ?: mediaMetadata.displayTitle?.toString()?.takeIf(String::isNotBlank)
        ?: "未知媒体"

private fun formatDuration(valueMs: Long): String {
    val safeMs = valueMs.takeUnless { it == C.TIME_UNSET || it < 0L } ?: 0L
    val totalSeconds = safeMs / 1_000L
    val seconds = totalSeconds % 60L
    val totalMinutes = totalSeconds / 60L
    val minutes = totalMinutes % 60L
    val hours = totalMinutes / 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

internal fun performPlaybackDismissal(
    isVideo: Boolean,
    pauseVideo: () -> Unit,
    dismiss: () -> Unit,
) {
    if (isVideo) pauseVideo()
    dismiss()
}

internal fun shouldStopVideoOnOverlayDispose(
    isVideo: Boolean,
    dismissedExplicitly: Boolean,
): Boolean = isVideo && !dismissedExplicitly

internal fun performPlaybackBack(
    isFullscreen: Boolean,
    exitFullscreen: () -> Unit,
    dismissPlayback: () -> Unit,
) {
    if (isFullscreen) exitFullscreen() else dismissPlayback()
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.findActivity()
    else -> null
}

private fun Activity.applyPlaybackFullscreen(enabled: Boolean, originalOrientation: Int) {
    val insetsController = WindowCompat.getInsetsController(window, window.decorView)
    if (enabled) {
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        if (requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    } else {
        insetsController.show(WindowInsetsCompat.Type.systemBars())
        if (requestedOrientation != originalOrientation) requestedOrientation = originalOrientation
    }
}
