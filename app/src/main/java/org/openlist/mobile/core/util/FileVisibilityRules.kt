package org.openlist.mobile.core.util

import org.openlist.mobile.core.model.FileVisibilityAction
import org.openlist.mobile.core.model.FileVisibilityRule
import org.openlist.mobile.core.model.FileVisibilityTarget

/**
 * Compiled, immutable rules suitable for reuse across directory, search and media results.
 *
 * Matching covers the complete name, with no path or regular-expression syntax. `*` matches zero
 * or more Unicode code points and `?` matches exactly one. A backslash escapes the next character;
 * a trailing backslash is literal. Matching uses locale-independent, simple Unicode case folding,
 * without changing accents or expanding one character into several. A supplementary character
 * such as an emoji is one code point; combining marks and joined emoji remain multiple code points.
 */
class FileVisibilityMatcher private constructor(
    private val rules: List<CompiledRule>,
) {
    fun isVisible(name: String, isDirectory: Boolean): Boolean {
        var foldedName: IntArray? = null
        for (index in rules.lastIndex downTo 0) {
            val rule = rules[index]
            if (!rule.appliesTo(isDirectory)) continue
            val codePoints = foldedName ?: name.foldedCodePoints().also { foldedName = it }
            if (rule.pattern.matches(codePoints)) {
                return rule.action == FileVisibilityAction.Show
            }
        }
        return true
    }

    /**
     * Checks a raw remote path by matching each slash-separated name. Every parent is evaluated
     * as a directory; the last name uses [isDirectory]. A show rule for a descendant cannot cross
     * a hidden parent. Empty path segments are ignored and the root always remains visible.
     * Names are neither URL-decoded nor trimmed.
     */
    fun isPathVisible(path: String, isDirectory: Boolean): Boolean {
        val names = path.split('/').filter(String::isNotEmpty)
        for ((index, name) in names.withIndex()) {
            if (!isVisible(name, isDirectory = index < names.lastIndex || isDirectory)) return false
        }
        return true
    }

    companion object {
        fun compile(rules: List<FileVisibilityRule>): FileVisibilityMatcher = FileVisibilityMatcher(
            rules.map { rule ->
                CompiledRule(
                    pattern = NamePattern.compile(rule.pattern),
                    action = rule.action,
                    target = rule.target,
                )
            },
        )
    }
}

private data class CompiledRule(
    val pattern: NamePattern,
    val action: FileVisibilityAction,
    val target: FileVisibilityTarget,
) {
    fun appliesTo(isDirectory: Boolean): Boolean = when (target) {
        FileVisibilityTarget.Files -> !isDirectory
        FileVisibilityTarget.Directories -> isDirectory
        FileVisibilityTarget.All -> true
    }
}

private class NamePattern(
    private val tokens: IntArray,
    private val minimumLength: Int,
) {
    fun matches(name: IntArray): Boolean {
        if (name.size < minimumLength) return false
        var patternIndex = 0
        var nameIndex = 0
        var lastStarIndex = -1
        var starEndIndex = 0

        // Only the most recent star needs to absorb more input. This uses constant matching
        // space, never recurses and takes at most O(pattern length * name length), unlike regex
        // or recursive wildcard backtracking on patterns such as "*a*a*a*...b".
        while (nameIndex < name.size) {
            when {
                patternIndex < tokens.size && tokens[patternIndex] == STAR -> {
                    lastStarIndex = patternIndex++
                    starEndIndex = nameIndex
                }
                patternIndex < tokens.size &&
                    (tokens[patternIndex] == ANY || tokens[patternIndex] == name[nameIndex]) -> {
                    patternIndex++
                    nameIndex++
                }
                lastStarIndex >= 0 -> {
                    patternIndex = lastStarIndex + 1
                    nameIndex = ++starEndIndex
                }
                else -> return false
            }
        }
        while (patternIndex < tokens.size && tokens[patternIndex] == STAR) patternIndex++
        return patternIndex == tokens.size
    }

    companion object {
        private const val ANY = -1
        private const val STAR = -2

        fun compile(pattern: String): NamePattern {
            require(pattern.isNotEmpty()) { "Rule pattern must not be empty" }
            val tokens = ArrayList<Int>(pattern.length)
            var index = 0
            var minimumLength = 0
            while (index < pattern.length) {
                val codePoint = pattern.codePointAt(index)
                index += Character.charCount(codePoint)
                val token = when (codePoint) {
                    '\\'.code -> {
                        if (index < pattern.length) {
                            val escaped = pattern.codePointAt(index)
                            index += Character.charCount(escaped)
                            escaped.foldCase()
                        } else {
                            '\\'.code
                        }
                    }
                    '*'.code -> STAR
                    '?'.code -> ANY
                    else -> codePoint.foldCase()
                }
                if (token != STAR || tokens.lastOrNull() != STAR) tokens += token
                if (token != STAR) minimumLength++
            }
            return NamePattern(tokens.toIntArray(), minimumLength)
        }
    }
}

private fun String.foldedCodePoints(): IntArray {
    val result = IntArray(codePointCount(0, length))
    var sourceIndex = 0
    var destinationIndex = 0
    while (sourceIndex < length) {
        val codePoint = codePointAt(sourceIndex)
        result[destinationIndex++] = codePoint.foldCase()
        sourceIndex += Character.charCount(codePoint)
    }
    return result
}

private fun Int.foldCase(): Int = Character.toLowerCase(Character.toUpperCase(this))
