/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.playlist

import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.pages.PlaylistContinuationPage
import com.metrolist.innertube.pages.PlaylistPage

/**
 * The outcome of reading a playlist from YouTube.
 *
 * A comparison is only honest against a complete read, so a partial one is a separate outcome
 * rather than a shorter list: songs missing from a truncated read would be reported as local only,
 * and uploading them would put a second copy of each on YouTube.
 */
sealed interface RemotePlaylist {
    data class Complete(val songs: List<SongItem>) : RemotePlaylist

    data object Truncated : RemotePlaylist

    data class Failed(val error: Throwable) : RemotePlaylist
}

/**
 * Walks a playlist's pages, reporting how many songs have arrived so far.
 *
 * The page fetchers are parameters so the walk can be exercised without a network; the caller
 * passes YouTube.playlist and YouTube.playlistContinuation. This exists rather than reusing
 * `completed()` because that helper stops after a fixed number of continuations and returns what it
 * has, with no way to tell a whole playlist from a clipped one.
 */
suspend fun walkRemotePlaylist(
    firstPage: suspend () -> Result<PlaylistPage>,
    nextPage: suspend (String) -> Result<PlaylistContinuationPage>,
    maxRequests: Int = 200,
    onProgress: (Int) -> Unit = {},
): RemotePlaylist {
    val page = firstPage().getOrElse { return RemotePlaylist.Failed(it) }

    val songs = page.songs.toMutableList()
    onProgress(songs.size)

    var continuation = page.songsContinuation
    val seen = mutableSetOf<String>()
    var requests = 0

    while (continuation != null) {
        // A token that repeats means YouTube is handing back the same page, and there is no way to
        // tell how much is missing beyond it. Both cases stop the walk short, and stopping short is
        // never a result.
        if (requests >= maxRequests || !seen.add(continuation)) {
            return RemotePlaylist.Truncated
        }
        requests++

        val next = nextPage(continuation).getOrElse { return RemotePlaylist.Failed(it) }
        songs += next.songs
        onProgress(songs.size)
        continuation = next.continuation
    }

    return RemotePlaylist.Complete(songs)
}
