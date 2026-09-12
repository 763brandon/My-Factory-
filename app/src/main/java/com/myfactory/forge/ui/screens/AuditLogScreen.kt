package com.myfactory.forge.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.myfactory.forge.R
import com.myfactory.forge.core.session.AuditCategory
import com.myfactory.forge.core.session.AuditEntry
import com.myfactory.forge.ui.components.EmptyState
import java.text.DateFormat
import java.util.Date

/**
 * The activity log.
 *
 * This is a privacy feature, not a debugging one: it is the answer to "what
 * did this app actually do, and where did my code go". It is readable by the
 * user, in their own language, and it can be cleared.
 */
@Composable
fun AuditLogScreen(
    entries: List<AuditEntry>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) {
        EmptyState(
            icon = Icons.Default.Article,
            title = stringResource(R.string.settings_audit_log),
            body = stringResource(R.string.settings_audit_log_body),
            modifier = modifier,
        )
        return
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            TextButton(onClick = onClear, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(stringResource(R.string.settings_clear_log))
            }
        }
        items(entries, key = { it.id }) { entry ->
            ListItem(
                headlineContent = { Text(entry.summary) },
                supportingContent = {
                    Text(
                        text = DateFormat
                            .getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                            .format(Date(entry.timestampMillis)) +
                            "  ·  " + label(entry.category) +
                            if (entry.detail.isBlank()) "" else "  ·  " + entry.detail,
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
            )
            HorizontalDivider()
        }
    }
}

private fun label(category: AuditCategory): String = when (category) {
    AuditCategory.FILE_WRITE -> "file write"
    AuditCategory.FILE_DELETE -> "file delete"
    AuditCategory.COMMAND_RUN -> "command"
    AuditCategory.NETWORK_REQUEST -> "network"
    AuditCategory.CHECKPOINT_CREATE -> "checkpoint"
    AuditCategory.CHECKPOINT_RESTORE -> "restore"
    AuditCategory.KEY_STORED -> "key stored"
    AuditCategory.KEY_DELETED -> "key deleted"
    AuditCategory.PERMISSION_DECISION -> "permission"
}
