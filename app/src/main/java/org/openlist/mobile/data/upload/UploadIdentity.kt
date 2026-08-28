package org.openlist.mobile.data.upload

import java.security.MessageDigest

object UploadIdentity {
    fun staged(sha256: String, size: Long): String {
        require(SHA256_REGEX.matches(sha256.lowercase())) { "Invalid SHA-256" }
        require(size >= 0) { "size must not be negative" }
        return "sha256:${sha256.lowercase()}:$size"
    }

    fun checkpoint(
        serverScope: String,
        remotePath: String,
        sourceIdentity: String,
    ): String = sha256("${serverScope.trimEnd('/')}\n$remotePath\n$sourceIdentity")

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private val SHA256_REGEX = Regex("[0-9a-f]{64}")
}
