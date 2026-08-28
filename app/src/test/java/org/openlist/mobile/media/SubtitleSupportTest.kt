package org.openlist.mobile.media

import androidx.media3.common.MimeTypes
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.openlist.mobile.core.model.OpenListObject

class SubtitleSupportTest {
    @Test
    fun `supported subtitle extensions map to Media3 mime types case insensitively`() {
        assertThat(SubtitleTypeDetector.mimeType("one.SRT")).isEqualTo(MimeTypes.APPLICATION_SUBRIP)
        assertThat(SubtitleTypeDetector.mimeType("one.webvtt")).isEqualTo(MimeTypes.TEXT_VTT)
        assertThat(SubtitleTypeDetector.mimeType("one.ASS")).isEqualTo(MimeTypes.TEXT_SSA)
        assertThat(SubtitleTypeDetector.mimeType("one.ssa")).isEqualTo(MimeTypes.TEXT_SSA)
        assertThat(SubtitleTypeDetector.mimeType("one.dfxp")).isEqualTo(MimeTypes.APPLICATION_TTML)
        assertThat(SubtitleTypeDetector.mimeType("one.txt")).isNull()
    }

    @Test
    fun `matcher accepts exact and language qualified basenames but rejects neighbors`() {
        val matches = DirectorySubtitleMatcher.match(
            videoName = "Film.Final.MKV",
            directory = "/shows/private",
            candidates = listOf(
                file("film.final.srt"),
                file("Film.Final.zh-CN.ass"),
                file("Film.Final.webvtt"),
                file("Film.srt"),
                file("Film.Final2.srt"),
                file("Film.Final.txt"),
                file("../Film.Final.vtt"),
                file("Film.Final.vtt", isDirectory = true),
            ),
        )

        assertThat(matches.map(SubtitleEntry::name))
            .containsExactly("film.final.srt", "Film.Final.zh-CN.ass", "Film.Final.webvtt")
            .inOrder()
        assertThat(matches.map(SubtitleEntry::remotePath))
            .containsExactly(
                "/shows/private/film.final.srt",
                "/shows/private/Film.Final.zh-CN.ass",
                "/shows/private/Film.Final.webvtt",
            ).inOrder()
    }

    @Test
    fun `default selection prefers a sole exact basename and never guesses among languages`() {
        val plain = subtitle("episode.srt")
        val english = subtitle("episode.en.vtt")
        val chinese = subtitle("episode.zh-CN.ass")

        assertThat(defaultSubtitleIndex("episode.mkv", listOf(chinese, plain, english))).isEqualTo(1)
        assertThat(defaultSubtitleIndex("episode.mkv", listOf(chinese))).isEqualTo(0)
        assertThat(defaultSubtitleIndex("episode.mkv", listOf(chinese, english))).isNull()
        assertThat(defaultSubtitleIndex("episode.mkv", listOf(plain, subtitle("episode.vtt")))).isNull()
    }

    private fun file(name: String, isDirectory: Boolean = false) = OpenListObject(
        name = name,
        isDirectory = isDirectory,
        type = if (isDirectory) 1 else 4,
    )
    private fun subtitle(name: String) = SubtitleEntry(
        remotePath = "/shows/$name",
        name = name,
        mimeType = SubtitleTypeDetector.mimeType(name)!!,
    )
}
