package org.openlist.mobile.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openlist.mobile.core.model.FileVisibilityAction
import org.openlist.mobile.core.model.FileVisibilityRule
import org.openlist.mobile.core.model.FileVisibilityTarget
import org.openlist.mobile.core.util.FileVisibilityMatcher

class FileVisibilityPreferencesCodecTest {
    @Test
    fun roundTripPreservesRuleOrderAndLiteralCharacters() {
        val rules = listOf(
            FileVisibilityRule("hide", "*", target = FileVisibilityTarget.Files),
            FileVisibilityRule("exception", "旅行 \\?.mp4", FileVisibilityAction.Show, FileVisibilityTarget.Files),
        )
        val restored = FileVisibilityPreferencesCodec.decode(FileVisibilityPreferencesCodec.encode(rules))
        assertEquals(rules, restored)
        val matcher = FileVisibilityMatcher.compile(restored)
        assertTrue(matcher.isVisible("旅行 ?.mp4", false))
    }

    @Test
    fun invalidOrDuplicateRulesCannotBreakLoadingOrChangeTheRemainingOrder() {
        val encoded = """[
            {"id":"first","pattern":"*.tmp","action":"Hide","target":"Files"},
            {"id":"bad","pattern":"","action":"Hide","target":"All"},
            {"id":"future","pattern":"*","action":"Unknown","target":"All"},
            null,
            {"id":"first","pattern":"*","action":"Show","target":"All"},
            {"id":"last","pattern":"keep.tmp","action":"Show","target":"Files"}
        ]"""
        val restored = FileVisibilityPreferencesCodec.decode(encoded)
        assertEquals(listOf("first", "last"), restored.map { it.id })
        assertTrue(FileVisibilityMatcher.compile(restored).isVisible("keep.tmp", false))
        assertTrue(FileVisibilityPreferencesCodec.decode("invalid JSON").isEmpty())
    }
}
