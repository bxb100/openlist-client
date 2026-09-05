@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package org.openlist.mobile.ui.viewer

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.android.tools.screenshot.PreviewTest
import org.openlist.mobile.media.AudioPlaybackPresentation
import org.openlist.mobile.media.PlaybackControllerState
import org.openlist.mobile.media.VideoQueueList
import org.openlist.mobile.media.VideoAdjustment
import org.openlist.mobile.media.VideoAdjustmentFeedback
import org.openlist.mobile.media.VideoAdjustmentIndicator
import org.openlist.mobile.ui.theme.OpenListTheme

@PreviewTest
@Preview(name = "Video adjustment feedback", widthDp = 360, heightDp = 300)
@Preview(name = "Video adjustment large text", widthDp = 320, heightDp = 460, fontScale = 2f)
@Composable
fun VideoAdjustmentScreenshot() {
    OpenListTheme {
        Surface(color = Color(0xFF161B24)) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            ) {
                VideoAdjustmentIndicator(VideoAdjustmentFeedback(VideoAdjustment.BRIGHTNESS, 0.76f))
                VideoAdjustmentIndicator(VideoAdjustmentFeedback(VideoAdjustment.VOLUME, 0.53f))
                VideoAdjustmentIndicator(VideoAdjustmentFeedback(VideoAdjustment.VOLUME, null))
            }
        }
    }
}

@PreviewTest
@Preview(name = "Now playing · Compact", widthDp = 360)
@Preview(name = "Now playing · Dark", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Now playing · Large text", widthDp = 320, fontScale = 2f)
@Composable
fun NowPlayingScreenshot() {
    OpenListTheme {
        NowPlayingBar(
            title = "旅行记录_杭州西湖_完整版本_第一部分.mp4",
            isPlaying = true,
            isVideo = true,
            onOpen = {},
            onToggle = {},
        )
    }
}

@PreviewTest
@Preview(name = "Audio · Compact", widthDp = 360, heightDp = 800)
@Preview(name = "Audio · Dark", widthDp = 360, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Audio · Large text", widthDp = 320, heightDp = 1100, fontScale = 2f)
@Preview(name = "Audio · Expanded", widthDp = 1000, heightDp = 800)
@Composable
fun AudioPlaybackScreenshot() {
    OpenListTheme { AudioPlaybackSample() }
}

@PreviewTest
@Preview(name = "Audio · Dynamic light", widthDp = 360, heightDp = 800)
@Preview(name = "Audio · Dynamic dark", widthDp = 360, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun AudioPlaybackDynamicColorScreenshot() {
    OpenListTheme(dynamicColor = true) { AudioPlaybackSample() }
}

@Composable
private fun AudioPlaybackSample() {
    val items = listOf("夏日旅途 · 01 海边的清晨.flac", "02 晚风与日落.flac", "03 回家的路.flac")
        .mapIndexed { index, title ->
            MediaItem.Builder().setMediaId("preview-$index")
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build()).build()
        }
    Surface {
        AudioPlaybackPresentation(
            state = PlaybackControllerState(
                queue = items,
                currentIndex = 0,
                isPlaying = true,
                playWhenReady = true,
                playbackState = Player.STATE_READY,
                repeatMode = Player.REPEAT_MODE_OFF,
                shuffleEnabled = false,
                playbackSpeed = 1f,
                canSeek = true,
                canChangeSpeed = true,
            ),
            positionMs = 94_000L,
            durationMs = 276_000L,
            errorMessage = null,
            onScrub = {},
            onSeek = {},
            onToggle = {},
            onPrevious = {},
            onNext = {},
            onSelect = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@PreviewTest
@Preview(name = "Video queue · Light", widthDp = 360, heightDp = 640)
@Preview(name = "Video queue · Dark", widthDp = 360, heightDp = 640, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Video queue · Large text", widthDp = 320, heightDp = 640, fontScale = 2f)
@Composable
fun VideoQueueScreenshot() {
    OpenListTheme { VideoQueueSample() }
}

@PreviewTest
@Preview(name = "Video queue · Dynamic light", widthDp = 360, heightDp = 640)
@Preview(name = "Video queue · Dynamic dark", widthDp = 360, heightDp = 640, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun VideoQueueDynamicColorScreenshot() {
    OpenListTheme(dynamicColor = true) { VideoQueueSample() }
}

@Composable
private fun VideoQueueSample() {
    VideoQueueList(
        queue = listOf("旅行记录_杭州西湖_完整版本_第一部分.mp4", "旅行记录_上海_第二部分.mp4")
            .mapIndexed { index, title ->
                MediaItem.Builder().setMediaId("preview-$index")
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build()).build()
            },
        currentIndex = 0,
        isPlaying = true,
        onSelect = {},
        modifier = Modifier.fillMaxSize(),
    )
}
