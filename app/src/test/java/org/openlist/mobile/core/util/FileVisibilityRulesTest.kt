package org.openlist.mobile.core.util

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.openlist.mobile.core.model.FileVisibilityAction
import org.openlist.mobile.core.model.FileVisibilityRule
import org.openlist.mobile.core.model.FileVisibilityTarget

class FileVisibilityRulesTest {
    @Test
    fun `a later show exception restores a hidden file while directory targets remain separate`() {
        val matcher = compile(
            rule("*", target = FileVisibilityTarget.Files),
            rule("*.jpg", action = FileVisibilityAction.Show, target = FileVisibilityTarget.Files),
            rule("private*", target = FileVisibilityTarget.Directories),
            rule("private shared", action = FileVisibilityAction.Show),
        )

        assertThat(matcher.isVisible("holiday.JPG", isDirectory = false)).isTrue()
        assertThat(matcher.isVisible("holiday.mp4", isDirectory = false)).isFalse()
        assertThat(matcher.isVisible("public", isDirectory = true)).isTrue()
        assertThat(matcher.isVisible("private photos", isDirectory = true)).isFalse()
        assertThat(matcher.isVisible("private shared", isDirectory = true)).isTrue()
        assertThat(matcher.isVisible("private shared", isDirectory = false)).isTrue()
    }

    @Test
    fun `reordering overlapping rules changes the result and compilation snapshots rule order`() {
        val hide = rule("*.tmp")
        val show = rule("keep*", action = FileVisibilityAction.Show)
        val rules = mutableListOf(hide, show)
        val original = FileVisibilityMatcher.compile(rules)
        rules.reverse()
        val reordered = FileVisibilityMatcher.compile(rules)

        assertThat(original.isVisible("keep.tmp", isDirectory = false)).isTrue()
        assertThat(reordered.isVisible("keep.tmp", isDirectory = false)).isFalse()
    }

    @Test
    fun `showing a media filename cannot expose descendants of a hidden directory`() {
        val matcher = compile(
            rule("*", target = FileVisibilityTarget.Files),
            rule(".hidden", target = FileVisibilityTarget.Directories),
            rule("*.mp4", action = FileVisibilityAction.Show, target = FileVisibilityTarget.Files),
        )

        assertThat(matcher.isVisible("movie.mp4", isDirectory = false)).isTrue()
        assertThat(matcher.isPathVisible("/.hidden/nested/movie.mp4", false)).isFalse()
        assertThat(matcher.isPathVisible("/public/nested/movie.mp4", false)).isTrue()
        assertThat(matcher.isPathVisible("/public/nested/notes.txt", false)).isFalse()
        assertThat(matcher.isPathVisible("/public/.hidden", isDirectory = true)).isFalse()
        assertThat(matcher.isPathVisible("/.hidden%2Fnested/movie.mp4", false)).isTrue()
        assertThat(matcher.isPathVisible("/ .hidden /movie.mp4", false)).isTrue()
    }

    @Test
    fun `later directory exceptions must restore every parent while root stays reachable`() {
        val matcher = compile(
            rule("*", target = FileVisibilityTarget.Directories),
            rule("public", action = FileVisibilityAction.Show, target = FileVisibilityTarget.Directories),
            rule("shared", action = FileVisibilityAction.Show, target = FileVisibilityTarget.Directories),
        )

        assertThat(matcher.isPathVisible("/public/shared/movie.mp4", false)).isTrue()
        assertThat(matcher.isPathVisible("/public/private/movie.mp4", false)).isFalse()
        assertThat(matcher.isPathVisible("/private/shared/movie.mp4", false)).isFalse()
        assertThat(matcher.isPathVisible("/public/shared/", isDirectory = true)).isTrue()
        assertThat(matcher.isPathVisible("/", isDirectory = true)).isTrue()
        assertThat(matcher.isPathVisible("", isDirectory = true)).isTrue()
    }

