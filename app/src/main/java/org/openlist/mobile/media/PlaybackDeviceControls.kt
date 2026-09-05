package org.openlist.mobile.media

import android.app.Activity
import android.media.AudioManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlin.math.roundToInt

internal enum class VideoAdjustment { BRIGHTNESS, VOLUME }

@Composable
internal fun rememberPlaybackDeviceControls(
    activity: Activity?,
    enabled: Boolean,
): PlaybackDeviceControls? {
    val controls = remember(activity) {
        activity?.let { PlaybackDeviceControls(AndroidPlaybackDeviceSettings(it)) }
    }
    DisposableEffect(controls, enabled) {
        controls?.setEnabled(enabled)
        onDispose { controls?.setEnabled(false) }
    }
    return controls.takeIf { enabled }
}

internal class PlaybackDeviceControls(private val settings: PlaybackDeviceSettings) {
    private var enabled = false
    private var originalBrightness: Float? = null
    private var brightnessChanged = false

    internal fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        if (value) {
            originalBrightness = deviceValue { settings.windowBrightness.takeIf(Float::isFinite) }
        } else {
            if (brightnessChanged) {
                originalBrightness?.let { original ->
                    deviceValue { settings.windowBrightness = original }
                }
            }
            originalBrightness = null
            brightnessChanged = false
        }
    }

    fun currentLevel(target: VideoAdjustment): Float? {
        if (!enabled) return null
        return deviceValue {
            when (target) {
                VideoAdjustment.BRIGHTNESS -> readBrightness()
                VideoAdjustment.VOLUME -> {
                    val maximum = adjustableMusicMaximum() ?: return@deviceValue null
                    settings.musicVolume.coerceIn(0, maximum).toFloat() / maximum
                }
            }
        }
    }

    fun setLevel(target: VideoAdjustment, level: Float): Float? {
        if (!enabled || !level.isFinite()) return null
        return deviceValue {
            when (target) {
                VideoAdjustment.BRIGHTNESS -> {
                    // Do not install an override unless the previous window policy can be restored.
                    if (originalBrightness == null) return@deviceValue null
                    brightnessChanged = true
                    settings.windowBrightness = level.coerceIn(MINIMUM_BRIGHTNESS, 1f)
                    readBrightness()
                }
                VideoAdjustment.VOLUME -> {
                    val maximum = adjustableMusicMaximum() ?: return@deviceValue null
                    val requested = (level.coerceIn(0f, 1f) * maximum).roundToInt()
                    if (requested != settings.musicVolume) settings.musicVolume = requested
                    // Android may clamp a request for hearing protection or the active audio route.
                    settings.musicVolume.coerceIn(0, maximum).toFloat() / maximum
                }
            }
        }
    }

    private fun readBrightness(): Float? {
        if (originalBrightness == null) return null
        val windowOverride = settings.windowBrightness
        val value = windowOverride.takeIf { it.isFinite() && it >= 0f } ?: settings.systemBrightness
        return value?.takeIf(Float::isFinite)?.coerceIn(MINIMUM_BRIGHTNESS, 1f)
    }

    private fun adjustableMusicMaximum(): Int? =
        if (settings.volumeFixed) null else settings.musicMaxVolume.takeIf { it > 0 }

    private companion object {
        const val MINIMUM_BRIGHTNESS = 0.05f
    }
}

internal interface PlaybackDeviceSettings {
    var windowBrightness: Float
    val systemBrightness: Float?
    val volumeFixed: Boolean
    val musicMaxVolume: Int
    var musicVolume: Int
}

private class AndroidPlaybackDeviceSettings(private val activity: Activity) : PlaybackDeviceSettings {
    private val audioManager = activity.applicationContext.getSystemService(AudioManager::class.java)

    override var windowBrightness: Float
        get() = activity.window.attributes.screenBrightness
        set(value) {
            activity.window.attributes = activity.window.attributes.apply { screenBrightness = value }
        }

    override val systemBrightness: Float?
        get() = Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
            .takeIf { it >= 0 }?.coerceAtMost(255)?.div(255f)

    override val volumeFixed: Boolean
        get() = audioManager?.isVolumeFixed ?: true

    override val musicMaxVolume: Int
        get() = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0

    override var musicVolume: Int
        get() = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
        set(value) {
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
        }
}

private inline fun <T> deviceValue(block: () -> T): T? = try {
    block()
} catch (_: RuntimeException) {
    // Audio policy, unavailable services, and an exiting activity can reject a gesture mid-drag.
    null
}
