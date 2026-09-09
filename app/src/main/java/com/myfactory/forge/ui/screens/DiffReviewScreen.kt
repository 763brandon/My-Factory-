package com.myfactory.forge.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.myfactory.forge.R
import com.myfactory.forge.core.diff.FilePatch
import com.myfactory.forge.ui.components.DiffView
import com.myfactory.forge.ui.components.EmptyState

/**
 * Per-hunk review of pending changes.
 *
 * Reached from the Files tab after the agent has edited something: it diffs
 * the working tree against the most recent checkpoint, so the user can revert
 * individual hunks rather than rolling the whole project back.
 *
 * Note the direction. [patch] describes what changed since the checkpoint, and
 * a hunk the user *rejects* is one that gets reverted. Accepting is the
 * default because the common case is keeping the agent's work.
 */
@Composable
fun DiffReviewScreen(
    patches: List<FilePatch>,
    onApply: (Map<String, Set<Int>>) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (patches.isEmpty()) {
        EmptyState(
            icon = Icons.Default.Difference,
            title = stringResource(R.string.diff_title),
            body = stringResource(R.string.diff_no_changes),
            modifier = modifier,
        )
        return
    }

    // Everything starts accepted; the user unticks what they want reverted.
    var selection by remember(patches) {
        mutableStateOf(
            patches.associate { patch ->
                patch.displayPath to patch.hunks.indices.toSet()
            },
        )
    }
    val keptCount = selection.values.sumOf { it.size }
    val totalCount = patches.sumOf { it.hunks.size }

    Column(modifier = modifier.fillMaxSize()) {
        Surface(tonalElevation = 2.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        selection = patches.associate { patch ->
                            patch.displayPath to patch.hunks.indices.toSet()
                        }
                    },
                ) {
                    Text(stringResource(R.string.diff_accept_all))
                }
                OutlinedButton(
                    onClick = {
                        selection = patches.associate { it.displayPath to emptySet() }
                    },
                ) {
                    Text(stringResource(R.string.diff_reject_all))
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            for (patch in patches) {
                DiffView(
                    patch = patch,
                    selectedHunks = selection[patch.displayPath].orEmpty(),
                    onToggleHunk = { index ->
                        val current = selection[patch.displayPath].orEmpty()
                        selection = selection + (
                            patch.displayPath to
                                if (index in current) current - index else current + index
                            )
                    },
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }

        Surface(tonalElevation = 3.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { onApply(selection) },
                    enabled = keptCount < totalCount,
                ) {
                    Text(
                        pluralStringResource(
                            R.plurals.diff_apply,
                            totalCount - keptCount,
                            totalCount - keptCount,
                        ),
                    )
                }
                OutlinedButton(onClick = onCancel) {
                    Text(stringResource(R.string.action_cancel))
                }
                Text(
                    text = stringResource(
                        R.string.diff_summary,
                        patches.sumOf { it.addedCount },
                        patches.sumOf { it.removedCount },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}
