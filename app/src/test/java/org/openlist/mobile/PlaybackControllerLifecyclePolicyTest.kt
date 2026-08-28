package org.openlist.mobile

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackControllerLifecyclePolicyTest {
    private val signedIn = PlaybackSessionIdentity(
        baseUrl = "https://openlist.test",
        username = "user",
        authenticated = true,
    )

    private fun snapshot(
        identity: PlaybackSessionIdentity = signedIn,
        serviceRunning: Boolean,
    ) = PlaybackConnectionSnapshot(identity, serviceRunning)

    @Test
    fun `activity recreation attaches to the existing playback session`() {
        assertThat(
            playbackConnectionAction(
                previous = null,
                current = snapshot(serviceRunning = true),
            ),
        )
            .isEqualTo(PlaybackConnectionAction.ATTACH)
    }

    @Test
    fun `cold launch does not start an empty playback service`() {
        assertThat(
            playbackConnectionAction(
                previous = null,
                current = snapshot(serviceRunning = false),
            ),
        ).isEqualTo(PlaybackConnectionAction.NONE)
    }

    @Test
    fun `unchanged running service does not reconnect the controller`() {
        assertThat(
            playbackConnectionAction(
                previous = snapshot(serviceRunning = true),
                current = snapshot(serviceRunning = true),
            ),
        )
            .isEqualTo(PlaybackConnectionAction.NONE)
    }

    @Test
    fun `service becoming ready after activity recreation attaches`() {
        assertThat(
            playbackConnectionAction(
                previous = snapshot(serviceRunning = false),
                current = snapshot(serviceRunning = true),
            ),
        ).isEqualTo(PlaybackConnectionAction.ATTACH)
    }

    @Test
    fun `logout releases playback without reconnecting`() {
        val signedOut = signedIn.copy(authenticated = false)

        assertThat(
            playbackConnectionAction(
                previous = snapshot(serviceRunning = true),
                current = snapshot(identity = signedOut, serviceRunning = true),
            ),
        )
            .isEqualTo(PlaybackConnectionAction.RELEASE)
    }

    @Test
    fun `account switch releases playback until new media is requested`() {
        val nextAccount = signedIn.copy(
            baseUrl = "https://second-openlist.test",
            username = "other",
        )

        assertThat(
            playbackConnectionAction(
                previous = snapshot(serviceRunning = true),
                current = snapshot(identity = nextAccount, serviceRunning = true),
            ),
        ).isEqualTo(PlaybackConnectionAction.RELEASE)
    }
}
