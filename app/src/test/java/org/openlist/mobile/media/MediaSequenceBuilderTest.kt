package org.openlist.mobile.media

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.openlist.mobile.core.model.DirectoryListing
import org.openlist.mobile.core.model.FileVisibilityAction
import org.openlist.mobile.core.model.FileVisibilityRule
import org.openlist.mobile.core.model.FileVisibilityTarget
import org.openlist.mobile.core.model.MediaKind
import org.openlist.mobile.core.model.OpenListObject
import java.io.IOException

class MediaSequenceBuilderTest {
    @Test
    fun `build keeps directory order filters kind and includes current exactly once`() = runTest {
        val listing = DirectoryListing(
            content = listOf(
                objectOf("before.bin", type = 3),
                objectOf("selected.MP3"),
                objectOf("selected.MP3", size = 999),
                objectOf("after.flac"),
                objectOf("movie.mp4"),
                objectOf("cover.jpg"),
                objectOf("notes.unknown"),
                objectOf("folder.mp3", isDirectory = true),
            ),
        )
        val builder = MediaSequenceBuilder(
            directorySource = DirectoryMediaSource { listing },
            serverIdentity = { "https://example.test" },
        )

        val sequence = builder.build(
            currentPath = "/music/selected.MP3",
            current = objectOf("selected.MP3", size = 42, modified = "new"),
        )

        assertThat(sequence.kind).isEqualTo(MediaKind.AUDIO)
        assertThat(sequence.items.map(MediaEntry::name))
            .containsExactly("before.bin", "selected.MP3", "after.flac")
            .inOrder()
        assertThat(sequence.currentIndex).isEqualTo(1)
        assertThat(sequence.current.size).isEqualTo(42)
        assertThat(sequence.items.count { it.remotePath == "/music/selected.MP3" }).isEqualTo(1)
        assertThat(sequence.isDirectoryFallback).isFalse()
    }

    @Test
    fun `unknown server type falls back to case insensitive extension`() = runTest {
        val builder = MediaSequenceBuilder(
            directorySource = DirectoryMediaSource {
                DirectoryListing(
                    content = listOf(
                        objectOf("one.JPEG"),
                        objectOf("two.webp"),
                        objectOf("audio.mp3"),
                    ),
                )
            },
            serverIdentity = { "server" },
        )

        val sequence = builder.build("/photos/one.JPEG", objectOf("one.JPEG"))

        assertThat(sequence.kind).isEqualTo(MediaKind.IMAGE)
        assertThat(sequence.items.map(MediaEntry::name)).containsExactly("one.JPEG", "two.webp").inOrder()
    }

    @Test
    fun `video sequence still attaches matching subtitles when visibility rules hide sidecars`() = runTest {
        val listing = DirectoryListing(
            content = listOf(
                objectOf("episode.mkv", type = 4),
                objectOf("episode.srt", type = 4),
                objectOf("episode.zh-CN.ass", type = 4),
                objectOf("next.mkv", type = 3),
                objectOf("next.vtt", type = 4),
                objectOf("unrelated.ssa", type = 4),
                objectOf("episode.txt", type = 4),
            ),
        )
        val builder = MediaSequenceBuilder(
            directorySource = DirectoryMediaSource { listing },
            serverIdentity = { "server" },
            visibilityRules = { listOf(hide("*.srt"), hide("*.ass"), hide("*.vtt")) },
        )

        val sequence = builder.build("/shows/episode.mkv", objectOf("episode.mkv", type = 4))

        assertThat(sequence.kind).isEqualTo(MediaKind.VIDEO)
        assertThat(sequence.items.map(MediaEntry::name)).containsExactly("episode.mkv", "next.mkv").inOrder()
        assertThat(sequence.items[0].subtitles.map(SubtitleEntry::name))
            .containsExactly("episode.srt", "episode.zh-CN.ass").inOrder()
        assertThat(sequence.items[0].subtitles.map(SubtitleEntry::remotePath))
            .containsExactly("/shows/episode.srt", "/shows/episode.zh-CN.ass").inOrder()
        assertThat(sequence.items[1].subtitles.map(SubtitleEntry::name)).containsExactly("next.vtt")
    }

