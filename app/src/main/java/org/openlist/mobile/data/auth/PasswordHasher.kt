package org.openlist.mobile.data.auth

import java.security.MessageDigest

object PasswordHasher {
    private const val STATIC_SALT = "https://github.com/alist-org/alist"

    /** Matches OpenList model.StaticHash without sending the raw password. */
    fun forOpenList(password: String): String = MessageDigest.getInstance("SHA-256")
        .digest("$password-$STATIC_SALT".toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

