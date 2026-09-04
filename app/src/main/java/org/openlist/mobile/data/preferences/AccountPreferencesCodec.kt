package org.openlist.mobile.data.preferences

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import org.openlist.mobile.core.model.ServerProfile
import org.openlist.mobile.data.account.AccountId
import org.openlist.mobile.data.account.AccountRecord
import org.openlist.mobile.data.account.AccountState
import org.openlist.mobile.data.account.AccountStateMachine
import org.openlist.mobile.data.account.LegacyAccount
import org.openlist.mobile.data.account.defaultAccountDisplayName

internal data class DecodedAccounts(
    val state: AccountState,
    val requiresMigrationWrite: Boolean,
)

/** Preferences serialization isolated from account state transitions and public summaries. */
internal object AccountPreferencesCodec {
    private const val CURRENT_SCHEMA = 1

    fun decode(preferences: Preferences): DecodedAccounts {
        if ((preferences[Keys.SCHEMA] ?: 0) >= CURRENT_SCHEMA) {
            val ids = decodeOrder(preferences[Keys.ORDER].orEmpty())
            val records = ids.mapNotNull { id -> decodeRecord(preferences, id) }
            val requestedActive = preferences[Keys.ACTIVE_ID]
                ?.let { raw -> runCatching { AccountId(raw) }.getOrNull() }
            return DecodedAccounts(AccountState(records, requestedActive), false)
        }

        val hasLegacyAccount = preferences.asMap().keys.any { key ->
            key == Keys.LEGACY_BASE_URL ||
                key == Keys.LEGACY_USERNAME ||
                key == Keys.LEGACY_ALLOW_INSECURE_HTTP ||
                key == Keys.LEGACY_TOKEN
        }
        val legacy = if (hasLegacyAccount) {
            LegacyAccount(
                server = ServerProfile(
                    baseUrl = preferences[Keys.LEGACY_BASE_URL].orEmpty(),
                    username = preferences[Keys.LEGACY_USERNAME].orEmpty(),
                    allowInsecureHttp = preferences[Keys.LEGACY_ALLOW_INSECURE_HTTP] ?: false,
                ),
                token = preferences[Keys.LEGACY_TOKEN].orEmpty(),
            )
        } else {
            null
        }
        return DecodedAccounts(
            state = AccountStateMachine.migrateLegacy(legacy),
            requiresMigrationWrite = true,
        )
    }

    fun write(preferences: MutablePreferences, state: AccountState) {
        val oldIds = decodeOrder(preferences[Keys.ORDER].orEmpty())
        val newIds = state.records.map(AccountRecord::id).toSet()
        oldIds.filterNot(newIds::contains).forEach { removeRecord(preferences, it) }

        preferences[Keys.SCHEMA] = CURRENT_SCHEMA
        preferences[Keys.ORDER] = state.records.joinToString(",") { it.id.value }
        state.activeId?.let { preferences[Keys.ACTIVE_ID] = it.value }
            ?: preferences.remove(Keys.ACTIVE_ID)

        state.records.forEach { record ->
            preferences[Keys.displayName(record.id)] = record.displayName
            preferences[Keys.baseUrl(record.id)] = record.server.baseUrl
            preferences[Keys.username(record.id)] = record.server.username
            preferences[Keys.allowInsecureHttp(record.id)] = record.server.allowInsecureHttp
            preferences[Keys.token(record.id)] = record.token
            preferences[Keys.encryptedPasswordHash(record.id)] = record.encryptedPasswordHash
            preferences[Keys.sessionBindingKey(record.id)] = record.sessionBindingKey
        }

        // The active account is now derived from the account table. Remove the former standalone
        // token so there is only one authoritative credential slot after migration.
        preferences.remove(Keys.LEGACY_BASE_URL)
        preferences.remove(Keys.LEGACY_USERNAME)
        preferences.remove(Keys.LEGACY_ALLOW_INSECURE_HTTP)
        preferences.remove(Keys.LEGACY_TOKEN)
    }

    private fun decodeRecord(preferences: Preferences, id: AccountId): AccountRecord? {
        val baseUrl = preferences[Keys.baseUrl(id)] ?: return null
        val server = ServerProfile(
            baseUrl = baseUrl,
            username = preferences[Keys.username(id)].orEmpty(),
            allowInsecureHttp = preferences[Keys.allowInsecureHttp(id)] ?: false,
        )
        return AccountRecord(
            id = id,
            displayName = preferences[Keys.displayName(id)]
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: defaultAccountDisplayName(server),
            server = server,
            token = preferences[Keys.token(id)].orEmpty(),
            encryptedPasswordHash = preferences[Keys.encryptedPasswordHash(id)].orEmpty(),
            // Existing work used the login token as its binding before token renewal existed.
            sessionBindingKey = preferences[Keys.sessionBindingKey(id)]
                ?: preferences[Keys.token(id)].orEmpty(),
        )
    }

    private fun removeRecord(preferences: MutablePreferences, id: AccountId) {
        preferences.remove(Keys.displayName(id))
        preferences.remove(Keys.baseUrl(id))
        preferences.remove(Keys.username(id))
        preferences.remove(Keys.allowInsecureHttp(id))
        preferences.remove(Keys.token(id))
        preferences.remove(Keys.encryptedPasswordHash(id))
        preferences.remove(Keys.sessionBindingKey(id))
    }

    private fun decodeOrder(value: String): List<AccountId> = value
        .split(',')
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .mapNotNull { raw -> runCatching { AccountId(raw) }.getOrNull() }
        .distinct()
        .toList()

    internal object Keys {
        val SCHEMA = intPreferencesKey("accounts_schema")
        val ORDER = stringPreferencesKey("accounts_order")
        val ACTIVE_ID = stringPreferencesKey("active_account_id")

        val LEGACY_BASE_URL = stringPreferencesKey("server_base_url")
        val LEGACY_USERNAME = stringPreferencesKey("server_username")
        val LEGACY_ALLOW_INSECURE_HTTP = booleanPreferencesKey("server_allow_insecure_http")
        val LEGACY_TOKEN = stringPreferencesKey("auth_token")

        fun displayName(id: AccountId) = stringPreferencesKey("account.${id.value}.display_name")
        fun baseUrl(id: AccountId) = stringPreferencesKey("account.${id.value}.base_url")
        fun username(id: AccountId) = stringPreferencesKey("account.${id.value}.username")
        fun allowInsecureHttp(id: AccountId) =
            booleanPreferencesKey("account.${id.value}.allow_insecure_http")
        fun token(id: AccountId) = stringPreferencesKey("account.${id.value}.token")
        fun encryptedPasswordHash(id: AccountId) =
            stringPreferencesKey("account.${id.value}.encrypted_password_hash")
        fun sessionBindingKey(id: AccountId) = stringPreferencesKey("account.${id.value}.session_binding_key")
    }
}