    @Test
    fun `provided siblings build avoids relist and preserves stable queue plus subtitles`() = runTest {
        var listCalls = 0
        val siblings = listOf(
            objectOf("episode.mkv", type = 4, size = 1),
            objectOf("episode.srt", type = 4),
            objectOf("episode.mkv", type = 4, size = 9_999),
            objectOf("next.mkv", type = 3),
            objectOf("cover.jpg", type = 0),
        )
        val builder = MediaSequenceBuilder(
            directorySource = DirectoryMediaSource {
                listCalls += 1
                DirectoryListing(content = siblings)
            },
            serverIdentity = { "server" },
        )

        val sequence = builder.build(
            currentPath = "/shows/episode.mkv",
            current = objectOf("episode.mkv", type = 4, size = 42, modified = "selected"),
            siblings = siblings,
        )

        assertThat(listCalls).isEqualTo(0)
        assertThat(sequence.kind).isEqualTo(MediaKind.VIDEO)
        assertThat(sequence.items.map(MediaEntry::name))
            .containsExactly("episode.mkv", "next.mkv")
            .inOrder()
        assertThat(sequence.currentIndex).isEqualTo(0)
        assertThat(sequence.current.size).isEqualTo(42)
        assertThat(sequence.current.modified).isEqualTo("selected")
        assertThat(sequence.current.subtitles.map(SubtitleEntry::name)).containsExactly("episode.srt")
    }

    @Test
    fun `directory queue snapshots rules before listing and preserves selected item when hidden`() = runTest {
        val siblings = listOf(
            objectOf("before.mp3"),
            objectOf("hidden-one.mp3"),
            objectOf("selected.mp3"),
            objectOf("hidden-two.mp3"),
            objectOf("after.mp3"),
        )
        var rules = listOf(hide("hidden*"), hide("selected*"))
        val listedDirectories = mutableListOf<String>()
        val builder = MediaSequenceBuilder(
            directorySource = DirectoryMediaSource { directory ->
                listedDirectories += directory
                rules = emptyList()
                DirectoryListing(content = siblings)
            },
            serverIdentity = { "server" },
            visibilityRules = { rules },
        )

        val original = builder.build("/music/selected.mp3", objectOf("selected.mp3", size = 42))
        val following = builder.build("/music/selected.mp3", objectOf("selected.mp3", size = 42))

        assertThat(listedDirectories).containsExactly("/music", "/music").inOrder()
        assertThat(original.items.map(MediaEntry::name))
            .containsExactly("before.mp3", "selected.mp3", "after.mp3").inOrder()
        assertThat(original.currentIndex).isEqualTo(1)
        assertThat(original.current.size).isEqualTo(42)
        assertThat(following.items.map(MediaEntry::name))
            .containsExactly("before.mp3", "hidden-one.mp3", "selected.mp3", "hidden-two.mp3", "after.mp3")
            .inOrder()
        assertThat(following.currentIndex).isEqualTo(2)
    }

    @Test
    fun `provided gallery siblings use visibility exceptions and retain the selected image once`() = runTest {
        val builder = MediaSequenceBuilder(
            directorySource = DirectoryMediaSource { error("Provided siblings must not relist") },
            serverIdentity = { "server" },
            visibilityRules = {
                listOf(
                    hide("*.jpg"),
                    FileVisibilityRule("cover", "cover.jpg", action = FileVisibilityAction.Show),
                )
            },
        )

        val sequence = builder.build(
            currentPath = "/photos/selected.jpg",
            current = objectOf("selected.jpg", size = 42),
            siblings = listOf(
                objectOf("hidden.jpg"),
                objectOf("selected.jpg"),
                objectOf("cover.jpg"),
                objectOf("next.png"),
                objectOf("selected.jpg", size = 999),
            ),
        )

        assertThat(sequence.items.map(MediaEntry::name))
            .containsExactly("selected.jpg", "cover.jpg", "next.png").inOrder()
        assertThat(sequence.currentIndex).isEqualTo(0)
        assertThat(sequence.current.size).isEqualTo(42)
        assertThat(sequence.kind).isEqualTo(MediaKind.IMAGE)
    }

    @Test
    fun `selecting media under a hidden directory does not expose its other children`() = runTest {
        val builder = MediaSequenceBuilder(
            directorySource = DirectoryMediaSource {
                DirectoryListing(content = listOf(objectOf("before.mp4"), objectOf("after.mp4")))
            },
            serverIdentity = { "server" },
            visibilityRules = { listOf(hide("private", target = FileVisibilityTarget.Directories)) },
        )

        val sequence = builder.build("/private/videos/selected.mp4", objectOf("selected.mp4"))

        assertThat(sequence.items.map(MediaEntry::remotePath)).containsExactly("/private/videos/selected.mp4")
        assertThat(sequence.currentIndex).isEqualTo(0)
        assertThat(sequence.isDirectoryFallback).isFalse()
    }

