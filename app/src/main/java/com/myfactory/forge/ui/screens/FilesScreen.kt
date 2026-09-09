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
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A single-directory file browser with create, rename and delete.
 *
 * Not a tree: an expandable tree of a real project holds thousands of nodes in
 * composition, which is exactly the memory profile the LIGHTWEIGHT tier exists
 * to avoid. One directory at a time, with a breadcrumb.
 */
@Composable
fun FilesScreen(
    workspace: WorkspaceFs,
    onOpenFile: (String) -> Unit,
    /** Opens per-hunk review of everything changed since the last checkpoint. */
    onReviewChanges: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var currentPath by remember { mutableStateOf("") }
    var nodes by remember { mutableStateOf<List<FileNode>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    // Bumped after any mutation to re-list the directory.
    var revision by remember { mutableIntStateOf(0) }

    var creating by remember { mutableStateOf<CreateKind?>(null) }
    var renaming by remember { mutableStateOf<FileNode?>(null) }
    var deleting by remember { mutableStateOf<FileNode?>(null) }

    // LaunchedEffect runs on the main dispatcher. Listing a directory with a
    // few thousand entries on a slow eMMC phone is exactly the kind of
    // main-thread I/O that drops frames, so it goes to IO explicitly.
    LaunchedEffect(currentPath, revision) {
        withContext(Dispatchers.IO) { runCatching { workspace.list(currentPath) } }
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
        Toolbar(
            path = currentPath,
            onNavigateUp = { currentPath = currentPath.substringBeforeLast('/', "") },
            onNewFile = { creating = CreateKind.FILE },
            onNewFolder = { creating = CreateKind.FOLDER },
            onReviewChanges = onReviewChanges,
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
                        trailingContent = {
                            Row {
                                TextButton(onClick = { renaming = node }) {
                                    Text(stringResource(R.string.files_rename))
                                }
                                TextButton(onClick = { deleting = node }) {
                                    Text(stringResource(R.string.files_delete))
                                }
                            }
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

    creating?.let { kind ->
        NameDialog(
            title = stringResource(
                if (kind == CreateKind.FILE) R.string.files_new_file else R.string.files_new_folder,
            ),
            initial = "",
            onConfirm = { name ->
                val target = if (currentPath.isEmpty()) name else "$currentPath/$name"
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            if (kind == CreateKind.FILE) {
                                workspace.write(target, "")
                            } else {
                                workspace.mkdirs(target)
                            }
                        }
                    }.onFailure { error = it.message }
                    creating = null
                    revision++
                }
            },
            onDismiss = { creating = null },
        )
    }

    renaming?.let { node ->
        NameDialog(
            title = stringResource(R.string.files_rename),
            initial = node.name,
            onConfirm = { name ->
                val parent = node.relativePath.substringBeforeLast('/', "")
                val target = if (parent.isEmpty()) name else "$parent/$name"
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching { workspace.move(node.relativePath, target) }
                    }.onFailure { error = it.message }
                    renaming = null
                    revision++
                }
            },
            onDismiss = { renaming = null },
        )
    }

    // Deleting is confirmed because it is not undoable from here; a checkpoint
    // is the only way back, and the user may not have taken one.
    deleting?.let { node ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.files_delete)) },
            text = { Text(node.relativePath) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    workspace.delete(node.relativePath, recursive = true)
                                }
                            }.onFailure { error = it.message }
                            deleting = null
                            revision++
                        }
                    },
                ) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

private enum class CreateKind { FILE, FOLDER }

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    // A name with a separator would silently create or move across
    // directories, so the field is restricted to a single component.
    val valid = name.isNotBlank() && !name.contains('/') && name != "." && name != ".."

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                isError = name.isNotBlank() && !valid,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = valid) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun Toolbar(
    path: String,
    onNavigateUp: () -> Unit,
    onNewFile: () -> Unit,
    onNewFolder: () -> Unit,
    onReviewChanges: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (path.isNotEmpty()) {
            IconButton(onClick = onNavigateUp) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.files_parent),
                )
            }
        }
        Text(
            text = if (path.isEmpty()) "/" else "/$path",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onReviewChanges) {
            Icon(
                imageVector = Icons.Default.Difference,
                contentDescription = stringResource(R.string.diff_title),
            )
        }
        IconButton(onClick = onNewFile) {
            Icon(
                imageVector = Icons.Default.NoteAdd,
                contentDescription = stringResource(R.string.files_new_file),
            )
        }
        IconButton(onClick = onNewFolder) {
            Icon(
                imageVector = Icons.Default.CreateNewFolder,
                contentDescription = stringResource(R.string.files_new_folder),
            )
        }
    }
}
