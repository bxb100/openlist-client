package org.openlist.mobile.data.upload

import java.security.MessageDigest
import org.openlist.mobile.core.model.ServerProfile

/**
 * One-way identity for the exact session that enqueued an upload.
 *
 * WorkManager persists this digest rather than the bearer token. A task may start after the app
 * process has been recreated, so matching only the server and username is insufficient: signing
 * out and back into the same account must not authorize an older queued upload.
 */
@JvmInline
value class UploadSessionBinding private constructor(val value: String) {
    init {
        require(value.matches(HEX_SHA256)) { "Invalid upload session binding" }
    }

    fun matches(other: UploadSessionBinding): Boolean = MessageDigest.isEqual(
        value.toByteArray(Charsets.US_ASCII),
        other.value.toByteArray(Charsets.US_ASCII),
    )

    companion object {
        private val HEX_SHA256 = Regex("[0-9a-f]{64}")

        fun create(profile: ServerProfile, token: String): UploadSessionBinding {
            val server = runCatching { profile.normalizedBaseUrl() }
                .getOrElse { profile.baseUrl.trim().trimEnd('/') }
            val tokenDigest = sha256(token)
            return UploadSessionBinding(
                sha256("openlist-upload-session-v1\u0000$server\u0000${profile.username}\u0000$tokenDigest"),
            )
        }

        fun parse(value: String): UploadSessionBinding = UploadSessionBinding(value)

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
