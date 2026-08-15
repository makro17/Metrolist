/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.menu

/** A playlist and the song ids that should actually be inserted into it. */
data class PlaylistAddition(
    val playlistId: String,
    val songIds: List<String>,
)

/** How many distinct songs are already present, and in how many of the selected playlists. */
data class DuplicateSummary(
    val songCount: Int,
    val playlistCount: Int,
)

fun summarizeDuplicates(
    selectedPlaylistIds: List<String>,
    duplicatesByPlaylist: Map<String, List<String>>,
): DuplicateSummary {
    val affected = selectedPlaylistIds.filter { !duplicatesByPlaylist[it].isNullOrEmpty() }
    val distinctSongs = affected.flatMapTo(mutableSetOf()) { duplicatesByPlaylist[it].orEmpty() }
    return DuplicateSummary(songCount = distinctSongs.size, playlistCount = affected.size)
}

fun planPlaylistAdditions(
    selectedPlaylistIds: List<String>,
    songIds: List<String>,
    duplicatesByPlaylist: Map<String, List<String>>,
    skipDuplicates: Boolean,
): List<PlaylistAddition> =
    selectedPlaylistIds.mapNotNull { playlistId ->
        val idsToAdd =
            if (skipDuplicates) {
                val duplicates = duplicatesByPlaylist[playlistId].orEmpty().toSet()
                songIds.filterNot { it in duplicates }
            } else {
                songIds
            }
        idsToAdd.takeIf { it.isNotEmpty() }?.let { PlaylistAddition(playlistId, it) }
    }
