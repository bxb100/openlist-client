@file:androidx.annotation.OptIn(
    markerClass = [androidx.media3.common.util.UnstableApi::class],
)

package org.openlist.mobile.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs

private const val PLAYBACK_INFO_REFRESH_MS = 500L

/**
 * Values shown in the diagnostics window. Deliberately excludes URLs, remote paths, request
 * headers and cache keys so they cannot accidentally be exposed by the UI.
 */
internal data class PlaybackInfoSnapshot(
    val title: String = "当前媒体",
    val networkBytesPerSecond: Long = 0L,
    val cacheBytesPerSecond: Long = 0L,
    val networkBytesRead: Long = 0L,
    val cacheBytesRead: Long = 0L,
    val hitRatio: Double? = null,
    val currentCacheStatus: PlaybackCacheStatus = PlaybackCacheStatus.WAITING,
    val sessionCacheStatus: PlaybackCacheStatus = PlaybackCacheStatus.WAITING,
    val playbackState: Int = Player.STATE_IDLE,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long? = null,
    val bufferedPositionMs: Long = 0L,
    val playbackSpeed: Float = 1f,
    val mediaSizeBytes: Long? = null,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val videoFrameRate: Float? = null,
    val videoCodec: String? = null,
    val videoBitrate: Int? = null,
    val audioCodec: String? = null,
    val audioChannels: Int? = null,
    val audioSampleRate: Int? = null,
)

@Composable
internal fun PlaybackInfoButton(
    onClick: () -> Unit,
    onVideoSurface: Boolean,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .testTag(PlaybackUiTags.PLAYBACK_INFO_BUTTON)
            .size(48.dp)
            .then(
                if (onVideoSurface) {
                    Modifier.background(Color.Black.copy(alpha = 0.62f), CircleShape)
                } else {
                    Modifier
                },
            ),
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = "播放信息",
            tint = if (onVideoSurface) Color.White else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Polling lives in the dialog composition, so closing the window cancels it immediately. */
@Composable
internal fun PlaybackInfoDialog(
    controller: OpenListPlaybackController,
    onDismiss: () -> Unit,
) {
    val player = controller.mediaController
    var snapshot by remember(controller) { mutableStateOf(PlaybackInfoSnapshot()) }

    LaunchedEffect(controller) {
        while (true) {
            runCatching { capturePlaybackInfo(player) }
                .onSuccess { snapshot = it }
            delay(PLAYBACK_INFO_REFRESH_MS)
        }
    }

    PlaybackInfoDialogContent(
        snapshot = snapshot,
        onDismiss = onDismiss,
    )
}

@Composable
internal fun PlaybackInfoDialogContent(
    snapshot: PlaybackInfoSnapshot,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 780.dp)
                    .fillMaxWidth()
                    .fillMaxHeight(0.92f)
                    .testTag(PlaybackUiTags.PLAYBACK_INFO_DIALOG)
                    .semantics { paneTitle = "播放信息" },
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
            ) {
                Column {
                    PlaybackInfoHeader(
                        title = snapshot.title,
                        onDismiss = onDismiss,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    PlaybackInfoBody(
                        snapshot = snapshot,
                        modifier = Modifier.weight(1f),
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text("关闭")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaybackInfoHeader(
    title: String,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "播放信息",
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = sanitizePlaybackInfoTitle(title),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(48.dp).testTag(PlaybackUiTags.PLAYBACK_INFO_CLOSE),
        ) {
            Icon(Icons.Default.Close, contentDescription = "关闭播放信息")
        }
    }
}

@Composable
private fun PlaybackInfoBody(
    snapshot: PlaybackInfoSnapshot,
    modifier: Modifier = Modifier,
) {
    val networkRows = playbackNetworkInfoRows(snapshot)
    val playbackRows = playbackStateInfoRows(snapshot)
    val videoRows = playbackVideoInfoRows(snapshot)
    val audioRows = playbackAudioInfoRows(snapshot)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        if (maxWidth >= 600.dp && LocalDensity.current.fontScale < 1.5f) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PlaybackInfoSection("网络与缓存", networkRows)
                    PlaybackInfoSection("视频", videoRows)
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PlaybackInfoSection("播放", playbackRows)
                    PlaybackInfoSection("音频", audioRows)
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PlaybackInfoSection("网络与缓存", networkRows)
                PlaybackInfoSection("播放", playbackRows)
                PlaybackInfoSection("视频", videoRows)
                PlaybackInfoSection("音频", audioRows)
            }
        }
    }
}

