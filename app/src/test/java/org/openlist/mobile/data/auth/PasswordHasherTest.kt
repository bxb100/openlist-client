package org.openlist.mobile.data.auth

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PasswordHasherTest {
    @Test
    fun `hash matches OpenList static hash algorithm`() {
        assertThat(PasswordHasher.forOpenList("password"))
            .isEqualTo("0ee0be47182acad90a4307dd35cc06d901875e870b2637955a1188637ee56675")
    }

    @Test
    fun `hash is lowercase and deterministic`() {
        val first = PasswordHasher.forOpenList("密 码")

        assertThat(first).hasLength(64)
        assertThat(first).matches("[0-9a-f]{64}")
        assertThat(PasswordHasher.forOpenList("密 码")).isEqualTo(first)
    }
}
