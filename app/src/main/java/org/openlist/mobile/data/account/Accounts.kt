package org.openlist.mobile.data.account

import org.openlist.mobile.core.model.ServerProfile
import java.net.URI

/** Stable, opaque identifier used for account-management operations. */
@JvmInline
value class AccountId(val value: String) {
    init {
        require(value.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "Invalid account id" }
    }

    override fun toString(): String = value
}

/** Public account projection. It intentionally has no token field. */
data class AccountSummary(
    val id: AccountId,
    val displayName: String,
    val server: ServerProfile,
    val isActive: Boolean,
    val isAuthenticated: Boolean,
    val requiresLogin: Boolean,
)

/** Result of a target-scoped login commit; committing never changes the active account. */
data class LoginCompletion(
    val accountId: AccountId,
    val isActive: Boolean,
)

class StaleLoginAttemptException(accountId: AccountId) :
    IllegalStateException("Account identity changed while login was in progress: $accountId")

/** Editable, non-secret account fields. */
data class AccountDraft(
    val displayName: String = "",
    val server: ServerProfile,
)

/**
 * Persisted internal account record. Its string representation always redacts credentials so an
 * accidental diagnostic log cannot disclose credentials.
 */
internal class AccountRecord(
    val id: AccountId,
    val displayName: String,
    val server: ServerProfile,
    val token: String,
    val encryptedPasswordHash: String = "",
    val sessionBindingKey: String = "",
) {
    fun updated(
        displayName: String = this.displayName,
        server: ServerProfile = this.server,
        token: String = this.token,
        encryptedPasswordHash: String = this.encryptedPasswordHash,
        sessionBindingKey: String = this.sessionBindingKey,
    ): AccountRecord = AccountRecord(
        id, displayName, server, token, encryptedPasswordHash, sessionBindingKey,
    )

    fun summary(isActive: Boolean): AccountSummary = AccountSummary(
        id = id,
        displayName = displayName,
        server = server,
        isActive = isActive,
        isAuthenticated = token.isNotBlank(),
        requiresLogin = token.isBlank(),
    )

    override fun toString(): String =
        "AccountRecord(id=$id, displayName=$displayName, server=$server, token=<redacted>, " +
            "encryptedPasswordHash=<redacted>, sessionBindingKey=<redacted>)"
}

internal class AccountState(
    records: List<AccountRecord> = emptyList(),
    activeId: AccountId? = null,
) {
    val records: List<AccountRecord> = records.toList()
    val activeId: AccountId? = activeId?.takeIf { id -> records.any { it.id == id } }
        ?: records.firstOrNull()?.id

    val active: AccountRecord? get() = records.firstOrNull { it.id == activeId }

    fun summaries(): List<AccountSummary> = records.map { it.summary(it.id == activeId) }

    override fun toString(): String = "AccountState(records=$records, activeId=$activeId)"
}

internal class LegacyAccount(
    val server: ServerProfile,
    val token: String,
) {
    override fun toString(): String = "LegacyAccount(server=$server, token=<redacted>)"
}

internal data class AccountMutation(
    val state: AccountState,
    val accountId: AccountId,
)

internal data class LoginCompletionMutation(
    val state: AccountState,
    val completion: LoginCompletion,
)

internal data class LoginFailureMutation(
    val state: AccountState,
    val cleared: Boolean,
)

/** Pure state transitions shared by DataStore and JVM migration/switching tests. */
internal object AccountStateMachine {
    fun migrateLegacy(legacy: LegacyAccount?, id: AccountId = AccountId("legacy")): AccountState {
        if (legacy == null) return AccountState()
        val record = AccountRecord(
            id = id,
            displayName = defaultAccountDisplayName(legacy.server),
            server = legacy.server,
            token = legacy.token,
            sessionBindingKey = legacy.token,
        )
        return AccountState(listOf(record), id)
    }

    fun add(
        state: AccountState,
        id: AccountId,
        draft: AccountDraft,
        makeActive: Boolean,
    ): AccountMutation {
        require(state.records.none { it.id == id }) { "Account id already exists" }
        val server = normalizeNewProfile(draft.server)
        require(state.records.none { it.server.hasSameIdentity(server) }) {
            "An account for this server and username already exists"
        }
        val record = AccountRecord(
            id = id,
            displayName = draft.displayName.normalizedDisplayName(server),
            server = server,
            token = "",
        )
        val records = state.records + record
        val activeId = when {
            state.records.isEmpty() || makeActive -> id
            else -> state.activeId
        }
        return AccountMutation(AccountState(records, activeId), id)
    }

    /** Selects an existing identity or creates it, and clears only that target's stale token. */
    fun beginLogin(
        state: AccountState,
        idForNewAccount: AccountId,
        profile: ServerProfile,
        displayName: String? = null,
    ): AccountMutation {
        val server = normalizeNewProfile(profile)
        val existing = state.records.firstOrNull { it.server.hasSameIdentity(server) }
        if (existing != null) {
            val records = state.records.map { record ->
                if (record.id == existing.id) {
                    record.updated(
                        displayName = displayName
                            ?.normalizedDisplayName(server)
                            ?: record.displayName,
                        server = server,
                        token = "",
                        encryptedPasswordHash = "",
                        sessionBindingKey = "",
                    )
                } else {
                    record
                }
            }
            return AccountMutation(AccountState(records, existing.id), existing.id)
        }
        return add(
            state = state,
            id = idForNewAccount,
            draft = AccountDraft(displayName.orEmpty(), server),
            makeActive = true,
        )
    }