@Composable
private fun PlaybackInfoSection(
    title: String,
    rows: List<PlaybackInfoRow>,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = row.label,
                        modifier = Modifier.weight(0.42f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = row.value,
                        modifier = Modifier
                            .weight(0.58f)
                            .then(row.testTag?.let { Modifier.testTag(it) } ?: Modifier),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}

internal data class PlaybackInfoRow(
    val label: String,
    val value: String,
    val testTag: String? = null,
)

internal fun playbackNetworkInfoRows(snapshot: PlaybackInfoSnapshot): List<PlaybackInfoRow> = listOf(
    PlaybackInfoRow(
        label = "当前网速",
        value = formatByteRate(snapshot.networkBytesPerSecond),
        testTag = PlaybackUiTags.PLAYBACK_INFO_NETWORK_SPEED,
    ),
    PlaybackInfoRow("缓存读取速度", formatByteRate(snapshot.cacheBytesPerSecond)),
    PlaybackInfoRow("最近 2 秒数据源", playbackCacheStatusLabel(snapshot.currentCacheStatus)),
    PlaybackInfoRow(
        label = "缓存状态",
        value = playbackCacheStatusLabel(snapshot.sessionCacheStatus),
        testTag = PlaybackUiTags.PLAYBACK_INFO_CACHE_STATUS,
    ),
    PlaybackInfoRow("缓存命中率", formatHitRatio(snapshot.hitRatio)),
    PlaybackInfoRow("网络读取", formatBytes(snapshot.networkBytesRead)),
    PlaybackInfoRow("缓存读取", formatBytes(snapshot.cacheBytesRead)),
)

internal fun playbackStateInfoRows(snapshot: PlaybackInfoSnapshot): List<PlaybackInfoRow> {
    val duration = snapshot.durationMs
    val safePosition = snapshot.positionMs.coerceAtLeast(0L)
    val bufferedPosition = snapshot.bufferedPositionMs.coerceAtLeast(0L)
    val bufferedRemaining = (bufferedPosition - safePosition).coerceAtLeast(0L)
    return listOf(
        PlaybackInfoRow("播放状态", playbackStateLabel(snapshot.playbackState, snapshot.isPlaying)),
        PlaybackInfoRow("位置 / 时长", "${formatInfoDuration(safePosition)} / ${formatInfoDuration(duration)}"),
        PlaybackInfoRow("缓冲到", formatInfoDuration(bufferedPosition)),
        PlaybackInfoRow("剩余缓冲", formatInfoDuration(bufferedRemaining)),
        PlaybackInfoRow("媒体大小", formatBytes(snapshot.mediaSizeBytes)),
        PlaybackInfoRow("播放倍速", formatInfoPlaybackSpeed(snapshot.playbackSpeed)),
    )
}

internal fun playbackVideoInfoRows(snapshot: PlaybackInfoSnapshot): List<PlaybackInfoRow> = listOf(
    PlaybackInfoRow("分辨率", formatResolution(snapshot.videoWidth, snapshot.videoHeight)),
    PlaybackInfoRow("帧率", formatFrameRate(snapshot.videoFrameRate)),
    PlaybackInfoRow("视频编码", snapshot.videoCodec.orUnknown()),
    PlaybackInfoRow("视频码率", formatBitrate(snapshot.videoBitrate)),
)

internal fun playbackAudioInfoRows(snapshot: PlaybackInfoSnapshot): List<PlaybackInfoRow> = listOf(
    PlaybackInfoRow("音频编码", snapshot.audioCodec.orUnknown()),
    PlaybackInfoRow("声道", formatAudioChannels(snapshot.audioChannels)),
    PlaybackInfoRow("采样率", formatSampleRate(snapshot.audioSampleRate)),
)

private fun capturePlaybackInfo(player: Player): PlaybackInfoSnapshot {
    val mediaId = player.currentMediaItem?.mediaId
    val transfer = runCatching { PlaybackTransferDiagnostics.snapshot(mediaId) }.getOrNull()
    val videoFormat = player.selectedFormat(C.TRACK_TYPE_VIDEO)
    val audioFormat = player.selectedFormat(C.TRACK_TYPE_AUDIO)
    val videoSize = player.videoSize
    val displayTitle = player.mediaMetadata.title?.toString()
        ?: player.mediaMetadata.displayTitle?.toString()
        ?: "当前媒体"

    return PlaybackInfoSnapshot(
        title = sanitizePlaybackInfoTitle(displayTitle),
        networkBytesPerSecond = transfer?.networkBytesPerSecond ?: 0L,
        cacheBytesPerSecond = transfer?.cacheBytesPerSecond ?: 0L,
        networkBytesRead = transfer?.networkBytesRead ?: 0L,
        cacheBytesRead = transfer?.cacheBytesRead ?: 0L,
        hitRatio = transfer?.hitRatio,
        currentCacheStatus = transfer?.currentCacheStatus ?: PlaybackCacheStatus.WAITING,
        sessionCacheStatus = transfer?.sessionCacheStatus ?: PlaybackCacheStatus.WAITING,
        playbackState = player.playbackState,
        isPlaying = player.isPlaying,
        positionMs = player.currentPosition.coerceAtLeast(0L),
        durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0L },
        bufferedPositionMs = player.bufferedPosition.coerceAtLeast(0L),
        playbackSpeed = player.playbackParameters.speed,
        mediaSizeBytes = transfer?.knownSizeBytes,
        videoWidth = positiveOrNull(videoFormat?.width)
            ?: positiveOrNull(videoSize.width),
        videoHeight = positiveOrNull(videoFormat?.height)
            ?: positiveOrNull(videoSize.height),
        videoFrameRate = videoFormat?.frameRate?.takeIf { it.isFinite() && it > 0f },
        videoCodec = formatCodec(videoFormat),
        videoBitrate = formatBitrateValue(videoFormat),
        audioCodec = formatCodec(audioFormat),
        audioChannels = positiveOrNull(audioFormat?.channelCount),
        audioSampleRate = positiveOrNull(audioFormat?.sampleRate),
    )
}

