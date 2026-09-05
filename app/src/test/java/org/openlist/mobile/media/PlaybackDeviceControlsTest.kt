package org.openlist.mobile.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackDeviceControlsTest {
    @Test
    fun `brightness starts at the system level and restores automatic window policy`() {
        val device = FakeDeviceSettings(systemBrightness = 0.7f)
        val controls = PlaybackDeviceControls(device)
        controls.setEnabled(true)

        assertEquals(0.7f, controls.currentLevel(VideoAdjustment.BRIGHTNESS))
        assertEquals(0.9f, controls.setLevel(VideoAdjustment.BRIGHTNESS, 0.9f))
        controls.setEnabled(false)

        assertEquals(-1f, device.windowBrightness, 0f)
        assertNull(controls.currentLevel(VideoAdjustment.BRIGHTNESS))
        assertNull(controls.setLevel(VideoAdjustment.VOLUME, 1f))
    }

    @Test
    fun `existing window override survives repeated activation and restores on exit`() {
        val device = FakeDeviceSettings(windowBrightness = 0.3f, systemBrightness = 0.8f)
        val controls = PlaybackDeviceControls(device)
        controls.setEnabled(true)
        assertEquals(0.3f, controls.currentLevel(VideoAdjustment.BRIGHTNESS))
        controls.setLevel(VideoAdjustment.BRIGHTNESS, 0.6f)

        controls.setEnabled(true)
        assertEquals(0.6f, controls.currentLevel(VideoAdjustment.BRIGHTNESS))
        controls.setEnabled(false)

        assertEquals(0.3f, device.windowBrightness, 0f)
        device.windowBrightness = 0.4f
        controls.setEnabled(true)
        controls.setLevel(VideoAdjustment.BRIGHTNESS, 1f)
        controls.setEnabled(false)
        assertEquals(0.4f, device.windowBrightness, 0f)
    }

    @Test
    fun `brightness keeps the screen visible at its lower boundary`() {
        val device = FakeDeviceSettings()
        val controls = PlaybackDeviceControls(device)
        controls.setEnabled(true)

        assertEquals(0.05f, controls.setLevel(VideoAdjustment.BRIGHTNESS, -0.4f))
        assertEquals(1f, controls.setLevel(VideoAdjustment.BRIGHTNESS, 1.5f))
        assertNull(controls.setLevel(VideoAdjustment.BRIGHTNESS, Float.NaN))
        assertEquals(1f, device.windowBrightness, 0f)
    }

    @Test
    fun `accumulating absolute gesture levels eventually advances discrete volume`() {
        val device = FakeDeviceSettings(musicVolume = 5, musicMaxVolume = 10)
        val controls = PlaybackDeviceControls(device)
        controls.setEnabled(true)

        assertEquals(0.5f, controls.setLevel(VideoAdjustment.VOLUME, 0.52f))
        assertEquals(0.5f, controls.setLevel(VideoAdjustment.VOLUME, 0.54f))
        assertEquals(0.6f, controls.setLevel(VideoAdjustment.VOLUME, 0.56f))
        assertEquals(1, device.volumeWrites)
        controls.setEnabled(false)
        assertEquals(6, device.musicVolume)
    }

    @Test
    fun `feedback reflects the volume Android allows instead of the requested level`() {
        val device = FakeDeviceSettings(musicVolume = 5, musicMaxVolume = 10)
        device.allowedMaximum = 7
        val controls = PlaybackDeviceControls(device)
        controls.setEnabled(true)

        assertEquals(0.7f, controls.setLevel(VideoAdjustment.VOLUME, 1f))
        assertEquals(7, device.musicVolume)
    }

    @Test
    fun `fixed volume routes are unavailable without attempting a write`() {
        val device = FakeDeviceSettings(volumeFixed = true)
        val controls = PlaybackDeviceControls(device)
        controls.setEnabled(true)

        assertNull(controls.currentLevel(VideoAdjustment.VOLUME))
        assertNull(controls.setLevel(VideoAdjustment.VOLUME, 1f))
        assertEquals(0, device.volumeWrites)
    }

    @Test
    fun `system volume rejection is unavailable rather than a playback crash`() {
        val device = FakeDeviceSettings()
        device.rejectVolumeWrites = true
        val controls = PlaybackDeviceControls(device)
        controls.setEnabled(true)

        assertNull(controls.setLevel(VideoAdjustment.VOLUME, 1f))
        assertEquals(5, device.musicVolume)
        assertEquals(0.5f, controls.currentLevel(VideoAdjustment.VOLUME))
    }

    private class FakeDeviceSettings(
        override var windowBrightness: Float = -1f,
        override val systemBrightness: Float? = 0.5f,
        override val volumeFixed: Boolean = false,
        override val musicMaxVolume: Int = 10,
        musicVolume: Int = 5,
    ) : PlaybackDeviceSettings {
        var volumeWrites = 0
        var allowedMaximum = musicMaxVolume
        var rejectVolumeWrites = false
        override var musicVolume = musicVolume
            set(value) {
                if (rejectVolumeWrites) throw SecurityException("Volume adjustment denied")
                volumeWrites += 1
                field = value.coerceAtMost(allowedMaximum)
            }
    }
}
