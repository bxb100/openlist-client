package org.openlist.mobile.data.account

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.openlist.mobile.core.model.ServerProfile
import org.openlist.mobile.data.preferences.AccountPreferencesCodec

class SavedLoginCredentialTest {
    private val account = AccountId("saved")
    private val profile = ServerProfile("https://files.example.test/openlist", "alice")

    @Test
    fun `saved credentials survive serialization and logout for their own identity`() {
        val loggedOut = AccountStateMachine.setToken(savedState(), account, "")
        val preferences = mutablePreferencesOf()
        AccountPreferencesCodec.write(preferences, loggedOut)
        val restored = AccountPreferencesCodec.decode(preferences).state

        assertThat(restored.active?.token).isEmpty()
        assertThat(restored.active?.encryptedPasswordHash).isEmpty()
        assertThat(restored.active?.encryptedPassword).isEqualTo("encrypted-password")
        assertThat(restored.active?.server).isEqualTo(profile)
        assertThat(restored.toString()).doesNotContain("encrypted-password")
    }

    @Test
    fun `failed login and second factor attempts cannot replace a previously saved password`() {
        val pending = AccountStateMachine.beginLogin(
            savedState(), AccountId("unused"), profile, clearSavedPassword = false,
        )
        val failed = AccountStateMachine.failLogin(pending.state, pending.accountId, profile).state

        assertThat(failed.active?.token).isEmpty()
        assertThat(roundTrip(failed).active?.encryptedPassword).isEqualTo("encrypted-password")
    }

    @Test
    fun `unchecked login forgets only its target even when abandoned at OTP or rejected`() {
        val second = AccountId("second")
        val secondProfile = profile.copy(username = "bob")
        val added = AccountStateMachine.add(savedState(), second, AccountDraft(server = secondProfile), true).state
        val previous = AccountStateMachine.completeLogin(added, second, secondProfile, "other-token", "other-hash", "other-password").state
        val pending = AccountStateMachine.beginLogin(
            previous, AccountId("unused"), profile, clearSavedPassword = true,
        )
        val rejected = AccountStateMachine.failLogin(pending.state, pending.accountId, profile).state

        // A cancelled OTP step leaves the already-persisted begin-login state. A failed request
        // also passes through failLogin. Neither path may require successful authentication to
        // finish the user's opt-out.
        listOf(pending.state, rejected).forEach { state ->
            val restored = roundTrip(state)
            val target = requireNotNull(restored.active)
            assertThat(target.id).isEqualTo(account)
            assertThat(target.encryptedPassword).isEmpty()
            assertThat(target.encryptedPasswordHash).isEmpty()
            val untouched = restored.records.first { it.id == second }
            assertThat(untouched.token).isEqualTo("other-token")
            assertThat(untouched.encryptedPassword).isEqualTo("other-password")
            assertThat(untouched.encryptedPasswordHash).isEqualTo("other-hash")
        }
    }

    @Test
    fun `expired automatic login needing OTP keeps saved password for interactive sign in`() {
        val previous = savedState()
        val expired = AccountStateMachine.replaceAuthentication(
            previous, requireNotNull(previous.active), "", clearCredentials = true,
        )
        val restored = requireNotNull(roundTrip(expired.state).active)

        assertThat(expired.replaced).isTrue()
        assertThat(restored.token).isEmpty()
        assertThat(restored.sessionBindingKey).isEmpty()
        assertThat(restored.encryptedPasswordHash).isEmpty()
        assertThat(restored.encryptedPassword).isEqualTo("encrypted-password")
    }

    @Test
    fun `in flight renewal cannot restore credentials after user opts out`() {
        val previous = savedState()
        val requestSnapshot = requireNotNull(previous.active)
        val optedOut = AccountStateMachine.clearSavedLoginCredentials(previous, account, profile)
        val staleSuccess = AccountStateMachine.replaceAuthentication(optedOut, requestSnapshot, "renewed-token")
        val staleFailure = AccountStateMachine.replaceAuthentication(optedOut, requestSnapshot, "", clearCredentials = true)

        listOf(staleSuccess, staleFailure).forEach { replacement ->
            val restored = requireNotNull(roundTrip(replacement.state).active)
            assertThat(replacement.replaced).isFalse()
            assertThat(restored.token).isEqualTo("login-token")
            assertThat(restored.encryptedPassword).isEmpty()
            assertThat(restored.encryptedPasswordHash).isEmpty()
        }
    }

