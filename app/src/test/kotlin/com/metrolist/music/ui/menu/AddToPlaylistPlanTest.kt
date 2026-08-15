package com.metrolist.music.ui.menu

import org.junit.Assert.assertEquals
import org.junit.Test

class AddToPlaylistPlanTest {
    private val songs = listOf("s1", "s2", "s3")

    @Test
    fun `without skipping every selected playlist gets every song`() {
        val plan = planPlaylistAdditions(
            selectedPlaylistIds = listOf("a", "b"),
            songIds = songs,
            duplicatesByPlaylist = mapOf("a" to listOf("s1")),
            skipDuplicates = false,
        )

        assertEquals(
            listOf(
                PlaylistAddition("a", songs),
                PlaylistAddition("b", songs),
            ),
            plan,
        )
    }

    @Test
    fun `skipping removes duplicates only from the playlist that has them`() {
        val plan = planPlaylistAdditions(
            selectedPlaylistIds = listOf("a", "b"),
            songIds = songs,
            duplicatesByPlaylist = mapOf("a" to listOf("s1")),
            skipDuplicates = true,
        )

        assertEquals(
            listOf(
                PlaylistAddition("a", listOf("s2", "s3")),
                PlaylistAddition("b", songs),
            ),
            plan,
        )
    }

    @Test
    fun `a playlist that already has every song is dropped when skipping`() {
        val plan = planPlaylistAdditions(
            selectedPlaylistIds = listOf("a", "b"),
            songIds = songs,
            duplicatesByPlaylist = mapOf("a" to songs),
            skipDuplicates = true,
        )

        assertEquals(listOf(PlaylistAddition("b", songs)), plan)
    }

    @Test
    fun `a playlist that already has every song is kept when adding anyway`() {
        val plan = planPlaylistAdditions(
            selectedPlaylistIds = listOf("a"),
            songIds = songs,
            duplicatesByPlaylist = mapOf("a" to songs),
            skipDuplicates = false,
        )

        assertEquals(listOf(PlaylistAddition("a", songs)), plan)
    }

    @Test
    fun `selection order is preserved`() {
        val plan = planPlaylistAdditions(
            selectedPlaylistIds = listOf("c", "a", "b"),
            songIds = listOf("s1"),
            duplicatesByPlaylist = emptyMap(),
            skipDuplicates = true,
        )

        assertEquals(listOf("c", "a", "b"), plan.map { it.playlistId })
    }

    @Test
    fun `an empty selection produces an empty plan`() {
        val plan = planPlaylistAdditions(
            selectedPlaylistIds = emptyList(),
            songIds = songs,
            duplicatesByPlaylist = emptyMap(),
            skipDuplicates = true,
        )

        assertEquals(emptyList<PlaylistAddition>(), plan)
    }

    @Test
    fun `duplicates present in several playlists are counted once`() {
        val summary = summarizeDuplicates(
            selectedPlaylistIds = listOf("a", "b", "c"),
            duplicatesByPlaylist = mapOf(
                "a" to listOf("s1", "s2"),
                "b" to listOf("s1"),
                "c" to emptyList(),
            ),
        )

        assertEquals(DuplicateSummary(songCount = 2, playlistCount = 2), summary)
    }

    @Test
    fun `duplicates in unselected playlists are ignored`() {
        val summary = summarizeDuplicates(
            selectedPlaylistIds = listOf("a"),
            duplicatesByPlaylist = mapOf(
                "a" to emptyList(),
                "z" to listOf("s1", "s2", "s3"),
            ),
        )

        assertEquals(DuplicateSummary(songCount = 0, playlistCount = 0), summary)
    }

    @Test
    fun `a playlist missing from the duplicate map has no duplicates`() {
        val summary = summarizeDuplicates(
            selectedPlaylistIds = listOf("a", "b"),
            duplicatesByPlaylist = emptyMap(),
        )

        assertEquals(DuplicateSummary(songCount = 0, playlistCount = 0), summary)
    }
}
