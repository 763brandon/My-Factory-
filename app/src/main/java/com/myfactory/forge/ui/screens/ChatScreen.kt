package com.myfactory.forge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.myfactory.forge.R
import com.myfactory.forge.core.agent.ApprovalDecision
import com.myfactory.forge.ui.components.ApprovalSheet
import com.myfactory.forge.ui.components.EmptyState
import com.myfactory.forge.ui.theme.CodeTextStyle
import com.myfactory.forge.ui.theme.LocalCodeColors
import com.myfactory.forge.ui.viewmodel.ChatEntry
import com.myfactory.forge.ui.viewmodel.ChatUiState
import com.myfactory.forge.ui.viewmodel.ChatViewModel
import com.myfactory.forge.ui.viewmodel.ToolStatus

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    allowSessionApprovals: Boolean,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { viewModel.refreshProvider() }

    // Follow the stream, but only while the user is already near the bottom;
    // yanking the view down while they are reading history is worse than not
    // following at all.
    LaunchedEffect(state.entries.size) {
        if (state.entries.isNotEmpty()) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (lastVisible >= state.entries.size - 3) {
                listState.animateScrollToItem(state.entries.lastIndex)
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        when {
            state.activeProvider == null -> EmptyState(
                icon = Icons.Default.Chat,
                title = stringResource(R.string.chat_no_provider_title),
                body = stringResource(R.string.chat_no_provider_body),
                actionLabel = stringResource(R.string.chat_open_settings),
                onAction = onOpenSettings,
                modifier = Modifier.weight(1f),
            )

            state.entries.isEmpty() -> EmptyState(
                icon = Icons.Default.Chat,
                title = stringResource(R.string.chat_empty_title),
                body = stringResource(R.string.chat_empty_body),
                modifier = Modifier.weight(1f),
            )

            else -> LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.entries, key = { it.id }) { entry -> ChatEntryRow(entry) }
            }
        }

        state.activeProvider?.let { provider ->
            Text(
                text = stringResource(R.string.chat_destination, provider.destinationHost),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
        }

        Composer(
            state = state,
            draft = draft,
            onDraftChange = { draft = it },
            onSend = {
                viewModel.send(draft)
                draft = ""
            },
            onStop = viewModel::stop,
        )
    }

    state.pendingApproval?.let { request ->
        ApprovalSheet(
            request = request,
            allowSessionApprovals = allowSessionApprovals,
            shellDescription = state.shellDescription,
            onAllowOnce = { viewModel.resolveApproval(ApprovalDecision.Approve) },
            onAllowSession = {
                viewModel.resolveApproval(ApprovalDecision.AlwaysAllowTool(request.toolName))
            },
            onDeny = {
                viewModel.resolveApproval(
                    ApprovalDecision.Reject("The user declined this action."),
                )
            },
        )
    }
}

@Composable
private fun ChatEntryRow(entry: ChatEntry) {
    when (entry) {
        is ChatEntry.User -> Bubble(
            text = entry.text,
            alignment = Alignment.End,
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
        )

        is ChatEntry.Assistant -> Bubble(
            text = entry.text.ifEmpty { stringResource(R.string.chat_thinking) },
            alignment = Alignment.Start,
            container = MaterialTheme.colorScheme.surfaceVariant,
            content = MaterialTheme.colorScheme.onSurface,
            streaming = entry.streaming,
        )

        is ChatEntry.Tool -> ToolRow(entry)

        is ChatEntry.Notice -> Surface(
            color = if (entry.isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                // Errors and stop notices are announced, since a blind user
                // has no other signal that the run ended.
                .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Text(
                text = entry.text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

@Composable
private fun Bubble(
    text: String,
    alignment: Alignment.Horizontal,
    container: Color,
    content: Color,
    streaming: Boolean = false,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment,
    ) {
        Surface(
            color = container,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.widthIn(max = 560.dp),
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = content,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (streaming) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolRow(entry: ChatEntry.Tool) {
    val codeColors = LocalCodeColors.current
    val (icon, tint) = when (entry.status) {
        ToolStatus.RUNNING -> null to MaterialTheme.colorScheme.onSurfaceVariant
        ToolStatus.SUCCEEDED -> Icons.Default.CheckCircle to codeColors.added
        ToolStatus.FAILED -> Icons.Default.Error to MaterialTheme.colorScheme.error
        ToolStatus.REJECTED -> Icons.Default.Error to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (icon == null) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(16.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = entry.summary, style = MaterialTheme.typography.bodyMedium)
                if (entry.detail.isNotBlank()) {
                    Text(
                        text = entry.detail,
                        style = CodeTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                    )
                }
            }
        }
    }
}

@Composable
private fun Composer(
    state: ChatUiState,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.chat_input_hint)) },
                enabled = state.activeProvider != null,
                maxLines = 6,
                shape = RoundedCornerShape(20.dp),
            )

            if (state.isRunning) {
                IconButton(
                    onClick = onStop,
                    modifier = Modifier.semantics {
                        contentDescription = "Stop the agent"
                    },
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                }
            } else {
                IconButton(
                    onClick = onSend,
                    enabled = state.canSend && draft.isNotBlank(),
                    modifier = Modifier.semantics {
                        contentDescription = "Send the message"
                    },
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                }
            }
        }
    }
}
