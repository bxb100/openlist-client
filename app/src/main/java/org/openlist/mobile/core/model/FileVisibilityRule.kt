package org.openlist.mobile.core.model

/**
 * A local visibility preference that matches a complete file or directory name.
 *
 * Rules are applied in list order; the last matching rule determines visibility. Unmatched names
 * remain visible. [id] identifies a rule across edits and reordering; [pattern] is not trimmed
 * because spaces may be part of a name.
 */
data class FileVisibilityRule(
    val id: String,
    val pattern: String,
    val action: FileVisibilityAction = FileVisibilityAction.Hide,
    val target: FileVisibilityTarget = FileVisibilityTarget.All,
) {
    init {
        require(id.isNotBlank()) { "Rule id must not be blank" }
        require(pattern.isNotEmpty()) { "Rule pattern must not be empty" }
    }
}

enum class FileVisibilityAction {
    Hide,
    Show,
}

enum class FileVisibilityTarget {
    Files,
    Directories,
    All,
}