    @Test
    fun `patterns match whole names and keep unmatched names visible`() {
        val matcher = compile(rule("report?.PDF"), rule(".DS_Store"))

        assertThat(matcher.isVisible("REPORT1.pdf", isDirectory = false)).isFalse()
        assertThat(matcher.isVisible("report.pdf", isDirectory = false)).isTrue()
        assertThat(matcher.isVisible("report12.pdf", isDirectory = false)).isTrue()
        assertThat(matcher.isVisible("my-report1.pdf", isDirectory = false)).isTrue()
        assertThat(matcher.isVisible(".DS_Store backup", isDirectory = false)).isTrue()
        assertThat(FileVisibilityMatcher.compile(emptyList()).isVisible("anything", true)).isTrue()
    }

    @Test
    fun `stars cover empty spans and matching may resume after an unsuccessful literal span`() {
        val matcher = compile(rule("ab**cd*ef"))

        assertThat(matcher.isVisible("abcdef", false)).isFalse()
        assertThat(matcher.isVisible("ab-cd-bad-cd-ef", false)).isFalse()
        assertThat(matcher.isVisible("ab-cd-e", false)).isTrue()
        assertThat(matcher.isVisible("ab-cd-ef-extra", false)).isTrue()
    }

    @Test
    fun `one wildcard consumes a full supplementary character and literals fold Unicode case`() {
        val matcher = compile(rule("photo?.jpg"), rule("𐐀-ÉTÉ.txt"))

        assertThat(matcher.isVisible("photo😀.jpg", false)).isFalse()
        assertThat(matcher.isVisible("photo😀😀.jpg", false)).isTrue()
        assertThat(matcher.isVisible("photo.jpg", false)).isTrue()
        assertThat(matcher.isVisible("𐐨-été.TXT", false)).isFalse()
        assertThat(matcher.isVisible("𐐨-ete.txt", false)).isTrue()
    }

    @Test
    fun `a wildcard cannot consume one half of an emoji surrogate pair`() {
        val highSurrogate = '\uD83D'
        val lowSurrogate = '\uDE00'
        val matcher = compile(rule("?$lowSurrogate"), rule("$highSurrogate?"))

        assertThat(matcher.isVisible("😀", false)).isTrue()
    }

    @Test
    fun `escaping makes wildcard and backslash names literal and trailing backslash is accepted`() {
        val matcher = compile(rule("literal\\*\\?.txt"), rule("folder\\\\name"), rule("trailing\\"))

        assertThat(matcher.isVisible("literal*?.txt", false)).isFalse()
        assertThat(matcher.isVisible("literal12.txt", false)).isTrue()
        assertThat(matcher.isVisible("folder\\name", false)).isFalse()
        assertThat(matcher.isVisible("foldername", false)).isTrue()
        assertThat(matcher.isVisible("trailing\\", false)).isFalse()
    }

    @Test
    fun `regular expression punctuation and surrounding spaces have no special syntax`() {
        val matcher = compile(rule(" [draft](1)+.txt "), rule(" "))

        assertThat(matcher.isVisible(" [draft](1)+.txt ", false)).isFalse()
        assertThat(matcher.isVisible("draft1.txt", false)).isTrue()
        assertThat(matcher.isVisible("[draft](1)+.txt", false)).isTrue()
        assertThat(matcher.isVisible(" ", false)).isFalse()
    }

    @Test(timeout = 2_000)
    fun `adversarial repeated star patterns do not create exponentially many alternatives`() {
        val matcher = compile(rule("*a".repeat(2_000) + "b"))

        assertThat(matcher.isVisible("a".repeat(20_000), false)).isTrue()
        assertThat(matcher.isVisible("a".repeat(20_000) + "b", false)).isFalse()
    }

    @Test
    fun `rules reject empty identities and patterns without deleting meaningful whitespace`() {
        assertThrows(IllegalArgumentException::class.java) { FileVisibilityRule("", "*") }
        assertThrows(IllegalArgumentException::class.java) { FileVisibilityRule("id", "") }
        assertThat(FileVisibilityRule("id", " ").pattern).isEqualTo(" ")
    }

    private fun compile(vararg rules: FileVisibilityRule): FileVisibilityMatcher =
        FileVisibilityMatcher.compile(rules.toList())

    private fun rule(
        pattern: String,
        action: FileVisibilityAction = FileVisibilityAction.Hide,
        target: FileVisibilityTarget = FileVisibilityTarget.All,
    ): FileVisibilityRule = FileVisibilityRule(
        id = "$action:$target:$pattern",
        pattern = pattern,
        action = action,
        target = target,
    )
}