private fun Player.selectedFormat(trackType: Int): Format? {
    currentTracks.groups.forEach { group ->
        if (group.type == trackType) {
            for (index in 0 until group.length) {
                if (group.isTrackSelected(index)) return group.getTrackFormat(index)
            }
        }
    }
    return null
}

private fun formatCodec(format: Format?): String? = format?.let {
    it.codecs
        ?.split(',')
        ?.firstOrNull()
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: it.sampleMimeType
            ?.substringAfter('/')
            ?.takeIf(String::isNotBlank)
}

private fun formatBitrateValue(format: Format?): Int? = format?.let {
    sequenceOf(it.averageBitrate, it.peakBitrate, it.bitrate)
        .firstOrNull { value -> value != Format.NO_VALUE && value > 0 }
}

private fun positiveOrNull(value: Int?): Int? = value?.takeIf { it > 0 && it != Format.NO_VALUE }

internal fun playbackCacheStatusLabel(status: PlaybackCacheStatus): String = when (status) {
    PlaybackCacheStatus.WAITING -> "等待"
    PlaybackCacheStatus.BYPASS -> "绕过"
    PlaybackCacheStatus.HIT -> "命中"
    PlaybackCacheStatus.PARTIAL -> "部分命中"
    PlaybackCacheStatus.MISS -> "未命中"
}

