package org.openlist.mobile.data.repository

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import org.junit.Test
import org.openlist.mobile.data.api.OpenListApiException

class LoginAuthenticationExceptionTest {
    @Test
    fun `OpenList 402 becomes a second factor challenge`() {
        val source = OpenListApiException(apiCode = 402, message = "Invalid 2FA code")

        val mapped = source.asLoginAuthenticationException()

        assertThat(mapped).isInstanceOf(SecondFactorRequiredException::class.java)
        assertThat(mapped.cause).isSameInstanceAs(source)
    }

    @Test
    fun `other authentication and network failures are preserved`() {
        val unauthorized = OpenListApiException(apiCode = 401, message = "invalid password")
        val network = IOException("offline")

        assertThat(unauthorized.asLoginAuthenticationException()).isSameInstanceAs(unauthorized)
        assertThat(network.asLoginAuthenticationException()).isSameInstanceAs(network)
    }
}
