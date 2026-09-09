package com.myfactory.forge.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.myfactory.forge.R
import com.myfactory.forge.core.files.FileNode
import com.myfactory.forge.core.files.WorkspaceFs
import com.myfactory.forge.ui.components.EmptyState
import com.myfactory.forge.ui.components.formatBytes

/**
 * A single-directory file browser.
 *
 * Not a tree: an expandable tree of a real project holds thousands of nodes in
 * composition, which is exactly the memory profile the LIGHTWEIGHT tier is
 * meant to avoid. One directory at a time, with a breadcrumb.
 */
@Composable
fun FilesScreen(
    workspace: WorkspaceFs,
    onOpenFile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var currentPath by remember { mutableStateOf("") }
    var nodes by remember { mutableStateOf<List<FileNode>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(currentPath) {
        runCatching { workspace.list(currentPath) }
            .onSuccess {
                nodes = it
                error = null
            }
            .onFailure {
                nodes = emptyList()
                error = it.message
            }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Breadcrumb(
            path = currentPath,
            onNavigate = { currentPath = it },
        )
        HorizontalDivider()

        when {
            error != null -> EmptyState(
                icon = Icons.Default.FolderOpen,
                title = stringResource(R.string.files_title),
                body = error!!,
                modifier = Modifier.weight(1f),
            )

            nodes.isEmpty() -> EmptyState(
                icon = Icons.Default.FolderOpen,
                title = stringResource(R.string.files_title),
                body = stringResource(R.string.files_empty),
                modifier = Modifier.weight(1f),
            )

            else -> LazyColumn(modifier = Modifier.weight(1f)) {
                items(nodes, key = { it.relativePath }) { node ->
                    ListItem(
                        headlineContent = { Text(node.name) },
                        supportingContent = {
                            if (!node.isDirectory) {
                                Text(
                                    formatBytes(node.sizeBytes),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        },
                        leadingContent = {
                            Icon(
                                imageVector = if (node.isDirectory) {
                                    Icons.Default.Folder
                                } else {
                                    Icons.AutoMirrored.Filled.InsertDriveFile
                                },
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                            )
                        },
                        modifier = Modifier
                            .clickable {
                                if (node.isDirectory) {
                                    currentPath = node.relativePath
                                } else {
                                    onOpenFile(node.relativePath)
                                }
                            }
                            .semantics {
                                contentDescription = if (node.isDirectory) {
                                    "Folder ${node.name}"
                                } else {
                                    "File ${node.name}, ${formatBytes(node.sizeBytes)}"
                                }
                            },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun Breadcrumb(path: String, onNavigate: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (path.isNotEmpty()) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.files_parent),
                modifier = Modifier
                    .clickable { onNavigate(path.substringBeforeLast('/', "")) }
                    .padding(8.dp),
            )
        }
        Text(
            text = if (path.isEmpty()) "/" else "/$path",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
