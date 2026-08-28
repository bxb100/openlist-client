package org.openlist.mobile.data.credentials

import org.openlist.mobile.core.model.ServerProfile

/**
 * Process-memory-only credentials for password-protected OpenList paths.
 *
 * Passwords deliberately never enter DataStore, media items, cache keys, or a value type whose
 * generated [toString] could disclose them. The process boundary is also the lifetime boundary:
 * callers must clear this store when the signed-in server/account changes or logs out.
 */
class InMemoryPathCredentialStore {
    private val lock = Any()
    private val passwords = LinkedHashMap<CredentialKey, String>()

    /** Returns the password from the most specific protected ancestor, or an empty string. */
    fun passwordFor(profile: ServerProfile, requestedPath: String): String = synchronized(lock) {
        val identity = CredentialIdentity.from(profile)
        val path = normalizeCredentialPath(requestedPath)
        passwords.entries
            .asSequence()
            .filter { (key, _) -> key.identity == identity && key.protectedPath.isAncestorOf(path) }
            .maxByOrNull { (key, _) -> key.protectedPath.length }
            ?.value
            .orEmpty()
    }

    /**
     * Remembers a credential for [protectedPath], replacing an earlier attempt for that exact
     * scope. Call this only after the server has accepted [password].
     */
    fun remember(profile: ServerProfile, protectedPath: String, password: String) {
        require(password.isNotEmpty()) { "A protected-path password must not be empty" }
        val key = CredentialKey(
            identity = CredentialIdentity.from(profile),
            protectedPath = normalizeCredentialPath(protectedPath),
        )
        synchronized(lock) { passwords[key] = password }
    }

    /** Forgets one exact scope without disturbing credentials for nested protected paths. */
    fun forget(profile: ServerProfile, protectedPath: String) {
        val key = CredentialKey(
            identity = CredentialIdentity.from(profile),
            protectedPath = normalizeCredentialPath(protectedPath),
        )
        synchronized(lock) { passwords.remove(key) }
    }

    /** Releases all password references, for example on logout or account/server switching. */
    fun clear() {
        synchronized(lock) { passwords.clear() }
    }

    private data class CredentialKey(
        val identity: CredentialIdentity,
        val protectedPath: String,
    )

    private data class CredentialIdentity(
        val serverBaseUrl: String,
        val username: String,
    ) {
        companion object {
            fun from(profile: ServerProfile): CredentialIdentity = CredentialIdentity(
                serverBaseUrl = runCatching { profile.normalizedBaseUrl() }
                    .getOrElse { profile.baseUrl.trim().trimEnd('/') },
                username = profile.username,
            )
        }
    }
}

internal fun normalizeCredentialPath(path: String): String {
    val segments = ArrayDeque<String>()
    path.split('/').forEach { segment ->
        when (segment) {
            "", "." -> Unit
            ".." -> if (segments.isNotEmpty()) segments.removeLast()
            else -> segments.addLast(segment)
        }
    }
    return if (segments.isEmpty()) "/" else segments.joinToString(separator = "/", prefix = "/")
}

private fun String.isAncestorOf(path: String): Boolean =
    this == "/" || path == this || path.startsWith("$this/")
