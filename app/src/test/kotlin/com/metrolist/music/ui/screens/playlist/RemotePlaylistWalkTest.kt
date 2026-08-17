package com.metrolist.music.ui.screens.playlist

import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.pages.PlaylistContinuationPage
import com.metrolist.innertube.pages.PlaylistPage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RemotePlaylistWalkTest {
    private fun song(id: String) = SongItem(
        id = id,
        title = id,
        artists = emptyList(),
        thumbnail = "",
    )

    private fun page(songs: List<SongItem>, continuation: String?) = PlaylistPage(
        playlist = PlaylistItem(
            id = "PL",
            title = "test",
            author = null,
            songCountText = null,
            thumbnail = null,
            playEndpoint = null,
            shuffleEndpoint = null,
            radioEndpoint = null,
        ),
        songs = songs,
        songsContinuation = continuation,
        continuation = null,
    )

    @Test
    fun `a single page playlist is complete`() = runBlocking {
        val result = walkRemotePlaylist(
            firstPage = { Result.success(page(listOf(song("a"), song("b")), continuation = null)) },
            nextPage = { error("must not be called") },
        )

        assertEquals(listOf("a", "b"), (result as RemotePlaylist.Complete).songs.map { it.id })
    }

    @Test
    fun `continuations are followed and concatenated in order`() = runBlocking {
        val result = walkRemotePlaylist(
            firstPage = { Result.success(page(listOf(song("a")), continuation = "c1")) },
            nextPage = { token ->
                when (token) {
                    "c1" -> Result.success(PlaylistContinuationPage(listOf(song("b")), "c2"))
                    "c2" -> Result.success(PlaylistContinuationPage(listOf(song("c")), null))
                    else -> error("unexpected token $token")
                }
            },
        )

        assertEquals(listOf("a", "b", "c"), (result as RemotePlaylist.Complete).songs.map { it.id })
    }

    @Test
    fun `progress is reported as songs arrive`() = runBlocking {
        val seen = mutableListOf<Int>()

        walkRemotePlaylist(
            firstPage = { Result.success(page(listOf(song("a")), continuation = "c1")) },
            nextPage = { Result.success(PlaylistContinuationPage(listOf(song("b")), null)) },
            onProgress = { seen += it },
        )

        assertEquals(listOf(1, 2), seen)
    }

    @Test
    fun `a walk that runs out of requests is truncated, not complete`() = runBlocking {
        val result = walkRemotePlaylist(
            firstPage = { Result.success(page(listOf(song("a")), continuation = "c")) },
            nextPage = { Result.success(PlaylistContinuationPage(listOf(song("b")), "c-again")) },
            maxRequests = 1,
        )

        assertTrue(result is RemotePlaylist.Truncated)
    }

    @Test
    fun `a repeated continuation token is truncated, not an endless loop`() = runBlocking {
        val result = walkRemotePlaylist(
            firstPage = { Result.success(page(listOf(song("a")), continuation = "loop")) },
            nextPage = { Result.success(PlaylistContinuationPage(listOf(song("b")), "loop")) },
        )

        assertTrue(result is RemotePlaylist.Truncated)
    }

    @Test
    fun `a failed first page is a failure`() = runBlocking {
        val boom = IOException("no network")

        val result = walkRemotePlaylist(
            firstPage = { Result.failure(boom) },
            nextPage = { error("must not be called") },
        )

        assertEquals(boom, (result as RemotePlaylist.Failed).error)
    }

    @Test
    fun `a failed continuation is a failure, never a short playlist`() = runBlocking {
        val result = walkRemotePlaylist(
            firstPage = { Result.success(page(listOf(song("a")), continuation = "c1")) },
            nextPage = { Result.failure(IOException("dropped")) },
        )

        assertTrue(result is RemotePlaylist.Failed)
    }
}
