package org.openlist.mobile.core.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LocalNetworkAddressTest {
    @Test
    fun `detects common local addresses`() {
        listOf(
            "http://192.168.1.3:5244",
            "https://10.0.0.1/openlist",
            "http://172.31.255.1",
            "https://nas.local",
            "http://localhost:5244",
            "http://homeserver",
            "http://[fd00::2]:5244",
        ).forEach { assertThat(LocalNetworkAddress.isLikelyLocal(it)).isTrue() }
    }

    @Test
    fun `does not classify public addresses as local`() {
        listOf(
            "https://files.example.com",
            "https://8.8.8.8",
            "https://172.32.0.1",
            "https://192.0.2.10",
        ).forEach { assertThat(LocalNetworkAddress.isLikelyLocal(it)).isFalse() }
    }
}
