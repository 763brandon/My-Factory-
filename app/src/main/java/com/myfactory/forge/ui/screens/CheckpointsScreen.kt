package com.myfactory.forge.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.myfactory.forge.R
import com.myfactory.forge.core.checkpoint.Checkpoint
import com.myfactory.forge.ui.components.EmptyState
import com.myfactory.forge.ui.components.formatBytes
import java.text.DateFormat
import java.util.Date

@Composable
fun CheckpointsScreen(
    checkpoints: List<Checkpoint>,
    onCreate: () -> Unit,
    onRestore: (Checkpoint) -> Unit,
    onDelete: (Checkpoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingRestore by remember { mutableStateOf<Checkpoint?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreate,
                icon = { Icon(Icons.Default.History, contentDescription = null) },
                text = { Text(stringResource(R.string.checkpoints_create)) },
            )
        },
    ) { padding ->
        if (checkpoints.isEmpty()) {
            EmptyState(
                icon = Icons.Default.History,
                title = stringResource(R.string.checkpoints_title),
                body = stringResource(R.string.checkpoints_empty),
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(modifier = Modifier.padding(padding)) {
                items(checkpoints, key = { it.id }) { checkpoint ->
                    ListItem(
                        headlineContent = { Text(checkpoint.label) },
                        supportingContent = {
                            Text(
                                text = DateFormat
                                    .getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                    .format(Date(checkpoint.createdAtMillis)) +
                                    "  ·  " + stringResource(
                                        R.string.checkpoints_detail,
                                        checkpoint.fileCount,
                                        formatBytes(checkpoint.archiveBytes),
                                    ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        leadingContent = {
                            if (checkpoint.automatic) {
                                AssistChip(
                                    onClick = {},
                                    label = {
                                        Text(
                                            stringResource(R.string.checkpoints_automatic),
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    },
                                )
                            } else {
                                Icon(Icons.Default.History, contentDescription = null)
                            }
                        },
                        trailingContent = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { pendingRestore = checkpoint }) {
                                    Text(stringResource(R.string.checkpoints_restore))
                                }
                                TextButton(onClick = { onDelete(checkpoint) }) {
                                    Text(stringResource(R.string.checkpoints_delete))
                                }
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    // Restoring throws away work that came after the snapshot, so it is
    // always confirmed, and the dialog says exactly what will be lost.
    pendingRestore?.let { checkpoint ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text(stringResource(R.string.checkpoints_restore)) },
            text = {
                Text(stringResource(R.string.checkpoints_restore_confirm, checkpoint.label))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRestore(checkpoint)
                        pendingRestore = null
                    },
                ) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestore = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
