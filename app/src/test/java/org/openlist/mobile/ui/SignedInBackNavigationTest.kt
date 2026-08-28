package org.openlist.mobile.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SignedInBackNavigationTest {
    @Test
    fun `files does not intercept back for a hidden settings detail`() {
        assertThat(
            signedInBackAction(
                isFilesDestination = true,
                isSettingsRoot = false,
            ),
        ).isEqualTo(SignedInBackAction.None)
    }

    @Test
    fun `visible account settings returns to settings root first`() {
        assertThat(
            signedInBackAction(
                isFilesDestination = false,
                isSettingsRoot = false,
            ),
        ).isEqualTo(SignedInBackAction.SettingsRoot)
    }

    @Test
    fun `settings root returns to files`() {
        assertThat(
            signedInBackAction(
                isFilesDestination = false,
                isSettingsRoot = true,
            ),
        ).isEqualTo(SignedInBackAction.Files)
    }
}
