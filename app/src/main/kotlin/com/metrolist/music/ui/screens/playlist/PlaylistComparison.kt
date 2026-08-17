/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.playlist

/** A row of the local playlist, reduced to what the comparison needs. */
data class LocalSong(
    val id: String,
    val confirmedOnYouTube: Boolean,
)

/**
 * What the two sides of a playlist do not share. Ids only: the screen already holds the local rows
 * and the fetched remote items, and looks the details up from them.
 */
data class PlaylistDifference(
    val onlyLocalIds: List<String>,
    val onlyRemoteIds: List<String>,
    /**
     * The local-only songs that were added on this device and never came back confirmed from
     * YouTube. A hint for the user, never grounds for deciding on their behalf: a song can equally
     * be local-only because they deleted it on the YouTube side.
     */
    val neverConfirmedIds: Set<String>,
)

/**
 * Compares a playlist against what YouTube returned for it.
 *
 * Membership only. Two sides holding the same songs in a different order are in sync, and a song
 * listed twice on one side is reported once, because every action the screen offers is about a song
 * being present or absent rather than about where it sits.
 */
fun comparePlaylist(
    local: List<LocalSong>,
    remoteIds: List<String>,
): PlaylistDifference {
    val remote = remoteIds.toSet()
    val localIds = local.mapTo(mutableSetOf()) { it.id }

    val onlyLocal = local.map { it.id }.distinct().filterNot { it in remote }
    val onlyRemote = remoteIds.distinct().filterNot { it in localIds }
    val onlyLocalSet = onlyLocal.toSet()

    return PlaylistDifference(
        onlyLocalIds = onlyLocal,
        onlyRemoteIds = onlyRemote,
        neverConfirmedIds = local
            .filter { !it.confirmedOnYouTube && it.id in onlyLocalSet }
            .mapTo(mutableSetOf()) { it.id },
    )
}
