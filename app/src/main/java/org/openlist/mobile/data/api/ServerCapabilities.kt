package org.openlist.mobile.data.api

data class ServerCapabilities(
    val version: String = "",
    val multipartEnabled: Boolean = false,
    val multipartChunkSizeMiB: Int = 10,
    val ssoEnabled: Boolean = false,
    val ldapEnabled: Boolean = false,
    val webAuthnEnabled: Boolean = false,
) {
    companion object {
        fun from(settings: Map<String, String>): ServerCapabilities = ServerCapabilities(
            version = settings["version"].orEmpty(),
            multipartEnabled = settings["multipart_enabled"].toBooleanLenient(),
            multipartChunkSizeMiB = settings["multipart_chunk_size"]
                ?.toIntOrNull()
                ?.coerceAtLeast(1)
                ?: 10,
            ssoEnabled = settings["sso_login_enabled"].toBooleanLenient(),
            ldapEnabled = settings["ldap_login_enabled"].toBooleanLenient(),
            webAuthnEnabled = settings["webauthn_login_enabled"].toBooleanLenient(),
        )
    }
}

private fun String?.toBooleanLenient(): Boolean =
    this.equals("true", ignoreCase = true) || this == "1"
