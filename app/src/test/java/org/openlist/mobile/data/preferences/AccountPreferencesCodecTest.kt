package org.openlist.mobile.data.preferences

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.openlist.mobile.data.account.AccountId
import org.openlist.mobile.data.account.AccountStateMachine

class AccountPreferencesCodecTest {
    @Test
    fun `legacy single account is rewritten losslessly into an account slot`() {
        val preferences = mutablePreferencesOf(
            AccountPreferencesCodec.Keys.LEGACY_BASE_URL to "http://192.168.1.9:5244",
            AccountPreferencesCodec.Keys.LEGACY_USERNAME to "legacy-user",
            AccountPreferencesCodec.Keys.LEGACY_ALLOW_INSECURE_HTTP to true,
            AccountPreferencesCodec.Keys.LEGACY_TOKEN to "legacy-token",
        )

        val legacy = AccountPreferencesCodec.decode(preferences)
        assertThat(legacy.requiresMigrationWrite).isTrue()
        assertThat(legacy.state.active?.server?.baseUrl).isEqualTo("http://192.168.1.9:5244")
        assertThat(legacy.state.active?.server?.allowInsecureHttp).isTrue()
        assertThat(legacy.state.active?.token).isEqualTo("legacy-token")

        AccountPreferencesCodec.write(preferences, legacy.state)
        val migrated = AccountPreferencesCodec.decode(preferences)

        assertThat(migrated.requiresMigrationWrite).isFalse()
        assertThat(migrated.state.active?.server).isEqualTo(legacy.state.active?.server)
        assertThat(migrated.state.active?.token).isEqualTo("legacy-token")
        assertThat(preferences[AccountPreferencesCodec.Keys.LEGACY_TOKEN]).isNull()
    }

    @Test
    fun `active id round trip switches server and token as one state`() {
        val preferences = mutablePreferencesOf()
        val legacy = AccountPreferencesCodec.decode(preferences).state
        val firstId = AccountId("first")
        val secondId = AccountId("second")
        var state = AccountStateMachine.add(
            legacy,
            firstId,
            org.openlist.mobile.data.account.AccountDraft(
                server = org.openlist.mobile.core.model.ServerProfile("https://one.example", "alice"),
            ),
            makeActive = true,
        ).state
        state = AccountStateMachine.setToken(state, firstId, "first-token")
        state = AccountStateMachine.add(
            state,
            secondId,
            org.openlist.mobile.data.account.AccountDraft(
                server = org.openlist.mobile.core.model.ServerProfile("https://two.example", "bob"),
            ),
            makeActive = false,
        ).state
        state = AccountStateMachine.setToken(state, secondId, "second-token")
        state = AccountStateMachine.switch(state, secondId)

        AccountPreferencesCodec.write(preferences, state)
        val roundTripped = AccountPreferencesCodec.decode(preferences).state

        assertThat(roundTripped.activeId).isEqualTo(secondId)
        assertThat(roundTripped.active?.server?.username).isEqualTo("bob")
        assertThat(roundTripped.active?.token).isEqualTo("second-token")
        assertThat(roundTripped.records.first { it.id == firstId }.token).isEqualTo("first-token")
    }

    @Test
    fun `app settings string representation redacts compatibility token`() {
        val settings = AppSettings(token = "do-not-log")

        assertThat(settings.toString()).doesNotContain("do-not-log")
        assertThat(settings.toString()).contains("token=<redacted>")
    }
}
