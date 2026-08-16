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
    fun `a playlist holding none of the songs offers add`() {
        assertEquals(RowAction.Add, rowActionFor(sourceCount = 12, presentCount = 0))
    }

    @Test
    fun `a playlist holding some of the songs still offers add`() {
        assertEquals(RowAction.Add, rowActionFor(sourceCount = 12, presentCount = 3))
    }

    @Test
    fun `a playlist holding every song offers remove`() {
        assertEquals(RowAction.Remove, rowActionFor(sourceCount = 12, presentCount = 12))
    }

    @Test
    fun `a single song already present offers remove`() {
        assertEquals(RowAction.Remove, rowActionFor(sourceCount = 1, presentCount = 1))
    }

    @Test
    fun `no source songs offers add`() {
        assertEquals(RowAction.Add, rowActionFor(sourceCount = 0, presentCount = 0))
    }
}
