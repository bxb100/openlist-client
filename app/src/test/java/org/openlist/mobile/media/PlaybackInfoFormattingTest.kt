package org.openlist.mobile.media

import androidx.media3.common.C
import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackInfoFormattingTest {
    @Test
    fun byteFormattingHandlesUnknownZeroFractionsAndLongMax() {
        assertThat(formatBytes(null)).isEqualTo("未知")
        assertThat(formatBytes(-1L)).isEqualTo("未知")
        assertThat(formatBytes(0L)).isEqualTo("0 B")
        assertThat(formatBytes(1_536L)).isEqualTo("1.50 KiB")
        assertThat(formatBytes(Long.MAX_VALUE)).isEqualTo("8.00 EiB")
        assertThat(formatByteRate(1_048_576L)).isEqualTo("1.00 MiB/s")
    }

    @Test
    fun ratiosAndDurationsClampInvalidBoundaries() {
        assertThat(formatHitRatio(null)).isEqualTo("等待")
        assertThat(formatHitRatio(Double.NaN)).isEqualTo("等待")
        assertThat(formatHitRatio(-0.5)).isEqualTo("0.0%")
        assertThat(formatHitRatio(0.375)).isEqualTo("37.5%")
        assertThat(formatHitRatio(1.5)).isEqualTo("100.0%")
        assertThat(formatInfoDuration(C.TIME_UNSET)).isEqualTo("未知")
        assertThat(formatInfoDuration(3_661_000L)).isEqualTo("1:01:01")
    }

    @Test
    fun mediaFormattingUsesReadableFallbacks() {
        assertThat(formatResolution(3_840, 2_160)).isEqualTo("3840 × 2160")
        assertThat(formatResolution(-1, 2_160)).isEqualTo("未知")
        assertThat(formatFrameRate(23.976f)).isEqualTo("23.98 fps")
        assertThat(formatBitrate(12_500_000)).isEqualTo("12.50 Mbps")
        assertThat(formatAudioChannels(1)).isEqualTo("单声道")
        assertThat(formatAudioChannels(2)).isEqualTo("立体声")
        assertThat(formatAudioChannels(6)).isEqualTo("6 声道")
        assertThat(formatSampleRate(48_000)).isEqualTo("48.0 kHz")
        assertThat(formatInfoPlaybackSpeed(1.25f)).isEqualTo("1.25×")
    }

    @Test
    fun statusLabelsCoverEveryPlaybackAndCacheState() {
        assertThat(playbackCacheStatusLabel(PlaybackCacheStatus.WAITING)).isEqualTo("等待")
        assertThat(playbackCacheStatusLabel(PlaybackCacheStatus.BYPASS)).isEqualTo("绕过")
        assertThat(playbackCacheStatusLabel(PlaybackCacheStatus.HIT)).isEqualTo("命中")
        assertThat(playbackCacheStatusLabel(PlaybackCacheStatus.PARTIAL)).isEqualTo("部分命中")
        assertThat(playbackCacheStatusLabel(PlaybackCacheStatus.MISS)).isEqualTo("未命中")
        assertThat(playbackStateLabel(Player.STATE_BUFFERING, false)).isEqualTo("缓冲中")
        assertThat(playbackStateLabel(Player.STATE_READY, true)).isEqualTo("播放中")
        assertThat(playbackStateLabel(Player.STATE_READY, false)).isEqualTo("已暂停")
        assertThat(playbackStateLabel(Player.STATE_ENDED, false)).isEqualTo("已结束")
    }

    @Test
    fun titleSanitizationDoesNotExposeRemoteLocationOrQuerySecrets() {
        val raw = "https://example.invalid/private/folder/movie.mp4?token=top-secret#fragment"

        val visible = sanitizePlaybackInfoTitle(raw)

        assertThat(visible).isEqualTo("movie.mp4")
        assertThat(visible).doesNotContain("https://")
        assertThat(visible).doesNotContain("private/folder")
        assertThat(visible).doesNotContain("token")
        assertThat(visible).doesNotContain("top-secret")
    }
}