    fun edit(state: AccountState, id: AccountId, draft: AccountDraft): AccountState {
        val existing = state.requireAccount(id)
        val server = normalizeNewProfile(draft.server)
        require(state.records.none { it.id != id && it.server.hasSameIdentity(server) }) {
            "An account for this server and username already exists"
        }
        val identityChanged = !existing.server.hasSameIdentity(server)
        return AccountState(
            records = state.records.map { record ->
                if (record.id == id) {
                    record.updated(
                        displayName = draft.displayName.normalizedDisplayName(server),
                        server = server,
                        token = if (identityChanged) "" else record.token,
                        encryptedPasswordHash = if (identityChanged) "" else record.encryptedPasswordHash,
                        sessionBindingKey = if (identityChanged) "" else record.sessionBindingKey,
                    )
                } else {
                    record
                }
            },
            activeId = state.activeId,
        )
    }

    fun switch(state: AccountState, id: AccountId): AccountState {
        state.requireAccount(id)
        return AccountState(state.records, id)
    }

    fun delete(state: AccountState, id: AccountId): AccountState {
        state.requireAccount(id)
        val remaining = state.records.filterNot { it.id == id }
        val activeId = if (state.activeId == id) remaining.firstOrNull()?.id else state.activeId
        return AccountState(remaining, activeId)
    }

    fun setToken(state: AccountState, id: AccountId, token: String): AccountState {
        state.requireAccount(id)
        return AccountState(
            records = state.records.map { record ->
                if (record.id == id) {
                    record.updated(
                        token = token,
                        encryptedPasswordHash = if (token.isBlank()) "" else record.encryptedPasswordHash,
                        sessionBindingKey = if (token.isBlank()) "" else record.sessionBindingKey,
                    )
                } else {
                    record
                }
            },
            activeId = state.activeId,
        )
    }

    /**
     * Commits a successful login to its original target after atomically checking the identity.
     * The currently active account is deliberately preserved; callers can use [LoginCompletion]
     * to detect that the user switched away while authentication was running.
     */
    fun completeLogin(
        state: AccountState,
        id: AccountId,
        expectedProfile: ServerProfile,
        token: String,
        encryptedPasswordHash: String = "",
    ): LoginCompletionMutation {
        require(token.isNotBlank()) { "Login token must not be blank" }
        val expected = normalizeNewProfile(expectedProfile)
        val target = state.requireAccount(id)
        if (target.server != expected) throw StaleLoginAttemptException(id)
        return LoginCompletionMutation(
            state = AccountState(
                records = state.records.map { record ->
                    if (record.id == id) {
                        record.updated(
                            token = token,
                            encryptedPasswordHash = encryptedPasswordHash,
                            sessionBindingKey = token,
                        )
                    } else {
                        record
                    }
                },
                activeId = state.activeId,
            ),
            completion = LoginCompletion(id, state.activeId == id),
        )
    }

    /** Clears only a matching login target; an edited/deleted target is left untouched. */
    fun failLogin(
        state: AccountState,
        id: AccountId,
        expectedProfile: ServerProfile,
    ): LoginFailureMutation {
        val expected = normalizeNewProfile(expectedProfile)
        val target = state.records.firstOrNull { it.id == id }
            ?: return LoginFailureMutation(state, false)
        if (target.server != expected) return LoginFailureMutation(state, false)
        return LoginFailureMutation(setToken(state, id, ""), true)
    }

    fun matchesIdentity(
        state: AccountState,
        id: AccountId,
        expectedProfile: ServerProfile,
    ): Boolean {
        val expected = runCatching { normalizeNewProfile(expectedProfile) }.getOrNull() ?: return false
        return state.records.firstOrNull { it.id == id }?.server == expected
    }

    private fun AccountState.requireAccount(id: AccountId): AccountRecord =
        records.firstOrNull { it.id == id } ?: throw NoSuchElementException("Unknown account: $id")
}

internal fun defaultAccountDisplayName(profile: ServerProfile): String {
    profile.username.trim().takeIf(String::isNotEmpty)?.let { return it }
    val host = runCatching {
        val value = profile.baseUrl.trim().let { if ("://" in it) it else "https://$it" }
        URI(value).host
    }.getOrNull()
    return host?.takeIf(String::isNotBlank) ?: profile.baseUrl.trim().ifBlank { "OpenList" }
}

private fun String.normalizedDisplayName(server: ServerProfile): String =
    trim().ifBlank { defaultAccountDisplayName(server) }

private fun normalizeNewProfile(profile: ServerProfile): ServerProfile =
    profile.copy(baseUrl = profile.normalizedBaseUrl())

internal fun ServerProfile.hasSameIdentity(other: ServerProfile): Boolean =
    username == other.username && identityBaseUrl() == other.identityBaseUrl()

private fun ServerProfile.identityBaseUrl(): String =
    runCatching { normalizedBaseUrl() }.getOrElse { baseUrl.trim().trimEnd('/') }
