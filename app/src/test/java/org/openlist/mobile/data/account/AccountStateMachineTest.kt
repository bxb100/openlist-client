package org.openlist.mobile.data.account

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.openlist.mobile.core.model.ServerProfile

class AccountStateMachineTest {
    @Test
    fun `legacy account migration preserves identity transport policy and token`() {
        val legacy = LegacyAccount(
            server = ServerProfile(
                baseUrl = "http://192.168.1.8:5244",
                username = "legacy-user",
                allowInsecureHttp = true,
            ),
            token = "legacy-token",
        )

        val migrated = AccountStateMachine.migrateLegacy(legacy)

        assertThat(migrated.records).hasSize(1)
        assertThat(migrated.active?.server).isEqualTo(legacy.server)
        assertThat(migrated.active?.token).isEqualTo("legacy-token")
        assertThat(migrated.summaries().single().displayName).isEqualTo("legacy-user")
    }

    @Test
    fun `switching accounts selects only the target token`() {
        val firstId = AccountId("first")
        val secondId = AccountId("second")
        var state = AccountStateMachine.add(
            AccountState(),
            firstId,
            AccountDraft("Personal", profile("one.example.test", "alice")),
            makeActive = true,
        ).state
        state = AccountStateMachine.setToken(state, firstId, "token-one")
        state = AccountStateMachine.add(
            state,
            secondId,
            AccountDraft("Work", profile("two.example.test", "bob")),
            makeActive = false,
        ).state
        state = AccountStateMachine.setToken(state, secondId, "token-two")

        val switched = AccountStateMachine.switch(state, secondId)

        assertThat(switched.active?.server?.username).isEqualTo("bob")
        assertThat(switched.active?.token).isEqualTo("token-two")
        assertThat(switched.records.first { it.id == firstId }.token).isEqualTo("token-one")
    }

    @Test
    fun `editing display name preserves token but editing identity clears it`() {
        val id = AccountId("account")
        var state = AccountStateMachine.add(
            AccountState(),
            id,
            AccountDraft("Original", profile("one.example.test", "alice")),
            makeActive = true,
        ).state
        state = AccountStateMachine.setToken(state, id, "secret-token")

        val renamed = AccountStateMachine.edit(
            state,
            id,
            AccountDraft("Renamed", profile("one.example.test", "alice")),
        )
        assertThat(renamed.active?.displayName).isEqualTo("Renamed")
        assertThat(renamed.active?.token).isEqualTo("secret-token")

        val identityChanged = AccountStateMachine.edit(
            renamed,
            id,
            AccountDraft("Renamed", profile("other.example.test", "alice")),
        )
        assertThat(identityChanged.active?.token).isEmpty()
    }

    @Test
    fun `begin login clears only selected identity and does not duplicate it`() {
        val firstId = AccountId("first")
        val secondId = AccountId("second")
        var state = AccountStateMachine.add(
            AccountState(),
            firstId,
            AccountDraft(server = profile("one.example.test", "alice")),
            makeActive = true,
        ).state
        state = AccountStateMachine.setToken(state, firstId, "stale-one")
        state = AccountStateMachine.add(
            state,
            secondId,
            AccountDraft(server = profile("two.example.test", "bob")),
            makeActive = false,
        ).state
        state = AccountStateMachine.setToken(state, secondId, "keep-two")

        val mutation = AccountStateMachine.beginLogin(
            state,
            idForNewAccount = AccountId("unused"),
            profile = profile("one.example.test", "alice"),
        )

        assertThat(mutation.accountId).isEqualTo(firstId)
        assertThat(mutation.state.records).hasSize(2)
        assertThat(mutation.state.active?.token).isEmpty()
        assertThat(mutation.state.records.first { it.id == secondId }.token).isEqualTo("keep-two")
    }