    @Test
    fun `successful renewal preserves saved password and transfer binding`() {
        val previous = savedState()
        val replacement = AccountStateMachine.replaceAuthentication(
            previous, requireNotNull(previous.active), "renewed-token",
        )
        val restored = requireNotNull(roundTrip(replacement.state).active)

        assertThat(replacement.replaced).isTrue()
        assertThat(restored.token).isEqualTo("renewed-token")
        assertThat(restored.sessionBindingKey).isEqualTo("login-token")
        assertThat(restored.encryptedPasswordHash).isEqualTo("encrypted-hash")
        assertThat(restored.encryptedPassword).isEqualTo("encrypted-password")
    }

    @Test
    fun `old renewal failure cannot invalidate a newer interactive login`() {
        val previous = savedState()
        val relogged = AccountStateMachine.completeLogin(
            previous, account, profile, "new-token", "new-hash", "new-password",
        ).state
        val staleFailure = AccountStateMachine.replaceAuthentication(
            relogged, requireNotNull(previous.active), "", clearCredentials = true,
        )

        assertThat(staleFailure.replaced).isFalse()
        assertThat(staleFailure.state.active?.token).isEqualTo("new-token")
        assertThat(staleFailure.state.active?.encryptedPassword).isEqualTo("new-password")
    }

    @Test
    fun `opting out removes both password and legacy hash without stopping current login`() {
        val previous = savedState()
        val cleared = AccountStateMachine.clearSavedLoginCredentials(previous, account, profile)

        assertThat(cleared.active?.encryptedPassword).isEmpty()
        assertThat(cleared.active?.encryptedPasswordHash).isEmpty()
        assertThat(cleared.active?.token).isEqualTo(previous.active?.token)
        assertThat(cleared.active?.sessionBindingKey).isEqualTo(previous.active?.sessionBindingKey)
    }

    @Test
    fun `unchecked successful login removes earlier saved credentials`() {
        val completed = AccountStateMachine.completeLogin(savedState(), account, profile, "new-token").state

        assertThat(completed.active?.token).isEqualTo("new-token")
        assertThat(completed.active?.encryptedPassword).isEmpty()
        assertThat(completed.active?.encryptedPasswordHash).isEmpty()
    }

    @Test
    fun `identity edits clear saved password but display name edits preserve it`() {
        val renamed = AccountStateMachine.edit(savedState(), account, AccountDraft("New name", profile))
        assertThat(renamed.active?.encryptedPassword).isEqualTo("encrypted-password")
        val edited = AccountStateMachine.edit(renamed, account, AccountDraft(server = profile.copy(username = "bob")))
        assertThat(edited.active?.encryptedPassword).isEmpty()
        assertThat(edited.active?.encryptedPasswordHash).isEmpty()
    }

    @Test
    fun `delayed opt out cannot clear a different identity stored in the same account slot`() {
        val changed = profile.copy(baseUrl = "https://another.example.test")
        val edited = AccountStateMachine.edit(savedState(), account, AccountDraft(server = changed))
        val relogged = AccountStateMachine.completeLogin(
            edited, account, changed, "other-token", "other-hash", "other-password",
        ).state
        val staleClear = AccountStateMachine.clearSavedLoginCredentials(relogged, account, profile)

        assertThat(staleClear.active?.encryptedPassword).isEqualTo("other-password")
        assertThat(staleClear.active?.encryptedPasswordHash).isEqualTo("other-hash")
    }

    @Test
    fun `deleting one account removes only its stored credential fields`() {
        val second = AccountId("second")
        val secondProfile = profile.copy(username = "bob")
        val added = AccountStateMachine.add(savedState(), second, AccountDraft(server = secondProfile), false).state
        val state = AccountStateMachine.completeLogin(added, second, secondProfile, "other", "hash-2", "password-2").state
        val preferences = mutablePreferencesOf()
        AccountPreferencesCodec.write(preferences, state)
        AccountPreferencesCodec.write(preferences, AccountStateMachine.delete(state, account))
        val restored = AccountPreferencesCodec.decode(preferences).state

        assertThat(preferences[AccountPreferencesCodec.Keys.encryptedPassword(account)]).isNull()
        assertThat(preferences[AccountPreferencesCodec.Keys.encryptedPasswordHash(account)]).isNull()
        assertThat(restored.active?.encryptedPassword).isEqualTo("password-2")
    }

    private fun savedState(): AccountState {
        val added = AccountStateMachine.add(AccountState(), account, AccountDraft(server = profile), true).state
        return AccountStateMachine.completeLogin(
            added, account, profile, "login-token", "encrypted-hash", "encrypted-password",
        ).state
    }

    private fun roundTrip(state: AccountState): AccountState {
        val preferences = mutablePreferencesOf()
        AccountPreferencesCodec.write(preferences, state)
        return AccountPreferencesCodec.decode(preferences).state
    }
}
