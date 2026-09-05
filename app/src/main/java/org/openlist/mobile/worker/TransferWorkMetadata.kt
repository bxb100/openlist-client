package org.openlist.mobile.worker

/**
 * Presentation metadata that survives WorkManager progress cleanup, including queued and cancelled
 * work. Identity is an existing one-way session binding; no credential or source/target URI is added.
 */
internal object TransferWorkMetadata {
    private const val SESSION_PREFIX = "openlist-transfer-session:"
    private const val NAME_PREFIX = "openlist-transfer-name:"
    private const val CREATED_PREFIX = "openlist-transfer-created:"
    private val validBinding = Regex("[0-9a-f]{64}")

    fun sessionTag(binding: String): String {
        require(validBinding.matches(binding)) { "Invalid transfer session binding" }
        return SESSION_PREFIX + binding
    }

    fun tags(binding: String, remotePath: String, createdAtMillis: Long): Set<String> = setOf(
        sessionTag(binding),
        NAME_PREFIX + displayName(remotePath),
        CREATED_PREFIX + createdAtMillis.coerceAtLeast(0L),
    )

    fun name(tags: Set<String>): String? = tags.singleOrNull { it.startsWith(NAME_PREFIX) }
        ?.removePrefix(NAME_PREFIX)
        ?.takeIf(String::isNotBlank)

    fun createdAtMillis(tags: Set<String>): Long? =
        tags.singleOrNull { it.startsWith(CREATED_PREFIX) }
            ?.removePrefix(CREATED_PREFIX)
            ?.toLongOrNull()
            ?.takeIf { it >= 0L }

    private fun displayName(remotePath: String): String = remotePath.substringAfterLast('/')
        .filterNot { character ->
            character.isISOControl() || character in '\u202a'..'\u202e' ||
                character in '\u2066'..'\u2069'
        }
        .take(512)
        .takeIf(String::isNotBlank)
        ?: "文件"
}