    @Test
    fun `login completion after active switch writes only its original target`() {
        val firstId = AccountId("first")
        val secondId = AccountId("second")
        val firstProfile = profile("one.example.test", "alice")
        val secondProfile = profile("two.example.test", "bob")
        var state = AccountStateMachine.add(
            AccountState(),
            firstId,
            AccountDraft(server = firstProfile),
            makeActive = true,
        ).state
        state = AccountStateMachine.add(
            state,
            secondId,
            AccountDraft(server = secondProfile),
            makeActive = false,
        ).state
        state = AccountStateMachine.setToken(state, secondId, "second-token")
        val loginBarrier = AccountStateMachine.beginLogin(
            state,
            idForNewAccount = AccountId("unused"),
            profile = firstProfile,
        )
        val switchedAway = AccountStateMachine.switch(loginBarrier.state, secondId)

        val completed = AccountStateMachine.completeLogin(
            state = switchedAway,
            id = loginBarrier.accountId,
            expectedProfile = firstProfile,
            token = "new-first-token",
        )

        assertThat(completed.completion.isActive).isFalse()
        assertThat(completed.state.activeId).isEqualTo(secondId)
        assertThat(completed.state.active?.token).isEqualTo("second-token")
        assertThat(completed.state.records.first { it.id == firstId }.token)
            .isEqualTo("new-first-token")
    }

    @Test
    fun `stale login identity cannot commit and failure cannot clear edited account`() {
        val id = AccountId("target")
        val original = profile("one.example.test", "alice")
        var state = AccountStateMachine.add(
            AccountState(),
            id,
            AccountDraft(server = original),
            makeActive = true,
        ).state
        state = AccountStateMachine.edit(
            state,
            id,
            AccountDraft(server = profile("other.example.test", "alice")),
        )
        state = AccountStateMachine.setToken(state, id, "new-identity-token")

        val error = runCatching {
            AccountStateMachine.completeLogin(state, id, original, "stale-token")
        }.exceptionOrNull()
        val failed = AccountStateMachine.failLogin(state, id, original)

        assertThat(error).isInstanceOf(StaleLoginAttemptException::class.java)
        assertThat(failed.cleared).isFalse()
        assertThat(failed.state.active?.token).isEqualTo("new-identity-token")
    }

    @Test
    fun `failed login after switch clears target only`() {
        val firstId = AccountId("first")
        val secondId = AccountId("second")
        val firstProfile = profile("one.example.test", "alice")
        var state = AccountStateMachine.add(
            AccountState(),
            firstId,
            AccountDraft(server = firstProfile),
            makeActive = true,
        ).state
        state = AccountStateMachine.setToken(state, firstId, "first-token")
        state = AccountStateMachine.add(
            state,
            secondId,
            AccountDraft(server = profile("two.example.test", "bob")),
            makeActive = false,
        ).state
        state = AccountStateMachine.setToken(state, secondId, "second-token")
        state = AccountStateMachine.switch(state, secondId)

        val failed = AccountStateMachine.failLogin(state, firstId, firstProfile)

        assertThat(failed.cleared).isTrue()
        assertThat(failed.state.records.first { it.id == firstId }.token).isEmpty()
        assertThat(failed.state.active?.token).isEqualTo("second-token")
    }

    @Test
    fun `public summaries and diagnostic strings never contain tokens`() {
        val id = AccountId("safe")
        var state = AccountStateMachine.add(
            AccountState(),
            id,
            AccountDraft(server = profile("one.example.test", "alice")),
            makeActive = true,
        ).state
        state = AccountStateMachine.setToken(state, id, "never-print-this")

        val summary = state.summaries().single()
        assertThat(summary.isAuthenticated).isTrue()
        assertThat(summary.requiresLogin).isFalse()
        assertThat(summary.toString()).doesNotContain("never-print-this")
        assertThat(state.toString()).doesNotContain("never-print-this")
        assertThat(state.active.toString()).contains("token=<redacted>")
    }

    private fun profile(host: String, username: String) = ServerProfile(
        baseUrl = "https://$host",
        username = username,
    )
}