    @Test
    fun `misclassified wma enters the same directory audio queue with explicit mime`() = runTest {
        val builder = MediaSequenceBuilder(
            directorySource = DirectoryMediaSource {
                DirectoryListing(
                    content = listOf(
                        objectOf("track.wma", type = 4),
                        objectOf("next.mp3", type = 3),
                        objectOf("movie.mkv", type = 2),
                    ),
                )
            },
            serverIdentity = { "server" },
        )

        val sequence = builder.build("/music/track.wma", objectOf("track.wma", type = 4))

        assertThat(sequence.kind).isEqualTo(MediaKind.AUDIO)
        assertThat(sequence.items.map(MediaEntry::name)).containsExactly("track.wma", "next.mp3").inOrder()
        assertThat(sequence.current.mimeType).isEqualTo("audio/x-ms-wma")
    }

    @Test
    fun `misclassified wmv enters video queue while wma remains audio`() = runTest {
        val builder = MediaSequenceBuilder(
            directorySource = DirectoryMediaSource {
                DirectoryListing(
                    content = listOf(
                        objectOf("legacy.wmv", type = 4),
                        objectOf("next.mkv", type = 3),
                        objectOf("track.wma", type = 2),
                    ),
                )
            },
            serverIdentity = { "server" },
        )

        val sequence = builder.build("/videos/legacy.wmv", objectOf("legacy.wmv", type = 4))

        assertThat(sequence.kind).isEqualTo(MediaKind.VIDEO)
        assertThat(sequence.items.map(MediaEntry::name)).containsExactly("legacy.wmv", "next.mkv").inOrder()
        assertThat(sequence.current.mimeType).isEqualTo("video/x-ms-wmv")
    }

    @Test
    fun `current path extension remains authoritative when details omit the name`() = runTest {
        val builder = MediaSequenceBuilder(
            directorySource = DirectoryMediaSource {
                DirectoryListing(content = listOf(objectOf("legacy.wmv", type = 4)))
            },
            serverIdentity = { "server" },
        )

        val sequence = builder.build("/videos/legacy.wmv", objectOf(name = "", type = 4))

        assertThat(sequence.kind).isEqualTo(MediaKind.VIDEO)
        assertThat(sequence.current.name).isEqualTo("legacy.wmv")
        assertThat(sequence.current.mimeType).isEqualTo("video/x-ms-wmv")
    }

    @Test
    fun `missing current in listing is appended and selected`() = runTest {
        val builder = MediaSequenceBuilder(
            directorySource = DirectoryMediaSource {
                DirectoryListing(content = listOf(objectOf("one.mp3"), objectOf("two.mp3")))
            },
            serverIdentity = { "server" },
        )

        val sequence = builder.build("/music/current.mp3", objectOf("current.mp3"))

        assertThat(sequence.items.map(MediaEntry::name))
            .containsExactly("one.mp3", "two.mp3", "current.mp3")
            .inOrder()
        assertThat(sequence.currentIndex).isEqualTo(2)
    }

    @Test
    fun `list failure falls back to current item`() = runTest {
        val builder = MediaSequenceBuilder(
            directorySource = DirectoryMediaSource { throw IOException("offline") },
            serverIdentity = { "server" },
        )

        val sequence = builder.build("/music/current.mp3", objectOf("current.mp3"))

        assertThat(sequence.items.map(MediaEntry::name)).containsExactly("current.mp3")
        assertThat(sequence.currentIndex).isEqualTo(0)
        assertThat(sequence.isDirectoryFallback).isTrue()
    }

    @Test
    fun `same server and path use different keys for different accounts`() = runTest {
        suspend fun keyFor(account: String) = MediaSequenceBuilder(
            directorySource = DirectoryMediaSource {
                DirectoryListing(content = listOf(objectOf("current.mp3")))
            },
            serverIdentity = { "https://server.test" },
            accountIdentity = { account },
        ).build("/private/current.mp3", objectOf("current.mp3")).current.contentKey

        assertThat(keyFor("alice")).isNotEqualTo(keyFor("bob"))
    }

    @Test(expected = CancellationException::class)
    fun `cancellation is never converted to fallback`() = runTest {
        val builder = MediaSequenceBuilder(
            directorySource = DirectoryMediaSource { throw CancellationException("cancel") },
            serverIdentity = { "server" },
        )

        builder.build("/music/current.mp3", objectOf("current.mp3"))
    }

    private fun objectOf(
        name: String,
        type: Int = 0,
        size: Long = 1,
        modified: String = "revision",
        isDirectory: Boolean = false,
    ) = OpenListObject(
        name = name,
        type = type,
        size = size,
        modified = modified,
        isDirectory = isDirectory,
    )

    private fun hide(
        pattern: String,
        target: FileVisibilityTarget = FileVisibilityTarget.All,
    ) = FileVisibilityRule(id = "$target:$pattern", pattern = pattern, target = target)
}
