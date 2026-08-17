/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Playlist
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.ui.screens.playlist.LocalSong
import com.metrolist.music.ui.screens.playlist.RemotePlaylist
import com.metrolist.music.ui.screens.playlist.comparePlaylist
import com.metrolist.music.ui.screens.playlist.walkRemotePlaylist
import com.metrolist.music.utils.SyncUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One row of either tab, carrying everything the list item draws and every action needs. */
data class ComparisonRow(
    val id: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val neverConfirmed: Boolean,
    val setVideoId: String?,
)

sealed interface ComparisonState {
    /** The local guess, shown while YouTube is still being read. Its actions stay disabled. */
    data class Guessing(val onlyLocal: List<ComparisonRow>) : ComparisonState

    data class Ready(
        val onlyLocal: List<ComparisonRow>,
        val onlyRemote: List<ComparisonRow>,
    ) : ComparisonState

    data object Truncated : ComparisonState

    data class Failed(val error: Throwable) : ComparisonState
}

@HiltViewModel
class PlaylistComparisonViewModel
@Inject
constructor(
    private val database: MusicDatabase,
    private val syncUtils: SyncUtils,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val playlistId = savedStateHandle.get<String>("playlistId")!!

    val playlist: StateFlow<Playlist?> =
        database.playlist(playlistId).stateIn(viewModelScope, SharingStarted.Lazily, null)

    private val _state = MutableStateFlow<ComparisonState>(ComparisonState.Guessing(emptyList()))
    val state: StateFlow<ComparisonState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(0)
    val progress: StateFlow<Int> = _progress.asStateFlow()

    private val _expectedTotal = MutableStateFlow<Int?>(null)
    val expectedTotal: StateFlow<Int?> = _expectedTotal.asStateFlow()

    /** The songs of the last complete read, so an action can reach a remote song's setVideoId. */
    private var remoteSongs: List<SongItem>? = null

    init {
        refresh()
    }

    /** Reads the playlist from YouTube again and compares against it. */
    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val target = database.playlist(playlistId).first() ?: return@launch
            val browseId = target.playlist.browseId ?: return@launch

            _progress.value = 0
            _expectedTotal.value = target.playlist.remoteSongCount
            remoteSongs = null
            _state.value = ComparisonState.Guessing(guessOnlyLocal())

            _state.value = when (
                val remote = walkRemotePlaylist(
                    firstPage = { YouTube.playlist(browseId) },
                    nextPage = { YouTube.playlistContinuation(it) },
                    onProgress = { _progress.value = it },
                )
            ) {
                is RemotePlaylist.Failed -> ComparisonState.Failed(remote.error)
                RemotePlaylist.Truncated -> ComparisonState.Truncated
                is RemotePlaylist.Complete -> {
                    remoteSongs = remote.songs
                    compareAgainst(remote.songs)
                }
            }
        }
    }

    /**
     * Compares again against the songs already read, for changes made on this device. A local edit
     * cannot have altered the remote side, so walking it again would cost the user a second wait
     * for an answer we already hold.
     */
    private fun recompare() {
        val remote = remoteSongs ?: return
        _state.value = compareAgainst(remote)
    }

    /**
     * What the first tab shows before YouTube answers: the rows this device added and never saw
     * confirmed. A guess, which is why the screen keeps its actions disabled until the real
     * comparison replaces it.
     */
    private fun guessOnlyLocal(): List<ComparisonRow> {
        val unconfirmed = database.playlistSongMaps(playlistId, 0)
            .filter { it.setVideoId == null }
            .map { it.songId }
            .toSet()
        return localRows(unconfirmed, neverConfirmed = unconfirmed)
    }

    private fun compareAgainst(remote: List<SongItem>): ComparisonState.Ready {
        val maps = database.playlistSongMaps(playlistId, 0)
        val difference = comparePlaylist(
            local = maps.map { LocalSong(it.songId, confirmedOnYouTube = it.setVideoId != null) },
            remoteIds = remote.map { it.id },
        )

        val remoteById = remote.associateBy { it.id }
        val order = difference.onlyLocalIds.withIndex().associate { (index, id) -> id to index }

        return ComparisonState.Ready(
            onlyLocal = localRows(difference.onlyLocalIds.toSet(), difference.neverConfirmedIds)
                .sortedBy { order[it.id] ?: Int.MAX_VALUE },
            onlyRemote = difference.onlyRemoteIds.mapNotNull { id ->
                remoteById[id]?.let { item ->
                    ComparisonRow(
                        id = item.id,
                        title = item.title,
                        subtitle = item.artists.joinToString { artist -> artist.name },
                        thumbnailUrl = item.thumbnail,
                        neverConfirmed = false,
                        setVideoId = item.setVideoId,
                    )
                }
            },
        )
    }

    private fun localRows(ids: Set<String>, neverConfirmed: Set<String>): List<ComparisonRow> {
        if (ids.isEmpty()) return emptyList()
        return database.playlistSongMaps(playlistId, 0)
            .filter { it.songId in ids }
            .distinctBy { it.songId }
            .mapNotNull { map ->
                database.getSongByIdBlocking(map.songId)?.let { song ->
                    ComparisonRow(
                        id = song.id,
                        title = song.song.title,
                        subtitle = song.artists.joinToString { artist -> artist.name },
                        thumbnailUrl = song.song.thumbnailUrl,
                        neverConfirmed = song.id in neverConfirmed,
                        setVideoId = map.setVideoId,
                    )
                }
            }
    }

    fun uploadToYouTube(ids: List<String>) {
        val target = playlist.value ?: return
        val browseId = target.playlist.browseId ?: return
        // Queued on syncScope behind the edit throttle, so the row stays put until the next read.
        syncUtils.addSongsToPlaylist(browseId, target.id, target.playlist.name, ids)
    }

    fun removeFromYouTube(rows: List<ComparisonRow>) {
        val target = playlist.value ?: return
        val browseId = target.playlist.browseId ?: return
        syncUtils.removeSongsFromPlaylist(
            browseId,
            target.id,
            target.playlist.name,
            rows.map { it.id to it.setVideoId },
        )
    }

    fun removeLocally(ids: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            val maps = database.playlistSongMaps(playlistId, ids).sortedByDescending { it.position }
            if (maps.isEmpty()) return@launch

            // Descending order matters: move() shifts only the positions above the row it moves, so
            // the rows still pending keep the positions just read.
            database.transaction {
                maps.forEach { map ->
                    move(map.playlistId, map.position, Int.MAX_VALUE)
                    delete(map.copy(position = Int.MAX_VALUE))
                }
            }
            recompare()
        }
    }

    fun addLocally(ids: List<String>) {
        val target = playlist.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val wanted = ids.toSet().let { wantedIds ->
                remoteSongs.orEmpty().filter { it.id in wantedIds }
            }
            if (wanted.isEmpty()) return@launch

            database.transaction {
                wanted.forEach { insert(it.toMediaMetadata()) }
                addSongsToPlaylist(target, wanted.map { it.id to it.setVideoId })
            }
            recompare()
        }
    }
}
