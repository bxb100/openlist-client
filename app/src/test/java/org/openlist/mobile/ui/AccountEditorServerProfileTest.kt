package org.openlist.mobile.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.openlist.mobile.core.model.ServerProfile
import org.openlist.mobile.data.account.AccountDraft
import org.openlist.mobile.data.account.AccountId
import org.openlist.mobile.data.account.AccountState
import org.openlist.mobile.data.account.AccountStateMachine
import org.openlist.mobile.ui.account.accountConnectionDraft

class AccountEditorServerProfileTest {
    @Test
    fun `editing a saved connection preserves its explicit port and proxy path`() {
        val endpoint = LoginEndpointDraft.fromBaseUrl("https://files.example.com:8443/team%2Ffiles")
        val profile = endpoint.serverProfile(" alice ", allowInsecureHttp = false)

        assertThat(profile.baseUrl).isEqualTo("https://files.example.com:8443/team%2Ffiles")
        assertThat(profile.username).isEqualTo("alice")
        assertThat(profile.allowInsecureHttp).isFalse()
    }

    @Test
    fun `editing a connection does not implicitly permit disabled cleartext transport`() {
        val endpoint = LoginEndpointDraft.fromBaseUrl("http://files.example.com:5244")
        val profile = endpoint.serverProfile("alice", allowInsecureHttp = false)

        assertThat(profile.allowInsecureHttp).isFalse()
        assertThrows(IllegalArgumentException::class.java) { profile.normalizedBaseUrl() }
        assertThat(endpoint.serverProfile("alice", allowInsecureHttp = true).normalizedBaseUrl())
            .isEqualTo("http://files.example.com:5244")
    }

    @Test
    fun `renaming authenticated account through editor preserves original server and session`() {
        listOf(
            ServerProfile("https://files.example.com/网盘", "alice"),
            ServerProfile("https://files.example.com//tenant", "alice"),
            ServerProfile("https://files.example.com/team%2ffiles/%e6%96%87", "alice"),
            ServerProfile("https://Files.Example.com:00443/proxy", "alice"),
            ServerProfile("http://nas.local:80/proxy", "alice", allowInsecureHttp = true),
            ServerProfile("https://files.example.com/proxy", "alice", allowInsecureHttp = true),
        ).forEach { source ->
            val state = authenticatedAccount(source)
            val saved = requireNotNull(state.active)
            val draft = accountConnectionDraft(
                displayName = "Renamed",
                endpoint = LoginEndpointDraft.fromBaseUrl(saved.server.baseUrl),
                username = saved.server.username,
                allowInsecureHttp = saved.server.allowInsecureHttp,
                savedServer = saved.server,
            )
            val renamed = requireNotNull(AccountStateMachine.edit(state, saved.id, draft).active)

            // AppContainer also compares the full profile to decide whether playback must stop.
            assertThat(draft.server).isEqualTo(saved.server)
            assertThat(renamed.displayName).isEqualTo("Renamed")
            assertThat(renamed.server).isEqualTo(saved.server)
            assertThat(renamed.token).isEqualTo("session-token")
            assertThat(renamed.encryptedPasswordHash).isEqualTo("encrypted-hash")
            assertThat(renamed.encryptedPassword).isEqualTo("encrypted-password")
            assertThat(renamed.sessionBindingKey).isEqualTo("session-token")
        }
    }

    @Test
    fun `equivalent form edits keep saved representation and active authentication`() {
        val state = authenticatedAccount(ServerProfile("https://Files.Example.com:443/网盘/team%2ffiles", "alice"))
        val saved = requireNotNull(state.active)
        val endpoint = LoginEndpointDraft.fromBaseUrl("https://files.example.com/%E7%BD%91%E7%9B%98/team%2Ffiles")
        val draft = accountConnectionDraft("Renamed", endpoint, " alice ", false, saved.server)
        val edited = requireNotNull(AccountStateMachine.edit(state, saved.id, draft).active)

        assertThat(draft.server).isEqualTo(saved.server)
        assertThat(edited.token).isEqualTo(saved.token)
        assertThat(edited.encryptedPassword).isEqualTo(saved.encryptedPassword)
        assertThat(edited.sessionBindingKey).isEqualTo(saved.sessionBindingKey)
    }

    @Test
    fun `changed destination or username through editor still invalidates authentication`() {
        val state = authenticatedAccount(ServerProfile("https://files.example.com/team%2Ffiles", "alice"))
        val saved = requireNotNull(state.active)
        val originalEndpoint = LoginEndpointDraft.fromBaseUrl(saved.server.baseUrl)
        listOf(
            originalEndpoint.copy(host = "other.example.com") to "alice",
            originalEndpoint.copy(port = "8443") to "alice",
            originalEndpoint.copy(protocol = LoginProtocol.HTTP) to "alice",
            originalEndpoint.copy(basePath = "/team/files") to "alice",
            originalEndpoint to "bob",
        ).forEach { (endpoint, username) ->
            val draft = accountConnectionDraft("Renamed", endpoint, username, true, saved.server)
            val edited = requireNotNull(AccountStateMachine.edit(state, saved.id, draft).active)

            assertThat(draft.server).isNotEqualTo(saved.server)
            assertThat(edited.token).isEmpty()
            assertThat(edited.encryptedPasswordHash).isEmpty()
            assertThat(edited.encryptedPassword).isEmpty()
            assertThat(edited.sessionBindingKey).isEmpty()
        }
    }

    @Test
    fun `changing repeated leading slash in saved proxy path invalidates its session`() {
        val state = authenticatedAccount(ServerProfile("https://files.example.com//tenant", "alice"))
        val saved = requireNotNull(state.active)
        val draft = accountConnectionDraft(
            displayName = "Original",
            endpoint = LoginEndpointDraft.fromBaseUrl(saved.server.baseUrl).copy(basePath = "/tenant"),
            username = saved.server.username,
            allowInsecureHttp = false,
            savedServer = saved.server,
        )
        val edited = requireNotNull(AccountStateMachine.edit(state, saved.id, draft).active)

        assertThat(draft.server.baseUrl).isEqualTo("https://files.example.com/tenant")
        assertThat(edited.token).isEmpty()
        assertThat(edited.encryptedPasswordHash).isEmpty()
        assertThat(edited.encryptedPassword).isEmpty()
        assertThat(edited.sessionBindingKey).isEmpty()
    }

    private fun authenticatedAccount(server: ServerProfile): AccountState {
        val id = AccountId("account")
        val state = AccountStateMachine.add(
            AccountState(),
            id,
            AccountDraft("Original", server),
            makeActive = true,
        ).state
        return AccountStateMachine.completeLogin(
            state,
            id,
            requireNotNull(state.active).server,
            token = "session-token",
            encryptedPasswordHash = "encrypted-hash",
            encryptedPassword = "encrypted-password",
        ).state
    }
}
