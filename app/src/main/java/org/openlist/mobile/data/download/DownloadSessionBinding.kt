package org.openlist.mobile.data.download

import org.openlist.mobile.core.model.ServerProfile
import java.security.MessageDigest

/**
 * A one-way binding to the exact signed-in session. The bearer token itself is never persisted in
 * WorkManager Data; changing server, account, login token, or logging out invalidates queued work.
 */
@JvmInline
value class DownloadSessionBinding private constructor(val value: String) {
    init {
        require(value.matches(HEX_SHA256)) { "Invalid download session binding" }
    }

    fun matches(other: DownloadSessionBinding): Boolean = MessageDigest.isEqual(
        value.toByteArray(Charsets.US_ASCII),
        other.value.toByteArray(Charsets.US_ASCII),
    )

    companion object {
        private val HEX_SHA256 = Regex("[0-9a-f]{64}")

        fun create(profile: ServerProfile, token: String): DownloadSessionBinding {
            val server = runCatching { profile.normalizedBaseUrl() }
                .getOrElse { profile.baseUrl.trim().trimEnd('/') }
            val tokenDigest = sha256(token)
            return DownloadSessionBinding(
                sha256("openlist-download-session-v1\u0000$server\u0000${profile.username}\u0000$tokenDigest"),
            )
        }

        fun parse(value: String): DownloadSessionBinding = DownloadSessionBinding(value)

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