internal fun playbackStateLabel(@Player.State state: Int, isPlaying: Boolean): String = when (state) {
    Player.STATE_BUFFERING -> "缓冲中"
    Player.STATE_READY -> if (isPlaying) "播放中" else "已暂停"
    Player.STATE_ENDED -> "已结束"
    else -> "空闲"
}

internal fun formatBytes(value: Long?): String {
    val bytes = value?.takeIf { it >= 0L } ?: return "未知"
    if (bytes < 1_024L) return "$bytes B"
    val units = arrayOf("KiB", "MiB", "GiB", "TiB", "PiB", "EiB")
    var scaled = bytes.toDouble()
    var unitIndex = -1
    while (scaled >= 1_024.0 && unitIndex < units.lastIndex) {
        scaled /= 1_024.0
        unitIndex += 1
    }
    return String.format(Locale.ROOT, "%.2f %s", scaled, units[unitIndex])
}

internal fun formatByteRate(bytesPerSecond: Long): String =
    if (bytesPerSecond < 0L) "未知" else "${formatBytes(bytesPerSecond)}/s"

internal fun formatHitRatio(ratio: Double?): String {
    val safeRatio = ratio?.takeIf(Double::isFinite) ?: return "等待"
    return String.format(Locale.ROOT, "%.1f%%", safeRatio.coerceIn(0.0, 1.0) * 100.0)
}

internal fun formatInfoDuration(valueMs: Long?): String {
    val safeMs = valueMs?.takeIf { it != C.TIME_UNSET && it >= 0L } ?: return "未知"
    val totalSeconds = safeMs / 1_000L
    val seconds = totalSeconds % 60L
    val totalMinutes = totalSeconds / 60L
    val minutes = totalMinutes % 60L
    val hours = totalMinutes / 60L
    return if (hours > 0L) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

internal fun formatResolution(width: Int?, height: Int?): String =
    if (width != null && width > 0 && height != null && height > 0) "${width} × $height" else "未知"

internal fun formatFrameRate(frameRate: Float?): String {
    val safeRate = frameRate?.takeIf { it.isFinite() && it > 0f } ?: return "未知"
    val rounded = safeRate.toInt()
    return if (abs(safeRate - rounded) < 0.01f) "$rounded fps" else String.format(Locale.ROOT, "%.2f fps", safeRate)
}

internal fun formatBitrate(bitsPerSecond: Int?): String {
    val bitrate = bitsPerSecond?.takeIf { it > 0 } ?: return "未知"
    return when {
        bitrate >= 1_000_000 -> String.format(Locale.ROOT, "%.2f Mbps", bitrate / 1_000_000.0)
        bitrate >= 1_000 -> String.format(Locale.ROOT, "%.0f Kbps", bitrate / 1_000.0)
        else -> "$bitrate bps"
    }
}

internal fun formatAudioChannels(channelCount: Int?): String = when (channelCount) {
    1 -> "单声道"
    2 -> "立体声"
    null -> "未知"
    else -> if (channelCount > 0) "$channelCount 声道" else "未知"
}

internal fun formatSampleRate(sampleRate: Int?): String =
    sampleRate?.takeIf { it > 0 }?.let { String.format(Locale.ROOT, "%.1f kHz", it / 1_000.0) }
        ?: "未知"

internal fun formatInfoPlaybackSpeed(speed: Float): String {
    if (!speed.isFinite() || speed <= 0f) return "未知"
    val rounded = speed.toInt()
    return if (abs(speed - rounded) < 0.01f) {
        "$rounded×"
    } else {
        "${String.format(Locale.ROOT, "%.2f", speed).trimEnd('0').trimEnd('.')}×"
    }
}

internal fun sanitizePlaybackInfoTitle(rawTitle: String): String {
    val withoutQuery = rawTitle.substringBefore('?').substringBefore('#')
    return withoutQuery
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .trim()
        .takeIf(String::isNotBlank)
        ?: "当前媒体"
}

private fun String?.orUnknown(): String = this?.takeIf(String::isNotBlank) ?: "未知"
