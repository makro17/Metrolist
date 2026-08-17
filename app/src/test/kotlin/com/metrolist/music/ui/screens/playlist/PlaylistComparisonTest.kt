package com.metrolist.music.ui.screens.playlist

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistComparisonTest {
    private fun local(vararg ids: String) = ids.map { LocalSong(it, confirmedOnYouTube = true) }

    @Test
    fun `a playlist that matches its remote reports no difference`() {
        val difference = comparePlaylist(local("a", "b"), listOf("a", "b"))

        assertEquals(emptyList<String>(), difference.onlyLocalIds)
        assertEquals(emptyList<String>(), difference.onlyRemoteIds)
    }

    @Test
    fun `songs missing from the remote are reported in local order`() {
        val difference = comparePlaylist(local("a", "b", "c"), listOf("b"))

        assertEquals(listOf("a", "c"), difference.onlyLocalIds)
    }

    @Test
    fun `songs missing locally are reported in remote order`() {
        val difference = comparePlaylist(local("b"), listOf("c", "b", "a"))

        assertEquals(listOf("c", "a"), difference.onlyRemoteIds)
    }

    @Test
    fun `order alone is not a difference`() {
        val difference = comparePlaylist(local("a", "b"), listOf("b", "a"))

        assertEquals(emptyList<String>(), difference.onlyLocalIds)
        assertEquals(emptyList<String>(), difference.onlyRemoteIds)
    }

    @Test
    fun `a song listed twice on one side is reported once`() {
        val difference = comparePlaylist(local("a", "a"), listOf("b", "b"))

        assertEquals(listOf("a"), difference.onlyLocalIds)
        assertEquals(listOf("b"), difference.onlyRemoteIds)
    }

    @Test
    fun `a duplicated local song that the remote holds is not a difference`() {
        val difference = comparePlaylist(local("a", "a"), listOf("a"))

        assertEquals(emptyList<String>(), difference.onlyLocalIds)
    }

    @Test
    fun `the never confirmed hint covers only local-only songs`() {
        val songs = listOf(
            LocalSong("a", confirmedOnYouTube = false),
            LocalSong("b", confirmedOnYouTube = false),
        )

        val difference = comparePlaylist(songs, listOf("b"))

        assertEquals(setOf("a"), difference.neverConfirmedIds)
    }
}
