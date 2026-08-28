package org.openlist.mobile.data.logging

/** Defense-in-depth redaction applied to both module names and messages before they enter memory. */
object LogSanitizer {
    const val REDACTED = "[REDACTED]"
    const val MAX_INPUT_CHARS = 65_536

    private const val SECRET_NAME =
        "(?:authorization|proxy-authorization|token|access[_-]?token|refresh[_-]?token|" +
            "password|passwd|pwd|secret|api[_-]?key|cookie|set-cookie|sign|signature|raw[_-]?url)"

    private val quotedAssignment = Regex(
        pattern = "(?i)([\\\"']?$SECRET_NAME[\\\"']?\\s*[:=]\\s*)(\\\"(?:\\\\.|[^\\\"\\\\])*\\\"|'(?:\\\\.|[^'\\\\])*')",
    )
    private val authorizationHeader = Regex(
        pattern = "(?i)(\\b(?:authorization|proxy-authorization)\\b[\\\"']?\\s*[:=]\\s*)([^\\r\\n,}]+)",
    )
    private val rawUrlAssignment = Regex(
        pattern = "(?i)(\\braw[_-]?url\\b[\\\"']?\\s*[:=]\\s*)([^\\s,}\\]]+)",
    )
    private val querySecret = Regex(
        pattern = "(?i)([?&#]$SECRET_NAME=)([^&#\\s]*)",
    )
    private val unquotedAssignment = Regex(
        pattern = "(?i)(\\b$SECRET_NAME\\b[\\\"']?\\s*(?:[:=]\\s*|\\s+(?:is\\s+)?))([^\\s,;&}\\]]+)",
    )
    private val bearerCredential = Regex(
        pattern = "(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]+",
    )
    private val jwt = Regex(
        pattern = "\\beyJ[A-Za-z0-9_-]+\\.eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b",
    )
    private val urlUserInfo = Regex(
        pattern = "(?i)(https?://)[^/@\\s:]+:[^/@\\s]+@",
    )

    fun redact(value: String): String {
        var sanitized = value.take(MAX_INPUT_CHARS)
        sanitized = quotedAssignment.replace(sanitized) { match ->
            val quotedValue = match.groupValues[2]
            val quote = quotedValue.firstOrNull()?.takeIf { it == '\"' || it == '\'' }
            if (quote == null) {
                match.groupValues[1] + REDACTED
            } else {
                match.groupValues[1] + quote + REDACTED + quote
            }
        }
        sanitized = authorizationHeader.replace(sanitized) { match ->
            if (match.groupValues[2].isAlreadyRedacted()) match.value else match.groupValues[1] + REDACTED
        }
        sanitized = rawUrlAssignment.replace(sanitized) { match ->
            if (match.groupValues[2].isAlreadyRedacted()) match.value else match.groupValues[1] + REDACTED
        }
        sanitized = querySecret.replace(sanitized) { match ->
            if (match.groupValues[2].isAlreadyRedacted()) match.value else match.groupValues[1] + REDACTED
        }
        sanitized = unquotedAssignment.replace(sanitized) { match ->
            if (match.groupValues[2].isAlreadyRedacted()) match.value else match.groupValues[1] + REDACTED
        }
        sanitized = bearerCredential.replace(sanitized) { it.groupValues[1] + " " + REDACTED }
        sanitized = jwt.replace(sanitized, REDACTED)
        sanitized = urlUserInfo.replace(sanitized) { it.groupValues[1] + REDACTED + "@" }
        return sanitized
    }

    private fun String.isAlreadyRedacted(): Boolean =
        trimStart('\"', '\'').startsWith(REDACTED.removeSuffix("]"))
}
