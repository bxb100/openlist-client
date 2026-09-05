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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitDragOrCancellation
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.automirrored.filled.VolumeUp
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.ScaleFactor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.state.rememberErrorState
import androidx.media3.ui.compose.state.rememberProgressStateWithTickInterval
import android.widget.Toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import org.openlist.mobile.ui.theme.OpenListMediaColors

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
    const val VIDEO_ADJUSTMENT_FEEDBACK = "video_adjustment_feedback"
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

/** Pages and sheets inherit the app theme; controls over video carry their own paired colors. */
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
    val inPictureInPicture = rememberIsInPictureInPictureMode(activity)
    val deviceControls = rememberPlaybackDeviceControls(activity, isVideo && !inPictureInPicture)
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
            deviceControls = deviceControls,
            state = state,
            errorMessage = errorState.error?.message,
            playbackInfoVisible = showPlaybackInfo,
            onShowPlaybackInfo = { showPlaybackInfo = true },
            onExitFullscreen = { isFullscreen = false },
            modifier = modifier.fillMaxSize(),
        )
    } else if (isVideo && current != null) {
        VideoPlaybackContent(
            controller = controller,
            deviceControls = deviceControls,
            state = state,
            errorMessage = errorState.error?.message,
            onBack = dismissPlayback,
            onShowPlaybackInfo = { showPlaybackInfo = true },
            onEnterFullscreen = { isFullscreen = true },
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
                            text = "正在播放",
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoPlaybackContent(
    controller: OpenListPlaybackController,
    deviceControls: PlaybackDeviceControls?,
    state: PlaybackControllerState,
    errorMessage: String?,
    onBack: () -> Unit,
    onShowPlaybackInfo: () -> Unit,
    onEnterFullscreen: () -> Unit,
    modifier: Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("视频播放") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "关闭播放界面")
                    }
                },
                actions = {
                    PlaybackInfoButton(onClick = onShowPlaybackInfo, onVideoSurface = false)
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val wide = maxWidth >= 840.dp && LocalDensity.current.fontScale < 1.5f
            if (wide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    WindowedVideoPlayer(
                        controller, deviceControls, state, errorMessage, onEnterFullscreen,
                        Modifier.weight(1.8f).fillMaxSize(),
                    )
                    VideoQueueList(
                        queue = state.queue,
                        currentIndex = state.currentIndex,
                        isPlaying = state.isPlaying,
                        onSelect = controller::play,
                        modifier = Modifier.weight(1f).fillMaxSize(),
                    )
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    WindowedVideoPlayer(
                        controller, deviceControls, state, errorMessage, onEnterFullscreen,
                        Modifier.fillMaxWidth().weight(1.6f),
                    )
                    VideoQueueList(
                        queue = state.queue,
                        currentIndex = state.currentIndex,
                        isPlaying = state.isPlaying,
                        onSelect = controller::play,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun WindowedVideoPlayer(
    controller: OpenListPlaybackController,
    deviceControls: PlaybackDeviceControls?,
    state: PlaybackControllerState,
    errorMessage: String?,
    onEnterFullscreen: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(OpenListMediaColors.canvas)) {
            VideoViewport(
                controller = controller,
                deviceControls = deviceControls,
                playWhenReady = state.playWhenReady,
                playbackState = state.playbackState,
                modifier = Modifier.fillMaxSize(),
            )
            if (state.playbackState == Player.STATE_BUFFERING) {
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            }
            FullscreenToggleButton(
                isFullscreen = false,
                onToggle = onEnterFullscreen,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = state.currentItem?.displayTitle.orEmpty(),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            errorMessage?.let { PlaybackError(it, onVideoSurface = false) }
            PlaybackControls(
                controller = controller,
                state = state,
                onVideoSurface = false,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullscreenVideoPlaybackContent(
    controller: OpenListPlaybackController,
    deviceControls: PlaybackDeviceControls?,
    state: PlaybackControllerState,
    errorMessage: String?,
    playbackInfoVisible: Boolean,
    onShowPlaybackInfo: () -> Unit,
    onExitFullscreen: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val scope = rememberCoroutineScope()
    val inPictureInPicture = rememberIsInPictureInPictureMode(activity)
    val player = controller.mediaController
    val canChangeSpeedNow = player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)
    var controlsVisible by remember { mutableStateOf(true) }
    var controlInteractionActive by remember { mutableStateOf(false) }
    var videoInteractionActive by remember { mutableStateOf(false) }
    var interactionEpoch by remember { mutableIntStateOf(0) }
    var videoScaleMode by remember(state.currentIndex) { mutableStateOf(VideoScaleMode.FIT) }
    // Locking hides every control and blocks touches on them; only the unlock affordance responds.
    var locked by rememberSaveable { mutableStateOf(false) }
    var lockControlsVisible by remember { mutableStateOf(true) }
    var showPlaylistSheet by rememberSaveable { mutableStateOf(false) }
    var showOptionsSheet by rememberSaveable { mutableStateOf(false) }
    var showAudioSheet by rememberSaveable { mutableStateOf(false) }
    var showSubtitleSheet by rememberSaveable { mutableStateOf(false) }
    var screenshotInProgress by remember { mutableStateOf(false) }
    val takeScreenshot = {
        val target = activity
        if (target != null && !screenshotInProgress) {
            screenshotInProgress = true
            // Hide the chrome so the saved frame is only the video, then let a frame draw.
            controlsVisible = false
            scope.launch {
                delay(180L)
                val result = captureAndSavePlaybackFrame(target)
                val message = result.fold(
                    onSuccess = { "已保存截图到$it" },
                    onFailure = { "截图失败：${it.message.orEmpty()}" },
                )
                Toast.makeText(target, message, Toast.LENGTH_SHORT).show()
                screenshotInProgress = false
            }
        }
    }
    val interactionActive =
        controlInteractionActive || videoInteractionActive || playbackInfoVisible ||
            showOptionsSheet || showPlaylistSheet || showAudioSheet || showSubtitleSheet
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
            deviceControls = deviceControls,
            playWhenReady = state.playWhenReady,
            playbackState = state.playbackState,
            onTap = {
                if (locked) {
                    // A tap while locked only reveals the unlock button; playback controls stay
                    // hidden and untouchable until the user unlocks.
                    lockControlsVisible = !lockControlsVisible
                } else {
                    currentToggleControls()
                }
            },
            scaleMode = videoScaleMode,
            gesturesEnabled = !locked && !inPictureInPicture,
            onInteractionActiveChange = currentVideoInteraction,
            modifier = Modifier.fillMaxSize(),
        )
        if (state.playbackState == Player.STATE_BUFFERING) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color.White)
        }

        AnimatedVisibility(
            visible = locked && lockControlsVisible && !inPictureInPicture,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            PlaybackChromeIconButton(
                icon = Icons.Default.Lock,
                contentDescription = "解锁屏幕",
                onClick = {
                    locked = false
                    controlsVisible = true
                    interactionEpoch += 1
                },
            )
        }

        AnimatedVisibility(
            visible = controlsVisible && !locked && !inPictureInPicture,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(Modifier.fillMaxSize()) {
                // Top-left: back to the previous level (exit fullscreen).
                PlaybackChromeIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "退出全屏",
                    onClick = onExitFullscreen,
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                )
                PlaybackChromeIconButton(
                    icon = Icons.Default.MoreVert,
                    contentDescription = "播放选项",
                    onClick = {
                        showOptionsSheet = true
                        interactionEpoch += 1
                    },
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                )
                // Bottom: title, scrubber + transport, and bottom-right actions.
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 20.dp, vertical = 10.dp)
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
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    errorMessage?.let { PlaybackError(it, onVideoSurface = true) }
                    state.currentItem?.let { current ->
                        Text(
                            text = current.displayTitle,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    FullscreenBottomBar(
                        controller = controller,
                        state = state,
                        onAudioTracks = {
                            interactionEpoch += 1
                            showAudioSheet = true
                        },
                        onSubtitles = {
                            interactionEpoch += 1
                            showSubtitleSheet = true
                        },
                        onPlaylist = {
                            interactionEpoch += 1
                            showPlaylistSheet = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    if (showOptionsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showOptionsSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                Text(
                    "播放选项",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("播放速度", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    IconButton(
                        enabled = canChangeSpeedNow,
                        onClick = {
                            if (player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) {
                                controller.setPlaybackSpeed(steppedPlaybackSpeed(player.playbackParameters.speed, false))
                            }
                        },
                    ) { Icon(Icons.Default.Remove, contentDescription = "减慢播放速度") }
                    Text(formatPlaybackSpeed(state.playbackSpeed), style = MaterialTheme.typography.titleMedium)
                    IconButton(
                        enabled = canChangeSpeedNow,
                        onClick = {
                            if (player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) {
                                controller.setPlaybackSpeed(steppedPlaybackSpeed(player.playbackParameters.speed, true))
                            }
                        },
                    ) { Icon(Icons.Default.Add, contentDescription = "加快播放速度") }
                }
                PlaybackOptionRow(
                    icon = if (videoScaleMode == VideoScaleMode.ZOOMED) Icons.Default.ZoomOut else Icons.Default.ZoomIn,
                    title = if (videoScaleMode == VideoScaleMode.ZOOMED) "恢复完整画面" else "放大画面",
                    onClick = {
                        videoScaleMode = toggleVideoScale(videoScaleMode).scaleMode
                        showOptionsSheet = false
                    },
                )
                if (activity != null && context.supportsPictureInPicture()) {
                    PlaybackOptionRow(Icons.Default.PictureInPictureAlt, "画中画") {
                        showOptionsSheet = false
                        activity.enterPlaybackPictureInPicture()
                    }
                }
                PlaybackOptionRow(Icons.Default.PhotoCamera, "截屏") {
                    showOptionsSheet = false
                    takeScreenshot()
                }
                PlaybackOptionRow(Icons.Default.LockOpen, "锁定屏幕") {
                    showOptionsSheet = false
                    locked = true
                    lockControlsVisible = true
                    controlsVisible = false
                    interactionEpoch += 1
                }
                PlaybackOptionRow(Icons.Default.MoreVert, "播放信息") {
                    showOptionsSheet = false
                    onShowPlaybackInfo()
                }
            }
        }
    }

    if (showPlaylistSheet) {
        ModalBottomSheet(
            onDismissRequest = { showPlaylistSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            VideoQueueList(
                queue = state.queue,
                currentIndex = state.currentIndex,
                isPlaying = state.isPlaying,
                onSelect = {
                    controller.play(it)
                    showPlaylistSheet = false
                },
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).fillMaxHeight(0.8f),
            )
        }
    }
    if (showAudioSheet) {
        TrackSelectionSheet(
            player = player,
            trackType = C.TRACK_TYPE_AUDIO,
            title = "音频轨道",
            allowDisable = false,
            onDismiss = { showAudioSheet = false },
        )
    }
    if (showSubtitleSheet) {
        TrackSelectionSheet(
            player = player,
            trackType = C.TRACK_TYPE_TEXT,
            title = "字幕",
            allowDisable = true,
            onDismiss = { showSubtitleSheet = false },
        )
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
    deviceControls: PlaybackDeviceControls?,
    playWhenReady: Boolean,
    @Player.State playbackState: Int,
    modifier: Modifier = Modifier,
    onTap: () -> Unit = {},
    gesturesEnabled: Boolean = true,
    scaleMode: VideoScaleMode = VideoScaleMode.FIT,
    onInteractionActiveChange: (Boolean) -> Unit = {},
) {
    val player = controller.mediaController
    val inPictureInPicture = rememberIsInPictureInPictureMode(LocalContext.current.findActivity())
    val canUseGestures = gesturesEnabled && !inPictureInPicture
    var speedBeforeBoost by remember(player) { mutableStateOf<Float?>(null) }
    var gestureSeekPositionMs by remember(player) { mutableStateOf<Long?>(null) }
    var adjustmentStartLevel by remember { mutableStateOf<Float?>(null) }
    var adjustmentFeedback by remember { mutableStateOf<VideoAdjustmentFeedback?>(null) }
    var adjustmentActive by remember { mutableStateOf(false) }
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
    LaunchedEffect(canUseGestures) {
        if (!canUseGestures) {
            restoreSpeed()
            adjustmentFeedback = null
            gestureSeekPositionMs = null
        }
    }
    LaunchedEffect(adjustmentActive, adjustmentFeedback) {
        if (!adjustmentActive && adjustmentFeedback != null) {
            delay(800L)
            adjustmentFeedback = null
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
            gesturesEnabled = canUseGestures,
            canSeek = { canUseGestures && player.canSeekWithCurrentCommands() },
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
                    !canUseGestures || speedBeforeBoost != null ||
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
            onVerticalAdjustmentStart = { target ->
                restoreSpeed()
                adjustmentActive = true
                adjustmentStartLevel = deviceControls?.currentLevel(target)
                adjustmentFeedback = VideoAdjustmentFeedback(target, adjustmentStartLevel)
            },
            onVerticalAdjustment = { target, delta ->
                adjustmentStartLevel?.let { start ->
                    val applied = deviceControls?.setLevel(target, (start + delta).coerceIn(0f, 1f))
                    adjustmentFeedback = VideoAdjustmentFeedback(target, applied)
                }
            },
            onVerticalAdjustmentEnd = {
                adjustmentStartLevel = null
                adjustmentActive = false
            },
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
        adjustmentFeedback?.takeIf { canUseGestures }?.let { feedback ->
            VideoAdjustmentIndicator(feedback, Modifier.align(Alignment.Center))
        }
    }
}

internal data class VideoAdjustmentFeedback(val target: VideoAdjustment, val level: Float?)

@Composable
internal fun VideoAdjustmentIndicator(feedback: VideoAdjustmentFeedback, modifier: Modifier = Modifier) {
    val brightness = feedback.target == VideoAdjustment.BRIGHTNESS
    val label = if (brightness) "亮度" else "音量"
    Surface(
        modifier = modifier.testTag(PlaybackUiTags.VIDEO_ADJUSTMENT_FEEDBACK),
        color = Color.Black.copy(alpha = 0.76f),
        contentColor = Color.White,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                if (brightness) Icons.Default.BrightnessMedium else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = feedback.level?.let { "$label ${(it * 100).roundToInt()}%" }
                    ?: "当前设备无法调节$label",
                style = MaterialTheme.typography.titleMedium,
            )
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
    gesturesEnabled: Boolean = true,
    onVerticalAdjustmentStart: (VideoAdjustment) -> Unit = {},
    onVerticalAdjustment: (VideoAdjustment, Float) -> Unit = { _, _ -> },
    onVerticalAdjustmentEnd: () -> Unit = {},
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
    val currentVerticalAdjustmentStart by rememberUpdatedState(onVerticalAdjustmentStart)
    val currentVerticalAdjustment by rememberUpdatedState(onVerticalAdjustment)
    val currentVerticalAdjustmentEnd by rememberUpdatedState(onVerticalAdjustmentEnd)
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
            .pointerInput(gesturesEnabled) {
                detectTapGestures(
                    onTap = { currentOnTap() },
                    onDoubleTap = { tapPosition ->
                        if (!gesturesEnabled || !currentCanSeek()) {
                            return@detectTapGestures
                        } else if (tapPosition.x < size.width / 2f) {
                            currentSeekBack()
                        } else {
                            currentSeekForward()
                        }
                    },
                    onLongPress = {
                        speedBoostActive = gesturesEnabled && currentSpeedBoostStart()
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
            .pointerInput(horizontalSeekPixelsPerSecond, gesturesEnabled) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!gesturesEnabled) return@awaitEachGesture
                    var overSlop = Offset.Zero
                    val dragStart = awaitTouchSlopOrCancellation(down.id) { change, over ->
                        change.consume()
                        overSlop = over
                    } ?: return@awaitEachGesture
                    val movement = dragStart.position - down.position
                    val vertical = abs(movement.y) > abs(movement.x)
                    // Keep both direction and side fixed for this pointer sequence, even if the
                    // finger later crosses the centre or moves diagonally.
                    val target = if (down.position.x < size.width / 2f) {
                        VideoAdjustment.BRIGHTNESS
                    } else {
                        VideoAdjustment.VOLUME
                    }
                    if (speedBoostActive) {
                        currentSpeedBoostEnd()
                        speedBoostActive = false
                    }
                    val duration = if (!vertical && currentCanSeek()) currentDurationMs() else null
                    val seekStart = if (duration != null) currentPositionMsProvider() else null
                    var accumulatedDrag = overSlop
                    var pendingSeek: Long? = null
                    var completed = false
                    var pointerId = dragStart.id
                    try {
                        if (vertical) currentVerticalAdjustmentStart(target)
                        while (true) {
                            if (vertical) {
                                currentVerticalAdjustment(
                                    target,
                                    -accumulatedDrag.y / (size.height.coerceAtLeast(1) * 0.75f),
                                )
                            } else if (seekStart != null) {
                                pendingSeek = horizontalSeekTarget(
                                    startPositionMs = seekStart,
                                    dragDeltaPx = accumulatedDrag.x,
                                    pixelsPerSecond = horizontalSeekPixelsPerSecond,
                                    durationMs = duration,
                                )
                                currentHorizontalSeekPreview(pendingSeek)
                            }
                            val change = awaitDragOrCancellation(pointerId) ?: break
                            if (!change.pressed) {
                                change.consume()
                                completed = true
                                break
                            }
                            accumulatedDrag += change.positionChange()
                            change.consume()
                            pointerId = change.id
                        }
                    } finally {
                        if (vertical) {
                            currentVerticalAdjustmentEnd()
                        } else {
                            currentHorizontalSeekPreview(null)
                        }
                    }
                    if (completed && !vertical && currentCanSeek()) {
                        pendingSeek?.let { currentHorizontalSeek(it) }
                    }
                }
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
            .size(48.dp)
            .background(OpenListMediaColors.controlScrim, CircleShape),
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
        modifier = modifier.size(48.dp).background(OpenListMediaColors.controlScrim, CircleShape),
    ) {
        Icon(
            imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
            contentDescription = if (isFullscreen) "退出全屏" else "进入全屏",
            tint = Color.White,
        )
    }
}

/** Circular, translucent chrome button shared across the fullscreen video overlay. */
@Composable
private fun PlaybackChromeIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(48.dp).background(OpenListMediaColors.controlScrim, CircleShape),
    ) {
        Icon(icon, contentDescription = contentDescription, tint = Color.White)
    }
}

@Composable
private fun PlaybackOptionRow(icon: ImageVector, title: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        leadingContent = { Icon(icon, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick).heightIn(min = 56.dp),
    )
}

/**
 * Track picker for a single [trackType], driven by Media3 [Player.getCurrentTracks] and applied via
 * [Player.setTrackSelectionParameters]. [allowDisable] adds an explicit "off" entry (subtitles).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackSelectionSheet(
    player: Player,
    trackType: Int,
    title: String,
    allowDisable: Boolean,
    onDismiss: () -> Unit,
) {
    // Re-read the (immutable) track snapshot after each change so the checkmarks stay accurate.
    var revision by remember { mutableStateOf(0) }
    val groups = remember(revision) {
        player.currentTracks.groups.filter { it.type == trackType }
    }
    val hasSelection = groups.any { group ->
        (0 until group.length).any { group.isTrackSelected(it) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Text(
                text = title,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                style = MaterialTheme.typography.titleMedium,
            )
            if (groups.isEmpty()) {
                Text(
                    text = "没有可选轨道",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (allowDisable) {
                TrackOptionRow(
                    label = "关闭",
                    selected = !hasSelection,
                    onClick = {
                        player.trackSelectionParameters = player.trackSelectionParameters
                            .buildUpon()
                            .clearOverridesOfType(trackType)
                            .setTrackTypeDisabled(trackType, true)
                            .build()
                        revision += 1
                    },
                )
            }
            groups.forEach { group ->
                for (index in 0 until group.length) {
                    val format = group.getTrackFormat(index)
                    TrackOptionRow(
                        label = trackLabel(format, index),
                        selected = group.isTrackSelected(index),
                        enabled = group.isTrackSupported(index),
                        onClick = {
                            player.trackSelectionParameters = player.trackSelectionParameters
                                .buildUpon()
                                .setTrackTypeDisabled(trackType, false)
                                .setOverrideForType(
                                    TrackSelectionOverride(group.mediaTrackGroup, index),
                                )
                                .build()
                            revision += 1
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = {
            if (selected) Icon(Icons.Default.Check, contentDescription = "已选择")
        },
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    )
}

private fun trackLabel(format: Format, index: Int): String =
    format.label?.takeIf { it.isNotBlank() }
        ?: format.language?.takeIf { it.isNotBlank() && it != "und" }
        ?: "轨道 ${index + 1}"

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
            text = "播放队列 · ${queue.size}",
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp).semantics { heading() },
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
                        Text(item.displayTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Text(
                            text = if (selected) {
                                if (isPlaying) "正在播放" else "当前项目"
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
                    modifier = Modifier.semantics { this.selected = selected }.clickable { onSelect(index) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioPlaybackContent(
    controller: OpenListPlaybackController,
    state: PlaybackControllerState,
    errorMessage: String?,
    modifier: Modifier,
) {
    val player = controller.mediaController
    val progress = rememberProgressStateWithTickInterval(player, PLAYBACK_PROGRESS_TICK_MS)
    val durationMs = playbackDurationMs(progress.durationMs, player.duration, player.contentDuration)
    val canSeekNow = player.canSeekWithCurrentCommands()
    val currentMediaId = state.currentItem?.mediaId
    var scrubPositionMs by remember(state.currentIndex, currentMediaId) { mutableStateOf<Long?>(null) }
    var pendingSeekPositionMs by remember(state.currentIndex, currentMediaId) {
        mutableStateOf<Long?>(null)
    }
    val displayedPositionMs = scrubPositionMs ?: pendingSeekPositionMs ?: progress.currentPositionMs

    LaunchedEffect(pendingSeekPositionMs, progress.currentPositionMs) {
        val pending = pendingSeekPositionMs ?: return@LaunchedEffect
        if (abs(progress.currentPositionMs - pending) <= PENDING_SEEK_CONFIRMATION_TOLERANCE_MS) {
            pendingSeekPositionMs = null
        }
    }
    LaunchedEffect(pendingSeekPositionMs) {
        val pending = pendingSeekPositionMs ?: return@LaunchedEffect
        delay(PENDING_SEEK_TIMEOUT_MS)
        if (pendingSeekPositionMs == pending) pendingSeekPositionMs = null
    }

    AudioPlaybackPresentation(
        state = state.copy(canSeek = canSeekNow),
        positionMs = displayedPositionMs,
        durationMs = durationMs,
        errorMessage = errorMessage,
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
        onToggle = {
            performPlaybackToggle(
                playWhenReady = player.playWhenReady,
                playbackState = player.playbackState,
                pause = controller::pause,
                seekToStart = { controller.seekTo(0L) },
                play = controller::play,
            )
        },
        onPrevious = controller::skipToPrevious,
        onNext = controller::skipToNext,
        onSelect = controller::play,
        modifier = modifier,
    )
}

/** Renders a session snapshot without connecting to or owning a player. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AudioPlaybackPresentation(
    state: PlaybackControllerState,
    positionMs: Long,
    durationMs: Long?,
    errorMessage: String?,
    onScrub: (Long) -> Unit,
    onSeek: (Long) -> Unit,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chrome = playbackChromePalette(MaterialTheme.colorScheme)
    var showPlaylistSheet by rememberSaveable { mutableStateOf(false) }
    val remainingMs = durationMs?.let { (it - positionMs).coerceAtLeast(0L) }

    BoxWithConstraints(modifier.background(chrome.pageContainer)) {
        val wide = maxWidth >= 840.dp && LocalDensity.current.fontScale < 1.5f
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Box(Modifier.weight(1.7f).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 600.dp)
                        .fillMaxSize()
                        .background(chrome.pageContainer)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .widthIn(max = 264.dp)
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(MaterialTheme.shapes.extraLarge)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            modifier = Modifier.size(112.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        if (state.playbackState == Player.STATE_BUFFERING) {
                            CircularProgressIndicator(Modifier.size(56.dp))
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            text = state.currentItem?.displayTitle.orEmpty(),
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.semantics { heading() },
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${state.currentIndex + 1} / ${state.queue.size} · " + when {
                                state.playbackState == Player.STATE_BUFFERING -> "正在缓冲"
                                state.isPlaying -> "正在播放"
                                state.playbackState == Player.STATE_ENDED -> "已播完"
                                state.playbackState == Player.STATE_IDLE -> "待播放"
                                else -> "已暂停"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    errorMessage?.let {
                        Spacer(Modifier.height(12.dp))
                        PlaybackError(it, onVideoSurface = false)
                    }
                    Spacer(Modifier.height(12.dp))
                    SeekablePlaybackSlider(
                        currentPositionMs = positionMs,
                        durationMs = durationMs,
                        enabled = state.canSeek,
                        mediaItemKey = state.currentItem?.mediaId,
                        onScrub = onScrub,
                        onSeek = onSeek,
                        onVideoSurface = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = formatDuration(positionMs),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            text = remainingMs?.let { "-${formatDuration(it)}" }
                                ?: formatDuration(durationMs ?: C.TIME_UNSET),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = onPrevious,
                            enabled = state.currentIndex > 0,
                            modifier = Modifier.size(56.dp),
                        ) {
                            Icon(
                                Icons.Default.SkipPrevious,
                                contentDescription = "上一首",
                                modifier = Modifier.size(36.dp),
                            )
                        }
                        FilledIconButton(
                            onClick = onToggle,
                            modifier = Modifier.size(76.dp),
                        ) {
                            Icon(
                                imageVector = when {
                                    playbackShowsPause(state.playWhenReady, state.playbackState) -> Icons.Default.Pause
                                    state.playbackState == Player.STATE_ENDED -> Icons.Default.Replay
                                    else -> Icons.Default.PlayArrow
                                },
                                contentDescription = when {
                                    playbackShowsPause(state.playWhenReady, state.playbackState) -> "暂停"
                                    state.playbackState == Player.STATE_ENDED -> "重新播放"
                                    else -> "播放"
                                },
                                modifier = Modifier.size(40.dp),
                            )
                        }
                        IconButton(
                            onClick = onNext,
                            enabled = state.currentIndex in 0 until state.queue.lastIndex,
                            modifier = Modifier.size(56.dp),
                        ) {
                            Icon(
                                Icons.Default.SkipNext,
                                contentDescription = "下一首",
                                modifier = Modifier.size(36.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (!wide) {
                        TextButton(onClick = { showPlaylistSheet = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null)
                            Text("播放队列 · ${state.queue.size}", modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
            if (wide) {
                AudioQueueList(
                    queue = state.queue,
                    currentIndex = state.currentIndex,
                    isPlaying = state.isPlaying,
                    onSelect = onSelect,
                    modifier = Modifier.weight(1f).fillMaxSize(),
                )
            }
        }
    }

    if (showPlaylistSheet) {
        ModalBottomSheet(
            onDismissRequest = { showPlaylistSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            AudioQueueList(
                queue = state.queue,
                currentIndex = state.currentIndex,
                isPlaying = state.isPlaying,
                onSelect = {
                    onSelect(it)
                    showPlaylistSheet = false
                },
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).fillMaxHeight(0.8f),
            )
        }
    }
}

@Composable
private fun AudioQueueList(
    queue: List<MediaItem>,
    currentIndex: Int,
    isPlaying: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    LazyColumn(modifier) {
        item {
            Text(
                text = "播放队列",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        itemsIndexed(
            items = queue,
            key = { index, item -> "${item.mediaId}:$index" },
        ) { index, item ->
            val selected = index == currentIndex
            ListItem(
                headlineContent = {
                    Text(item.displayTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                },
                supportingContent = {
                    Text(
                        text = if (selected) {
                            if (isPlaying) "正在播放" else "当前项目"
                        } else {
                            "第 ${index + 1} 项"
                        },
                        maxLines = 1,
                    )
                },
                leadingContent = {
                    Icon(
                        imageVector = if (selected && isPlaying) Icons.Default.PlayArrow else Icons.Default.MusicNote,
                        contentDescription = null,
                    )
                },
                colors = ListItemDefaults.colors(
                    containerColor = if (selected) {
                        colors.secondaryContainer
                    } else {
                        Color.Transparent
                    },
                    headlineColor = if (selected) colors.onSecondaryContainer else colors.onSurface,
                    supportingColor = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
                    leadingIconColor = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
                ),
                modifier = Modifier.semantics { this.selected = selected }.clickable { onSelect(index) },
            )
        }
    }
}

/**
 * Fullscreen bottom controls: row 1 is `elapsed / total` beside the scrubber; row 2 keeps queue
 * navigation and play/pause on the left and the audio/subtitle/playlist actions on the right.
 * Ten-second seek stays available via the double-tap gesture, so it is not repeated here.
 */
@Composable
private fun FullscreenBottomBar(
    controller: OpenListPlaybackController,
    state: PlaybackControllerState,
    onAudioTracks: () -> Unit,
    onSubtitles: () -> Unit,
    onPlaylist: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val player = controller.mediaController
    val progress = rememberProgressStateWithTickInterval(player, PLAYBACK_PROGRESS_TICK_MS)
    val durationMs = playbackDurationMs(progress.durationMs, player.duration, player.contentDuration)
    val canSeekNow = player.canSeekWithCurrentCommands()
    val currentMediaId = state.currentItem?.mediaId
    var scrubPositionMs by remember(state.currentIndex, currentMediaId) { mutableStateOf<Long?>(null) }
    var pendingSeekPositionMs by remember(state.currentIndex, currentMediaId) {
        mutableStateOf<Long?>(null)
    }
    val displayedPositionMs = scrubPositionMs ?: pendingSeekPositionMs ?: progress.currentPositionMs

    LaunchedEffect(pendingSeekPositionMs, progress.currentPositionMs) {
        val pending = pendingSeekPositionMs ?: return@LaunchedEffect
        if (abs(progress.currentPositionMs - pending) <= PENDING_SEEK_CONFIRMATION_TOLERANCE_MS) {
            pendingSeekPositionMs = null
        }
    }
    LaunchedEffect(pendingSeekPositionMs) {
        val pending = pendingSeekPositionMs ?: return@LaunchedEffect
        delay(PENDING_SEEK_TIMEOUT_MS)
        if (pendingSeekPositionMs == pending) pendingSeekPositionMs = null
    }

    val whiteButtonColors = IconButtonDefaults.iconButtonColors(
        contentColor = Color.White,
        disabledContentColor = Color.White.copy(alpha = 0.38f),
    )
    val playPauseColors = IconButtonDefaults.filledIconButtonColors(
        containerColor = Color.White,
        contentColor = Color.Black,
        disabledContainerColor = Color.White.copy(alpha = 0.12f),
        disabledContentColor = Color.White.copy(alpha = 0.38f),
    )

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Row 1: elapsed / total, then the scrubber fills the remaining width.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${formatDuration(displayedPositionMs)} / ${formatDuration(durationMs ?: C.TIME_UNSET)}",
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelMedium,
            )
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
                onVideoSurface = true,
                modifier = Modifier.weight(1f),
            )
        }
        // Row 2: transport on the left, track/playlist actions on the right.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = controller::skipToPrevious,
                    enabled = state.currentIndex > 0,
                    colors = whiteButtonColors,
                ) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "上一个")
                }
                FilledIconButton(
                    onClick = {
                        performPlaybackToggle(
                            playWhenReady = player.playWhenReady,
                            playbackState = player.playbackState,
                            pause = controller::pause,
                            seekToStart = { controller.seekTo(0L) },
                            play = controller::play,
                        )
                    },
                    colors = playPauseColors,
                ) {
                    Icon(
                        imageVector = when {
                            playbackShowsPause(state.playWhenReady, state.playbackState) -> Icons.Default.Pause
                            state.playbackState == Player.STATE_ENDED -> Icons.Default.Replay
                            else -> Icons.Default.PlayArrow
                        },
                        contentDescription = when {
                            playbackShowsPause(state.playWhenReady, state.playbackState) -> "暂停"
                            state.playbackState == Player.STATE_ENDED -> "重新播放"
                            else -> "播放"
                        },
                    )
                }
                IconButton(
                    onClick = controller::skipToNext,
                    enabled = state.currentIndex in 0 until state.queue.lastIndex,
                    colors = whiteButtonColors,
                ) {
                    Icon(Icons.Default.SkipNext, contentDescription = "下一个")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onAudioTracks, colors = whiteButtonColors) {
                    Icon(Icons.Default.Audiotrack, contentDescription = "音频轨道")
                }
                IconButton(onClick = onSubtitles, colors = whiteButtonColors) {
                    Icon(Icons.Default.Subtitles, contentDescription = "字幕")
                }
                IconButton(onClick = onPlaylist, colors = whiteButtonColors) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "播放列表")
                }
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

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = controller::skipToPrevious,
                enabled = state.currentIndex > 0,
                modifier = Modifier.size(48.dp),
                colors = transportButtonColors,
            ) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "上一个")
            }
            IconButton(
                onClick = { if (player.canSeekWithCurrentCommands()) controller.seekBack() },
                enabled = canSeekNow,
                modifier = Modifier.size(48.dp),
                colors = transportButtonColors,
            ) {
                Icon(Icons.Default.Replay10, contentDescription = "后退 10 秒")
            }
            FilledIconButton(
                onClick = {
                    performPlaybackToggle(
                        playWhenReady = player.playWhenReady,
                        playbackState = player.playbackState,
                        pause = controller::pause,
                        seekToStart = { controller.seekTo(0L) },
                        play = controller::play,
                    )
                },
                modifier = Modifier.size(56.dp),
                colors = playPauseButtonColors,
            ) {
                Icon(
                    imageVector = when {
                        playbackShowsPause(state.playWhenReady, state.playbackState) -> Icons.Default.Pause
                        state.playbackState == Player.STATE_ENDED -> Icons.Default.Replay
                        else -> Icons.Default.PlayArrow
                    },
                    contentDescription = when {
                        playbackShowsPause(state.playWhenReady, state.playbackState) -> "暂停"
                        state.playbackState == Player.STATE_ENDED -> "重新播放"
                        else -> "播放"
                    },
                    modifier = Modifier.size(32.dp),
                )
            }
            IconButton(
                onClick = { if (player.canSeekWithCurrentCommands()) controller.seekForward() },
                enabled = canSeekNow,
                modifier = Modifier.size(48.dp),
                colors = transportButtonColors,
            ) {
                Icon(Icons.Default.Forward10, contentDescription = "前进 10 秒")
            }
            IconButton(
                onClick = controller::skipToNext,
                enabled = state.currentIndex in 0 until state.queue.lastIndex,
                modifier = Modifier.size(48.dp),
                colors = transportButtonColors,
            ) {
                Icon(Icons.Default.SkipNext, contentDescription = "下一个")
            }
        }
        TextButton(
            onClick = {
                if (player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) {
                    controller.setPlaybackSpeed(nextPlaybackSpeed(player.playbackParameters.speed))
                }
            },
            enabled = canChangeSpeedNow,
            modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp)
                .testTag(PlaybackUiTags.PLAYBACK_SPEED),
        ) {
            Text("播放速度 ${formatPlaybackSpeed(state.playbackSpeed)}")
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

/** Moves one discrete step along [PLAYBACK_SPEED_STEPS] from the current rate, clamped at the ends. */
internal fun steppedPlaybackSpeed(currentSpeed: Float, increase: Boolean): Float {
    val steps = PLAYBACK_SPEED_STEPS
    val nearest = steps.indices.minByOrNull { abs(steps[it] - currentSpeed) } ?: return currentSpeed
    val target = (nearest + if (increase) 1 else -1).coerceIn(0, steps.lastIndex)
    return steps[target]
}

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
private fun PlaybackError(message: String, onVideoSurface: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
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

internal val MediaItem.isVideo: Boolean
    get() = mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_VIDEO ||
        localConfiguration?.mimeType?.startsWith("video/") == true

private val MediaItem.displayTitle: String
    get() = mediaMetadata.title?.toString()?.takeIf(String::isNotBlank)
        ?: mediaMetadata.displayTitle?.toString()?.takeIf(String::isNotBlank)
        ?: "未知媒体"

private fun formatDuration(valueMs: Long): String {
    val safeMs = valueMs.takeUnless { it == C.TIME_UNSET || it < 0L } ?: return "--:--"
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

/** Buffering still has an active play intent, so its primary action must be pause. */
internal fun playbackShowsPause(playWhenReady: Boolean, @Player.State playbackState: Int): Boolean =
    playWhenReady && (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING)

/** [play] must also prepare idle media, as [OpenListPlaybackController.play] does. */
internal fun performPlaybackToggle(
    playWhenReady: Boolean,
    @Player.State playbackState: Int,
    pause: () -> Unit,
    seekToStart: () -> Unit,
    play: () -> Unit,
) {
    if (playbackShowsPause(playWhenReady, playbackState)) {
        pause()
    } else {
        if (playbackState == Player.STATE_ENDED) seekToStart()
        play()
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
