/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.menu

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.metrolist.innertube.utils.parseCookieString
import com.metrolist.music.LocalDatabase
import com.metrolist.music.R
import com.metrolist.music.constants.AddToPlaylistSortDescendingKey
import com.metrolist.music.constants.AddToPlaylistSortTypeKey
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.constants.ListThumbnailSize
import com.metrolist.music.constants.PlaylistSortType
import com.metrolist.music.db.entities.Playlist
import com.metrolist.music.ui.component.CreatePlaylistDialog
import com.metrolist.music.ui.component.DefaultDialog
import com.metrolist.music.ui.component.ListDialog
import com.metrolist.music.ui.component.ListItem
import com.metrolist.music.ui.component.PlaylistListItem
import com.metrolist.music.ui.component.SortHeader
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.viewmodels.PlaylistsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.withContext
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.FilterChip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.FilterChipDefaults
import com.metrolist.music.LocalSyncUtils

@Composable
fun AddToPlaylistDialog(
    isVisible: Boolean,
    allowSyncing: Boolean = true,
    initialTextFieldValue: String? = null,
    onGetSong: suspend (Playlist) -> List<String>, // list of song ids. Songs should be inserted to database in this function.
    onGetSongIds: (suspend () -> List<String>)? = null,
    onDismiss: () -> Unit,
    viewModel: PlaylistsViewModel = hiltViewModel()
) {
    val database = LocalDatabase.current
    val syncUtils = LocalSyncUtils.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val (sortType, onSortTypeChange) = rememberEnumPreference(
        AddToPlaylistSortTypeKey,
        PlaylistSortType.NAME
    )
    val (sortDescending, onSortDescendingChange) = rememberPreference(
        AddToPlaylistSortDescendingKey,
        false
    )
    val playlists by viewModel.allPlaylists.collectAsStateWithLifecycle()
    val (innerTubeCookie) = rememberPreference(InnerTubeCookieKey, "")
    val isLoggedIn = remember(innerTubeCookie) {
        "SAPISID" in parseCookieString(innerTubeCookie)
    }
    var showCreatePlaylistDialog by rememberSaveable {
        mutableStateOf(false)
    }

    var songIds by remember {
        mutableStateOf<List<String>?>(null)
    }
    var presentCounts by remember {
        mutableStateOf<Map<String, Int>>(emptyMap())
    }

    // Snapshots of what an action was started on, so a warning acts on that and not on state the
    // list may have moved underneath it.
    var pendingAdd by remember {
        mutableStateOf<Pair<Playlist, List<String>>?>(null)
    }
    var pendingRemove by remember {
        mutableStateOf<Playlist?>(null)
    }

    suspend fun toast(message: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    suspend fun applyAdd(
        target: Playlist,
        ids: List<String>,
        duplicates: List<String>,
        skipDuplicates: Boolean,
    ) {
        val added = planPlaylistAdditions(
            selectedPlaylistIds = listOf(target.id),
            songIds = ids,
            duplicatesByPlaylist = mapOf(target.id to duplicates),
            skipDuplicates = skipDuplicates,
        ).firstOrNull()?.songIds ?: return

        database.addSongsToPlaylist(target, added.map { it to null }, prepend = true)
        target.playlist.browseId?.let { browseId ->
            syncUtils.addSongsToPlaylist(browseId, target.id, target.playlist.name, added)
        }
        toast(context.resources.getQuantityString(R.plurals.n_songs_added, added.size, added.size))
    }

    fun startAdd(target: Playlist) {
        val ids = songIds ?: return
        coroutineScope.launch(Dispatchers.IO) {
            onGetSong(target)
            val duplicates = database.playlistDuplicates(target.id, ids)
            if (duplicates.isNotEmpty()) {
                pendingAdd = target to duplicates
            } else {
                applyAdd(target, ids, duplicates = emptyList(), skipDuplicates = false)
            }
        }
    }

    fun applyRemove(target: Playlist) {
        val ids = songIds ?: return
        coroutineScope.launch(Dispatchers.IO) {
            // Read before deleting: the setVideoId lives on the row about to disappear.
            val maps = database.playlistSongMaps(target.id, ids)
                .sortedByDescending { it.position }
            if (maps.isEmpty()) return@launch

            // Descending order matters: move() shifts only the positions above the row it moves, so
            // the rows still pending keep the positions just read.
            database.transaction {
                maps.forEach { map ->
                    move(map.playlistId, map.position, Int.MAX_VALUE)
                    delete(map.copy(position = Int.MAX_VALUE))
                }
            }

            target.playlist.browseId?.let { browseId ->
                syncUtils.removeSongsFromPlaylist(
                    browseId,
                    target.id,
                    target.playlist.name,
                    maps.map { it.songId to it.setVideoId },
                )
            }
            toast(
                context.resources.getQuantityString(
                    R.plurals.n_songs_removed,
                    maps.size,
                    maps.size,
                )
            )
        }
    }

    LaunchedEffect(isVisible, playlists.isEmpty()) {
        if (!isVisible || playlists.isEmpty()) return@LaunchedEffect
        if (songIds != null) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            songIds = onGetSongIds?.invoke() ?: onGetSong(playlists.first())
        }
    }
    LaunchedEffect(isVisible, songIds, playlists) {
        if (!isVisible) {
            presentCounts = emptyMap()
            return@LaunchedEffect
        }
        val ids = songIds ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            presentCounts = playlists.associate { playlist ->
                playlist.id to database.playlistDuplicates(playlist.id, ids).size
            }
        }
    }

    if (isVisible) {
        ListDialog(
            onDismiss = onDismiss,
        ) {
            item {
                val interactionSource = remember { MutableInteractionSource() }
                val isPressed by interactionSource.collectIsPressedAsState()
                val scale by animateFloatAsState(
                    targetValue = if (isPressed) 0.7f else 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    ),
                    label = "buttonScale"
                )
                FilledTonalButton(
                    onClick = { showCreatePlaylistDialog = true},
                    shape = RoundedCornerShape(50),
                    interactionSource = interactionSource,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .graphicsLayer { scaleX = scale; scaleY = scale }
                ) {
                    Icon(
                        painter = painterResource(R.drawable.add),
                        contentDescription = null,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(20.dp)
                    )
                    Text(
                        text = stringResource(R.string.create_playlist),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            if (playlists.isNotEmpty()) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            PlaylistSortType.entries.forEach { type ->
                                val selected = sortType == type
                                FilterChip(
                                    selected = selected,
                                    onClick = { onSortTypeChange(type) },
                                    shape = RoundedCornerShape(50),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = selected,
                                        borderWidth = 0.dp,
                                        selectedBorderWidth = 0.dp,
                                    ),
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    ),
                                    label = {
                                        Text(
                                            text = stringResource(when (type) {
                                                PlaylistSortType.CREATE_DATE  -> R.string.sort_by_create_date
                                                PlaylistSortType.NAME         -> R.string.sort_by_name
                                                PlaylistSortType.SONG_COUNT   -> R.string.sort_by_song_count
                                                PlaylistSortType.LAST_UPDATED -> R.string.sort_by_last_updated
                                            }),
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        )
                                    }
                                )
                            }
                        }

                        val arrowBg by animateColorAsState(
                            targetValue = if (sortDescending) MaterialTheme.colorScheme.tertiaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                            label = "arrowBg"
                        )
                        val arrowFg by animateColorAsState(
                            targetValue = if (sortDescending) MaterialTheme.colorScheme.onTertiaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                            label = "arrowFg"
                        )
                        IconToggleButton(
                            checked = sortDescending,
                            onCheckedChange = { onSortDescendingChange(it) },
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(arrowBg)
                                .size(36.dp)
                        ) {
                            Icon(
                                painter = painterResource(
                                    if (sortDescending) R.drawable.arrow_downward else R.drawable.arrow_upward
                                ),
                                contentDescription = stringResource(
                                    if (sortDescending) R.string.sort_descending else R.string.sort_ascending
                                ),
                                tint = arrowFg,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            items(playlists) { playlist ->
                val presentCount = presentCounts[playlist.id] ?: 0
                val action = rowActionFor(
                    sourceCount = songIds?.size ?: 0,
                    presentCount = presentCount,
                )
                val rowBg by animateColorAsState(
                    targetValue = if (presentCount > 0)
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    else Color.Transparent,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    label = "playlistBg"
                )

                fun act() {
                    when (action) {
                        RowAction.Add -> startAdd(playlist)
                        RowAction.Remove -> pendingRemove = playlist
                    }
                }

                PlaylistListItem(
                    playlist = playlist,
                    trailingContent = {
                        IconButton(onClick = { act() }) {
                            Icon(
                                painter = painterResource(
                                    if (action == RowAction.Add) R.drawable.add else R.drawable.remove
                                ),
                                contentDescription = stringResource(
                                    if (action == RowAction.Add) R.string.add_to_playlist
                                    else R.string.remove_from_playlist
                                ),
                            )
                        }
                    },
                    modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(rowBg)
                    .clickable { act() }
                )
            }
        }
    }

    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreatePlaylistDialog = false },
            initialTextFieldValue = initialTextFieldValue,
            allowSyncing = allowSyncing
        )
    }

    // duplicate songs warning, for the one playlist the add was started on
    pendingAdd?.let { (target, duplicates) ->
        fun apply(skipDuplicates: Boolean) {
            val ids = songIds.orEmpty()
            pendingAdd = null
            coroutineScope.launch(Dispatchers.IO) {
                applyAdd(target, ids, duplicates, skipDuplicates)
            }
        }

        DefaultDialog(
            title = { Text(stringResource(R.string.duplicates)) },
            buttons = {
                TextButton(onClick = { apply(skipDuplicates = true) }) {
                    Text(stringResource(R.string.skip_duplicates))
                }

                TextButton(onClick = { apply(skipDuplicates = false) }) {
                    Text(stringResource(R.string.add_anyway))
                }

                TextButton(onClick = { pendingAdd = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            onDismiss = { pendingAdd = null }
        ) {
            Text(
                text = if (duplicates.size == 1) {
                    stringResource(R.string.duplicates_description_single)
                } else {
                    stringResource(R.string.duplicates_description_multiple, duplicates.size)
                },
                textAlign = TextAlign.Start,
                modifier = Modifier.align(Alignment.Start)
            )
        }
    }

    pendingRemove?.let { target ->
        val count = songIds?.size ?: 0
        DefaultDialog(
            title = { Text(stringResource(R.string.remove_from_playlist)) },
            buttons = {
                TextButton(
                    onClick = {
                        pendingRemove = null
                        applyRemove(target)
                    }
                ) {
                    Text(stringResource(R.string.remove))
                }

                TextButton(onClick = { pendingRemove = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            onDismiss = { pendingRemove = null }
        ) {
            Text(
                text = buildString {
                    append(
                        pluralStringResource(
                            R.plurals.remove_n_songs_confirm,
                            count,
                            count,
                            target.playlist.name,
                        )
                    )
                    if (target.playlist.browseId != null) {
                        append(" ")
                        append(stringResource(R.string.remove_also_from_youtube))
                    }
                },
                textAlign = TextAlign.Start,
                modifier = Modifier.align(Alignment.Start)
            )
        }
    }
}
