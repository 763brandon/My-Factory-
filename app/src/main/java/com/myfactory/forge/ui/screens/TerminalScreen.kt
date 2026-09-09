package com.myfactory.forge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.myfactory.forge.R
import com.myfactory.forge.core.capability.Capabilities
import com.myfactory.forge.runtime.proot.BootstrapProgress
import com.myfactory.forge.runtime.proot.RootfsCatalog
import com.myfactory.forge.runtime.proot.RuntimeAvailability
import com.myfactory.forge.runtime.pty.TerminalSession
import com.myfactory.forge.ui.components.EmptyState
import com.myfactory.forge.ui.components.formatBytes
import com.myfactory.forge.ui.theme.CodeTextStyle
import com.myfactory.forge.ui.theme.LocalCodeColors

/**
 * The terminal.
 *
 * Four distinct states, each of which is a real outcome on some device this
 * app supports, and none of which is a crash:
 *   ready         a rootfs is installed; start a shell
 *   bootstrap     an image exists but has not been downloaded
 *   busybox       no PRoot, but basic commands work
 *   unavailable   no local execution at all; say so plainly
 */
@Composable
fun TerminalScreen(
    capabilities: Capabilities,
    availability: RuntimeAvailability,
    session: TerminalSession?,
    bootstrapProgress: BootstrapProgress?,
    onStartShell: () -> Unit,
    onStopShell: () -> Unit,
    onInterrupt: () -> Unit,
    onSendLine: (String) -> Unit,
    onBootstrap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!capabilities.terminalEnabled || availability is RuntimeAvailability.Unavailable) {
        val reason = (availability as? RuntimeAvailability.Unavailable)?.reason
            ?: capabilities.reasons.firstOrNull { it.contains("Terminal") }
            ?: stringResource(R.string.terminal_unavailable_title)
        EmptyState(
            icon = Icons.Default.Terminal,
            title = stringResource(R.string.terminal_unavailable_title),
            body = reason,
            modifier = modifier,
        )
        return
    }

    when (availability) {
        is RuntimeAvailability.NeedsBootstrap -> BootstrapPane(
            image = availability.image,
            progress = bootstrapProgress,
            onBootstrap = onBootstrap,
            modifier = modifier,
        )

        else -> ShellPane(
            session = session,
            hint = (availability as? RuntimeAvailability.BusyBoxOnly)
                ?.let { stringResource(R.string.terminal_busybox_note) },
            onStartShell = onStartShell,
            onStopShell = onStopShell,
            onInterrupt = onInterrupt,
            onSendLine = onSendLine,
            modifier = modifier,
        )
    }
}

@Composable
private fun BootstrapPane(
    image: com.myfactory.forge.runtime.proot.RootfsImage,
    progress: BootstrapProgress?,
    onBootstrap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.terminal_setup_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = stringResource(
                R.string.terminal_setup_body,
                image.displayName,
                formatBytes(image.downloadBytes),
                formatBytes(image.installedBytes),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )

        if (!RootfsCatalog.isProvisioned) {
            Text(
                text = stringResource(R.string.terminal_setup_unprovisioned),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        when (progress) {
            is BootstrapProgress.Downloading -> {
                Text(
                    stringResource(
                        R.string.terminal_downloading,
                        formatBytes(progress.bytesRead),
                        formatBytes(progress.totalBytes),
                    ),
                )
                LinearProgressIndicator(
                    progress = {
                        if (progress.totalBytes > 0) {
                            progress.bytesRead.toFloat() / progress.totalBytes
                        } else {
                            0f
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            BootstrapProgress.Verifying -> Text(stringResource(R.string.terminal_verifying))

            is BootstrapProgress.Extracting -> {
                Text(stringResource(R.string.terminal_extracting))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            is BootstrapProgress.Failed -> Text(
                text = stringResource(R.string.terminal_setup_failed, progress.message),
                color = MaterialTheme.colorScheme.error,
            )

            is BootstrapProgress.Finished, null -> Unit
        }

        Button(
            onClick = onBootstrap,
            enabled = RootfsCatalog.isProvisioned &&
                (progress == null || progress is BootstrapProgress.Failed),
        ) {
            Text(stringResource(R.string.terminal_setup_start))
        }
    }
}

@Composable
private fun ShellPane(
    session: TerminalSession?,
    hint: String?,
    onStartShell: () -> Unit,
    onStopShell: () -> Unit,
    onInterrupt: () -> Unit,
    onSendLine: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val codeColors = LocalCodeColors.current
    var draft by remember { mutableStateOf("") }
    val lines = remember { mutableListOf<String>() }
    var revision by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()

    val state = session?.state?.collectAsState()

    LaunchedEffect(session) {
        session?.output?.collect { chunk ->
            // The screen keeps a bounded scrollback; a build that prints a
            // hundred thousand lines must not grow the heap without limit.
            lines.addAll(chunk.split(NEWLINE))
            while (lines.size > MAX_SCROLLBACK) lines.removeAt(0)
            revision++
        }
    }

    LaunchedEffect(revision) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (hint != null) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(codeColors.editorBackground)
                .padding(horizontal = 8.dp),
        ) {
            items(lines.size) { index ->
                Text(
                    text = lines[index],
                    style = CodeTextStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                    softWrap = true,
                )
            }
        }

        session?.failureMessage?.let { failure ->
            Text(
                text = failure,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
            )
        }

        // A shell that exited should say so, and say why. Silence looks like
        // a hang.
        if (state?.value == TerminalSession.State.EXITED) {
            session?.exitCode?.let { code ->
                Text(
                    text = stringResource(R.string.terminal_exited, code),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        Surface(tonalElevation = 3.dp) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state?.value == TerminalSession.State.RUNNING) {
                        OutlinedButton(onClick = onInterrupt) {
                            Text(stringResource(R.string.terminal_interrupt))
                        }
                        OutlinedButton(onClick = onStopShell) {
                            Text(stringResource(R.string.terminal_stop))
                        }
                    } else {
                        Button(onClick = onStartShell) {
                            Icon(Icons.Default.Terminal, contentDescription = null)
                            Text(
                                text = stringResource(R.string.terminal_start),
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }

                if (state?.value == TerminalSession.State.RUNNING) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.terminal_input_hint)) },
                        singleLine = true,
                        textStyle = CodeTextStyle,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = ImeAction.Send,
                            autoCorrectEnabled = false,
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onSend = {
                                onSendLine(draft)
                                draft = ""
                            },
                        ),
                    )
                }
            }
        }
    }
}

private const val MAX_SCROLLBACK = 3000
private val NEWLINE: Char = 10.toChar()
