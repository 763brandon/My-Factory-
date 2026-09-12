package com.myfactory.forge.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.myfactory.forge.R
import com.myfactory.forge.core.session.Project
import com.myfactory.forge.ui.components.EmptyState
import java.text.DateFormat
import java.util.Date

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(
    projects: List<Project>,
    onOpen: (Project) -> Unit,
    onCreate: (String) -> Unit,
    onDelete: (Project) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showCreate by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<Project?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.projects_title)) })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreate = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.projects_new)) },
            )
        },
    ) { padding ->
        if (projects.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Folder,
                title = stringResource(R.string.projects_empty_title),
                body = stringResource(R.string.projects_empty_body),
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(modifier = Modifier.padding(padding)) {
                items(projects, key = { it.id }) { project ->
                    ListItem(
                        headlineContent = { Text(project.name) },
                        supportingContent = {
                            Text(
                                text = formatOpened(project.lastOpenedMillis),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        leadingContent = {
                            Icon(Icons.Default.Folder, contentDescription = null)
                        },
                        trailingContent = {
                            TextButton(onClick = { pendingDelete = project }) {
                                Text(stringResource(R.string.projects_delete))
                            }
                        },
                        modifier = Modifier.clickable { onOpen(project) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    // Deleting a project removes its files for good, so the dialog names it.
    pendingDelete?.let { project ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.projects_delete)) },
            text = { Text(stringResource(R.string.projects_delete_confirm, project.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(project)
                        pendingDelete = null
                    },
                ) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text(stringResource(R.string.projects_new)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.projects_name_hint)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onCreate(name.trim())
                        name = ""
                        showCreate = false
                    },
                    enabled = name.isNotBlank(),
                ) {
                    Text(stringResource(R.string.projects_create))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) {
                    Text(stringResource(R.string.projects_cancel))
                }
            },
        )
    }
}

@Composable
private fun formatOpened(millis: Long): String =
    if (millis <= 0) {
        stringResource(R.string.projects_opened_never)
    } else {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
    }
