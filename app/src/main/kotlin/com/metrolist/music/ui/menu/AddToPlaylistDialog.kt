/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.menu

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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

    var showDuplicateDialog by remember {
        mutableStateOf(false)
    }
    var songIds by remember {
        mutableStateOf<List<String>?>(null)
    }
    var playlistsContainingSong by remember {
        mutableStateOf<Set<String>>(emptySet())
    }
    var selectedPlaylistIds by remember {
        mutableStateOf<Set<String>>(emptySet())
    }

    // Snapshot of what was confirmed, so the duplicate warning acts on that and not on a
    // selection the user may still be editing behind it.
    var pendingTargets by remember {
        mutableStateOf<List<Playlist>>(emptyList())
    }
    var pendingDuplicates by remember {
        mutableStateOf<Map<String, List<String>>>(emptyMap())
    }
    var duplicateSummary by remember {
        mutableStateOf(DuplicateSummary(songCount = 0, playlistCount = 0))
    }

    fun addSongsLocally(targetPlaylist: Playlist, ids: List<String>) {
        database.addSongsToPlaylist(targetPlaylist, ids.map { it to null }, prepend = true)
    }

    suspend fun uploadSongs(targetPlaylist: Playlist, ids: List<String>) {
        val browseId = targetPlaylist.playlist.browseId ?: return
        ids.forEach { songId ->
            syncUtils.addToPlaylist(browseId, targetPlaylist.id, songId)
        }
    }

    // Every local row is committed before onLocalWritesDone dismisses the dialog, because
    // dismissing it cancels this composition's scope and only the uploads can survive being cut
    // short.
    suspend fun applyAdditions(
        targets: List<Playlist>,
        additions: List<PlaylistAddition>,
        onLocalWritesDone: () -> Unit,
    ) {
        val byId = targets.associateBy { it.id }
        additions.forEach { addition ->
            byId[addition.playlistId]?.let { addSongsLocally(it, addition.songIds) }
        }
        onLocalWritesDone()
        additions.forEach { addition ->
            byId[addition.playlistId]?.let { uploadSongs(it, addition.songIds) }
        }
    }

    fun confirmSelection() {
        val targets = playlists.filter { it.id in selectedPlaylistIds }
        if (targets.isEmpty()) return
        coroutineScope.launch(Dispatchers.IO) {
            targets.forEach { playlist ->
                val ids = onGetSong(playlist)
                if (songIds == null) songIds = ids
            }
            val ids = songIds ?: return@launch
            val duplicatesByPlaylist =
                targets.associate { it.id to database.playlistDuplicates(it.id, ids) }
            val summary = summarizeDuplicates(targets.map { it.id }, duplicatesByPlaylist)
            if (summary.songCount > 0) {
                pendingTargets = targets
                pendingDuplicates = duplicatesByPlaylist
                duplicateSummary = summary
                showDuplicateDialog = true
            } else {
                applyAdditions(
                    targets = targets,
                    additions = planPlaylistAdditions(
                        selectedPlaylistIds = targets.map { it.id },
                        songIds = ids,
                        duplicatesByPlaylist = duplicatesByPlaylist,
                        skipDuplicates = false,
                    ),
                    onLocalWritesDone = onDismiss,
                )
            }
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
            playlistsContainingSong = emptySet()
            selectedPlaylistIds = emptySet()
            return@LaunchedEffect
        }
        val ids = songIds ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            playlistsContainingSong = playlists
                .filter { database.playlistDuplicates(it.id, ids).isNotEmpty() }
                .map { it.id }
                .toSet()
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

            val selectedCount = playlists.count { it.id in selectedPlaylistIds }
            if (selectedCount > 0) {
                stickyHeader {
                    Surface(
                        color = AlertDialogDefaults.containerColor,
                        tonalElevation = AlertDialogDefaults.TonalElevation,
                    ) {
                        FilledTonalButton(
                            onClick = { confirmSelection() },
                            shape = RoundedCornerShape(50),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = pluralStringResource(
                                    R.plurals.add_to_n_playlists,
                                    selectedCount,
                                    selectedCount,
                                ),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
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
                val containsSong = playlist.id in playlistsContainingSong
                val selected = playlist.id in selectedPlaylistIds
                val rowBg by animateColorAsState(
                    targetValue = if (containsSong)
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    else Color.Transparent,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    label = "playlistBg"
                )
                PlaylistListItem(
                    playlist = playlist,
                    trailingContent = {
                        Checkbox(
                            checked = selected,
                            onCheckedChange = null,
                        )
                    },
                    modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(rowBg)
                    .clickable {
                        selectedPlaylistIds =
                            if (selected) selectedPlaylistIds - playlist.id
                            else selectedPlaylistIds + playlist.id
                    }
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

    // duplicate songs warning
    if (showDuplicateDialog) {
        fun applyPending(skipDuplicates: Boolean) {
            showDuplicateDialog = false
            val targets = pendingTargets
            val duplicatesByPlaylist = pendingDuplicates
            val ids = songIds.orEmpty()
            coroutineScope.launch(Dispatchers.IO) {
                applyAdditions(
                    targets = targets,
                    additions = planPlaylistAdditions(
                        selectedPlaylistIds = targets.map { it.id },
                        songIds = ids,
                        duplicatesByPlaylist = duplicatesByPlaylist,
                        skipDuplicates = skipDuplicates,
                    ),
                    onLocalWritesDone = onDismiss,
                )
            }
        }

        DefaultDialog(
            title = { Text(stringResource(R.string.duplicates)) },
            buttons = {
                TextButton(
                    onClick = { applyPending(skipDuplicates = true) }
                ) {
                    Text(stringResource(R.string.skip_duplicates))
                }

                TextButton(
                    onClick = { applyPending(skipDuplicates = false) }
                ) {
                    Text(stringResource(R.string.add_anyway))
                }

                TextButton(
                    onClick = {
                        showDuplicateDialog = false
                    }
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            onDismiss = {
                showDuplicateDialog = false
            }
        ) {
            Text(
                text = when {
                    duplicateSummary.playlistCount > 1 -> pluralStringResource(
                        R.plurals.duplicates_description_playlists,
                        duplicateSummary.songCount,
                        duplicateSummary.songCount,
                        duplicateSummary.playlistCount,
                    )
                    duplicateSummary.songCount == 1 ->
                        stringResource(R.string.duplicates_description_single)
                    else -> stringResource(
                        R.string.duplicates_description_multiple,
                        duplicateSummary.songCount,
                    )
                },
                textAlign = TextAlign.Start,
                modifier = Modifier.align(Alignment.Start)
            )
        }
    }
}
