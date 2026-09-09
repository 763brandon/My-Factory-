package com.myfactory.forge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.myfactory.forge.R
import com.myfactory.forge.core.diff.FilePatch
import com.myfactory.forge.core.diff.Hunk
import com.myfactory.forge.core.diff.LineType
import com.myfactory.forge.ui.theme.CodeTextStyle
import com.myfactory.forge.ui.theme.LocalCodeColors

/**
 * Renders a unified diff with per-hunk accept and reject.
 *
 * A phone screen cannot show a side-by-side diff usefully, so this is the
 * unified form with a horizontal scroll per line: wrapping a line of code
 * makes a diff much harder to read than scrolling it does.
 */
@Composable
fun DiffView(
    patch: FilePatch,
    selectedHunks: Set<Int>,
    onToggleHunk: (Int) -> Unit,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
) {
    val codeColors = LocalCodeColors.current

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = patch.displayPath,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Text(
                text = stringResource(
                    R.string.diff_summary,
                    patch.addedCount,
                    patch.removedCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }

        if (patch.hunks.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.diff_no_changes),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        items(patch.hunks.size) { index ->
            HunkCard(
                hunk = patch.hunks[index],
                index = index,
                total = patch.hunks.size,
                selected = index in selectedHunks,
                onToggle = { onToggleHunk(index) },
                interactive = interactive,
            )
        }
    }
}

@Composable
private fun HunkCard(
    hunk: Hunk,
    index: Int,
    total: Int,
    selected: Boolean,
    onToggle: () -> Unit,
    interactive: Boolean,
) {
    val codeColors = LocalCodeColors.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = stringResource(R.string.diff_hunk, index + 1, total),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = hunk.header(),
                    style = CodeTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (interactive) {
                FilterChip(
                    selected = selected,
                    onClick = onToggle,
                    label = {
                        Text(
                            stringResource(
                                if (selected) R.string.diff_accept_hunk else R.string.diff_reject_hunk,
                            ),
                        )
                    },
                    modifier = Modifier.semantics {
                        contentDescription = if (selected) {
                            "Change ${index + 1} of $total will be applied"
                        } else {
                            "Change ${index + 1} of $total will be skipped"
                        }
                    },
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            var oldLine = hunk.oldStart
            var newLine = hunk.newStart
            for (line in hunk.lines) {
                val (background, foreground) = when (line.type) {
                    LineType.ADDED -> codeColors.addedBackground to codeColors.added
                    LineType.REMOVED -> codeColors.removedBackground to codeColors.removed
                    LineType.CONTEXT -> androidx.compose.ui.graphics.Color.Transparent to
                        MaterialTheme.colorScheme.onSurface
                }
                val oldLabel = if (line.type == LineType.ADDED) "" else oldLine.toString()
                val newLabel = if (line.type == LineType.REMOVED) "" else newLine.toString()

                DiffLine(
                    oldLabel = oldLabel,
                    newLabel = newLabel,
                    marker = line.type.marker,
                    text = line.text,
                    background = background,
                    foreground = foreground,
                    gutterColor = codeColors.lineNumber,
                )

                if (line.type != LineType.ADDED) oldLine++
                if (line.type != LineType.REMOVED) newLine++
            }
        }
    }
}

@Composable
private fun DiffLine(
    oldLabel: String,
    newLabel: String,
    marker: Char,
    text: String,
    background: androidx.compose.ui.graphics.Color,
    foreground: androidx.compose.ui.graphics.Color,
    gutterColor: androidx.compose.ui.graphics.Color,
) {
    Row(
        modifier = Modifier
            .background(background)
            .padding(horizontal = 8.dp, vertical = 1.dp),
    ) {
        Text(
            text = oldLabel,
            style = CodeTextStyle,
            color = gutterColor,
            textAlign = TextAlign.End,
            modifier = Modifier.width(36.dp),
        )
        Text(
            text = newLabel,
            style = CodeTextStyle,
            color = gutterColor,
            textAlign = TextAlign.End,
            modifier = Modifier
                .width(36.dp)
                .padding(end = 8.dp),
        )
        Text(
            // The +/- marker is what a screen reader and a colour-blind user
            // rely on; colour alone is never the only signal here.
            text = marker + text,
            style = CodeTextStyle,
            color = foreground,
            softWrap = false,
        )
    }
}

/** Read-only rendering of raw diff text, used in the approval sheet. */
@Composable
fun RawDiffView(diffText: String, modifier: Modifier = Modifier) {
    val codeColors = LocalCodeColors.current
    val lines = rememberDiffLines(diffText)

    Box(
        modifier = modifier
            .background(
                codeColors.editorBackground,
                RoundedCornerShape(8.dp),
            ),
    ) {
        Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            for (line in lines) {
                val color = when {
                    line.startsWith("+++") || line.startsWith("---") ->
                        MaterialTheme.colorScheme.onSurfaceVariant
                    line.startsWith("@@") -> MaterialTheme.colorScheme.primary
                    line.startsWith("+") -> codeColors.added
                    line.startsWith("-") -> codeColors.removed
                    else -> MaterialTheme.colorScheme.onSurface
                }
                val background = when {
                    line.startsWith("+++") || line.startsWith("---") ->
                        androidx.compose.ui.graphics.Color.Transparent
                    line.startsWith("+") -> codeColors.addedBackground
                    line.startsWith("-") -> codeColors.removedBackground
                    else -> androidx.compose.ui.graphics.Color.Transparent
                }
                Text(
                    text = line.ifEmpty { " " },
                    style = CodeTextStyle,
                    color = color,
                    softWrap = false,
                    modifier = Modifier
                        .background(background)
                        .padding(horizontal = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun rememberDiffLines(diffText: String): List<String> =
    androidx.compose.runtime.remember(diffText) {
        // A long diff in a bottom sheet is unreadable anyway; cap it so a
        // pathological patch cannot stall the composition.
        diffText.lines().take(400)
    }
