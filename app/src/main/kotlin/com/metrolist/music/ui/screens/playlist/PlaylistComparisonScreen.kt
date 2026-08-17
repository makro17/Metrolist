/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.playlist

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.ListThumbnailSize
import com.metrolist.music.ui.component.DefaultDialog
import com.metrolist.music.viewmodels.ComparisonRow
import com.metrolist.music.viewmodels.ComparisonState
import com.metrolist.music.viewmodels.PlaylistComparisonViewModel

private const val TAB_ONLY_HERE = 0
private const val TAB_ONLY_ON_YOUTUBE = 1

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistComparisonScreen(
    navController: NavController,
    viewModel: PlaylistComparisonViewModel = hiltViewModel(),
) {
    val playlist by viewModel.playlist.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val expectedTotal by viewModel.expectedTotal.collectAsStateWithLifecycle()

    var selectedTab by rememberSaveable { mutableIntStateOf(TAB_ONLY_HERE) }
    var pendingLocalRemoval by remember { mutableStateOf<ComparisonRow?>(null) }
    var pendingRemoteRemoval by remember { mutableStateOf<ComparisonRow?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current
                    .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
            ),
    ) {
        TopAppBar(
            title = { Text(playlist?.playlist?.name.orEmpty()) },
            navigationIcon = {
                IconButton(onClick = navController::navigateUp) {
                    Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                }
            },
        )

        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == TAB_ONLY_HERE,
                onClick = { selectedTab = TAB_ONLY_HERE },
                text = {
                    Text(
                        stringResource(R.string.comparison_only_here) +
                            "  " + countFor(state, TAB_ONLY_HERE),
                    )
                },
            )
            Tab(
                selected = selectedTab == TAB_ONLY_ON_YOUTUBE,
                onClick = { selectedTab = TAB_ONLY_ON_YOUTUBE },
                text = {
                    Text(
                        stringResource(R.string.comparison_only_on_youtube) +
                            "  " + countFor(state, TAB_ONLY_ON_YOUTUBE),
                    )
                },
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            when (val current = state) {
                is ComparisonState.Guessing -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = expectedTotal
                            ?.let { stringResource(R.string.comparison_reading_of, progress, it) }
                            ?: stringResource(R.string.comparison_reading, progress),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                    // The guess only ever covers the local side, and nothing is actionable until
                    // YouTube has answered.
                    ComparisonList(
                        rows = if (selectedTab == TAB_ONLY_HERE) current.onlyLocal else emptyList(),
                        enabled = false,
                        primaryIcon = R.drawable.upload,
                        primaryLabel = R.string.comparison_upload_one,
                        removeLabel = R.string.remove_from_playlist,
                        onPrimary = {},
                        onRemove = {},
                    )
                }

                is ComparisonState.Failed -> ComparisonMessage(
                    message = stringResource(R.string.comparison_failed),
                    actionLabel = stringResource(R.string.retry),
                    onAction = viewModel::refresh,
                )

                ComparisonState.Truncated -> ComparisonMessage(
                    message = stringResource(R.string.comparison_truncated),
                    actionLabel = stringResource(R.string.retry),
                    onAction = viewModel::refresh,
                )

                is ComparisonState.Ready ->
                    if (current.onlyLocal.isEmpty() && current.onlyRemote.isEmpty()) {
                        ComparisonMessage(message = stringResource(R.string.comparison_in_sync))
                    } else if (selectedTab == TAB_ONLY_HERE) {
                        ComparisonList(
                            rows = current.onlyLocal,
                            enabled = true,
                            primaryIcon = R.drawable.upload,
                            primaryLabel = R.string.comparison_upload_one,
                            removeLabel = R.string.remove_from_playlist,
                            onPrimary = { row -> viewModel.uploadToYouTube(listOf(row.id)) },
                            onRemove = { row -> pendingLocalRemoval = row },
                            emptyMessage = stringResource(R.string.comparison_nothing_only_here),
                        )
                    } else {
                        ComparisonList(
                            rows = current.onlyRemote,
                            enabled = true,
                            primaryIcon = R.drawable.download,
                            primaryLabel = R.string.comparison_add_one,
                            removeLabel = R.string.comparison_remove_from_youtube,
                            onPrimary = { row -> viewModel.addLocally(listOf(row.id)) },
                            onRemove = { row -> pendingRemoteRemoval = row },
                            emptyMessage = stringResource(R.string.comparison_nothing_only_on_youtube),
                        )
                    }
            }
        }

        // Only the constructive direction gets a bulk button: a pinned control that deletes thirty
        // songs in one tap is a trap, so removal stays per row and behind its confirmation.
        val ready = state as? ComparisonState.Ready
        val rows = if (selectedTab == TAB_ONLY_HERE) ready?.onlyLocal else ready?.onlyRemote
        if (!rows.isNullOrEmpty()) {
            Button(
                onClick = {
                    val ids = rows.map { it.id }
                    if (selectedTab == TAB_ONLY_HERE) {
                        viewModel.uploadToYouTube(ids)
                    } else {
                        viewModel.addLocally(ids)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text(
                    pluralStringResource(
                        if (selectedTab == TAB_ONLY_HERE) {
                            R.plurals.comparison_upload_all
                        } else {
                            R.plurals.comparison_add_all
                        },
                        rows.size,
                        rows.size,
                    ),
                )
            }
        }
    }

    // Removing a song that is only here. PR 6's confirmation without its YouTube sentence: this
    // song is not on YouTube, which is the whole reason it is in this tab.
    pendingLocalRemoval?.let { row ->
        DefaultDialog(
            title = { Text(stringResource(R.string.remove_from_playlist)) },
            buttons = {
                TextButton(
                    onClick = {
                        pendingLocalRemoval = null
                        viewModel.removeLocally(listOf(row.id))
                    },
                ) { Text(stringResource(R.string.remove)) }

                TextButton(onClick = { pendingLocalRemoval = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            onDismiss = { pendingLocalRemoval = null },
        ) {
            Text(
                text = pluralStringResource(
                    R.plurals.remove_n_songs_confirm,
                    1,
                    1,
                    playlist?.playlist?.name.orEmpty(),
                ),
                textAlign = TextAlign.Start,
                modifier = Modifier.align(Alignment.Start),
            )
        }
    }

    pendingRemoteRemoval?.let { row ->
        DefaultDialog(
            title = { Text(stringResource(R.string.comparison_remove_from_youtube)) },
            buttons = {
                TextButton(
                    onClick = {
                        pendingRemoteRemoval = null
                        viewModel.removeFromYouTube(listOf(row))
                    },
                ) { Text(stringResource(R.string.remove)) }

                TextButton(onClick = { pendingRemoteRemoval = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            onDismiss = { pendingRemoteRemoval = null },
        ) {
            Text(
                text = stringResource(R.string.comparison_remove_from_youtube_confirm, row.title),
                textAlign = TextAlign.Start,
                modifier = Modifier.align(Alignment.Start),
            )
        }
    }
}

@Composable
private fun ComparisonList(
    rows: List<ComparisonRow>,
    enabled: Boolean,
    @DrawableRes primaryIcon: Int,
    @StringRes primaryLabel: Int,
    @StringRes removeLabel: Int,
    onPrimary: (ComparisonRow) -> Unit,
    onRemove: (ComparisonRow) -> Unit,
    emptyMessage: String? = null,
) {
    if (rows.isEmpty()) {
        if (emptyMessage != null) ComparisonMessage(message = emptyMessage)
        return
    }

    LazyColumn {
        items(rows, key = { it.id }) { row ->
            ListItem(
                headlineContent = {
                    Text(row.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                supportingContent = {
                    Column {
                        Text(row.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (row.neverConfirmed) {
                            Text(
                                text = stringResource(R.string.comparison_never_confirmed),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                leadingContent = {
                    AsyncImage(
                        model = row.thumbnailUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(ListThumbnailSize)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                },
                trailingContent = {
                    Row {
                        IconButton(enabled = enabled, onClick = { onPrimary(row) }) {
                            Icon(
                                painter = painterResource(primaryIcon),
                                contentDescription = stringResource(primaryLabel),
                            )
                        }
                        IconButton(enabled = enabled, onClick = { onRemove(row) }) {
                            Icon(
                                painter = painterResource(R.drawable.close),
                                contentDescription = stringResource(removeLabel),
                            )
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun ComparisonMessage(
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
    ) {
        Text(text = message, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction, modifier = Modifier.padding(top = 16.dp)) {
                Text(actionLabel)
            }
        }
    }
}

/** The number beside a tab's name, or a placeholder while the remote side is still unknown. */
private fun countFor(state: ComparisonState, tab: Int): String = when (state) {
    is ComparisonState.Ready ->
        (if (tab == TAB_ONLY_HERE) state.onlyLocal.size else state.onlyRemote.size).toString()

    is ComparisonState.Guessing ->
        if (tab == TAB_ONLY_HERE) state.onlyLocal.size.toString() else "·"

    else -> "·"
}
