package com.myfactory.forge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.myfactory.forge.R
import com.myfactory.forge.core.agent.ApprovalRequest
import com.myfactory.forge.ui.theme.CodeTextStyle
import com.myfactory.forge.ui.theme.LocalCodeColors

/**
 * The consent step.
 *
 * Nothing the agent does to a file or to the shell happens before this sheet
 * is answered. It always shows what will change, in full: a diff for a write,
 * the exact command line for an execution. A yes/no box without that would be
 * consent in name only.
 *
 * The sheet is deliberately not dismissible by tapping outside. An accidental
 * scrim tap must not become an implicit answer either way.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalSheet(
    request: ApprovalRequest,
    allowSessionApprovals: Boolean,
    shellDescription: String?,
    onAllowOnce: () -> Unit,
    onAllowSession: () -> Unit,
    onDeny: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val codeColors = LocalCodeColors.current

    ModalBottomSheet(
        onDismissRequest = onDeny,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = if (request.command != null) {
                        Icons.Default.Terminal
                    } else {
                        Icons.Default.Warning
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                )
                Text(
                    text = stringResource(R.string.approval_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            }

            Text(
                text = stringResource(
                    when {
                        request.command != null -> R.string.approval_command_subtitle
                        request.toolName == "delete_file" -> R.string.approval_delete_subtitle
                        else -> R.string.approval_write_subtitle
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = request.summary,
                style = MaterialTheme.typography.titleMedium,
            )

            // Bound to locals: these are public properties from another
            // module, so the compiler will not smart-cast them.
            val command = request.command
            val previewDiff = request.previewDiff

            when {
                command != null -> {
                    Text(
                        text = command,
                        style = CodeTextStyle,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(codeColors.editorBackground, RoundedCornerShape(8.dp))
                            .padding(12.dp)
                            .semantics {
                                contentDescription = "Command to run: $command"
                            },
                    )
                    if (shellDescription != null) {
                        Text(
                            text = stringResource(R.string.approval_runs_in, shellDescription),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                previewDiff != null -> {
                    RawDiffView(
                        diffText = previewDiff,
                        modifier = Modifier
                            .fillMaxWidth()
                            // Bounded so a large diff cannot push the buttons
                            // off the bottom of a small screen.
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }

                else -> {
                    Text(
                        text = stringResource(R.string.approval_no_preview),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Button(
                onClick = onAllowOnce,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.approval_allow))
            }

            if (allowSessionApprovals) {
                OutlinedButton(
                    onClick = onAllowSession,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.approval_allow_session))
                }
            }

            TextButton(
                onClick = onDeny,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.approval_deny))
            }
        }
    }
}
