package com.myfactory.forge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.myfactory.forge.core.capability.LinuxStrategy
import com.myfactory.forge.core.checkpoint.Checkpoint
import com.myfactory.forge.core.checkpoint.CheckpointManager
import com.myfactory.forge.core.files.WorkspaceFs
import com.myfactory.forge.core.session.AuditCategory
import com.myfactory.forge.core.session.Project
import com.myfactory.forge.di.AppContainer
import com.myfactory.forge.runtime.proot.BootstrapProgress
import com.myfactory.forge.runtime.proot.LinuxRuntime
import com.myfactory.forge.runtime.proot.RootfsBootstrapper
import com.myfactory.forge.runtime.proot.RuntimeAvailability
import com.myfactory.forge.runtime.pty.TerminalSession
import com.myfactory.forge.runtime.shell.ProotShellRunner
import com.myfactory.forge.ui.navigation.Destination
import com.myfactory.forge.ui.screens.ChatScreen
import com.myfactory.forge.ui.screens.CheckpointsScreen
import com.myfactory.forge.ui.screens.EditorScreen
import com.myfactory.forge.ui.screens.FilesScreen
import com.myfactory.forge.ui.screens.PreviewScreen
import com.myfactory.forge.ui.screens.ProjectsScreen
import com.myfactory.forge.ui.screens.SettingsScreen
import com.myfactory.forge.ui.screens.TerminalScreen
import com.myfactory.forge.ui.theme.ForgeTheme
import com.myfactory.forge.ui.viewmodel.ChatViewModel
import com.myfactory.forge.core.preview.StaticHttpServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = (application as ForgeApplication).container

        setContent {
            ForgeTheme {
                ForgeApp(container)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ForgeApp(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val settings by container.settings.settings.collectAsState()
    val capabilities by container.capabilities.collectAsState()

    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    var activeProject by remember { mutableStateOf<Project?>(null) }
    var destination by remember { mutableStateOf(Destination.CHAT) }
    var editorPath by remember { mutableStateOf<String?>(null) }
    var showCheckpoints by remember { mutableStateOf(false) }
    var checkpoints by remember { mutableStateOf<List<Checkpoint>>(emptyList()) }

    LaunchedEffect(Unit) {
        container.repository.observeProjects().collect { projects = it }
    }
    LaunchedEffect(settings.tierOverride) {
        container.applyTierOverride(settings.tierOverride)
    }

    val project = activeProject
    if (project == null) {
        ProjectsScreen(
            projects = projects,
            onOpen = { selected ->
                activeProject = selected
                scope.launch { container.repository.touchProject(selected.id) }
            },
            onCreate = { name ->
                scope.launch {
                    val id = UUID.randomUUID().toString()
                    val root = File(container.projectsRoot, id).apply { mkdirs() }
                    val now = System.currentTimeMillis()
                    val created = Project(id, name, root.absolutePath, now, now)
                    container.repository.saveProject(created)
                    activeProject = created
                }
            },
        )
        return
    }

    // ------------------------------------------------------------ per-project
    val projectRoot = remember(project.id) { File(project.rootPath) }
    val workspace = remember(project.id) { WorkspaceFs(projectRoot) }
    val checkpointManager = remember(project.id) {
        CheckpointManager(workspace, File(container.checkpointsRoot, project.id))
    }

    val linuxRuntime = remember(capabilities) {
        LinuxRuntime(context, capabilities.primaryAbi, capabilities.linuxStrategy)
    }
    var availability by remember(linuxRuntime) {
        mutableStateOf(linuxRuntime.availability())
    }
    var bootstrapProgress by remember { mutableStateOf<BootstrapProgress?>(null) }
    var terminalSession by remember { mutableStateOf<TerminalSession?>(null) }

    val previewServer = remember(project.id) { StaticHttpServer(projectRoot) }
    var servingUrl by remember(project.id) { mutableStateOf<String?>(null) }

    val chatViewModel: ChatViewModel = viewModel(
        key = "chat-${project.id}",
        factory = chatFactory(container, project, projectRoot),
    )
    chatViewModel.shellRunnerProvider = {
        if (availability is RuntimeAvailability.Ready ||
            availability is RuntimeAvailability.BusyBoxOnly
        ) {
            ProotShellRunner(linuxRuntime, projectRoot)
        } else {
            null
        }
    }

    LaunchedEffect(showCheckpoints, project.id) {
        if (showCheckpoints) {
            container.repository.observeCheckpoints(project.id).collect { checkpoints = it }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(project.name) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            when {
                                editorPath != null -> editorPath = null
                                showCheckpoints -> showCheckpoints = false
                                else -> activeProject = null
                            }
                        },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back),
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (editorPath == null && !showCheckpoints) {
                NavigationBar {
                    Destination.entries.forEach { entry ->
                        NavigationBarItem(
                            selected = destination == entry,
                            onClick = { destination = entry },
                            icon = { Icon(entry.icon, contentDescription = null) },
                            label = { Text(stringResource(entry.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        val content = Modifier
            .fillMaxSize()
            .padding(padding)

        when {
            editorPath != null -> EditorScreen(
                workspace = workspace,
                relativePath = editorPath!!,
                capabilities = capabilities,
                fontScale = settings.editorFontScale,
                softWrap = settings.editorSoftWrap,
                showLineNumbers = settings.editorShowLineNumbers,
                modifier = content,
            )

            showCheckpoints -> CheckpointsScreen(
                checkpoints = checkpoints,
                onCreate = {
                    scope.launch {
                        runCatching {
                            checkpointManager.create(project.id, "Manual checkpoint")
                        }.onSuccess { container.repository.saveCheckpoint(it) }
                    }
                },
                onRestore = { checkpoint ->
                    scope.launch {
                        runCatching { checkpointManager.restore(checkpoint) }
                        container.repository.record(
                            category = AuditCategory.CHECKPOINT_RESTORE,
                            summary = "Restored ${checkpoint.label}",
                            projectId = project.id,
                        )
                    }
                },
                onDelete = { checkpoint ->
                    scope.launch {
                        checkpointManager.delete(checkpoint)
                        container.repository.deleteCheckpoint(checkpoint.id)
                    }
                },
                modifier = content,
            )

            destination == Destination.CHAT -> ChatScreen(
                viewModel = chatViewModel,
                allowSessionApprovals = settings.allowSessionApprovals,
                onOpenSettings = { destination = Destination.SETTINGS },
                modifier = content,
            )

            destination == Destination.FILES -> FilesScreen(
                workspace = workspace,
                onOpenFile = { editorPath = it },
                modifier = content,
            )

            destination == Destination.TERMINAL -> TerminalScreen(
                capabilities = capabilities,
                availability = availability,
                session = terminalSession,
                bootstrapProgress = bootstrapProgress,
                onStartShell = {
                    terminalSession = startShell(scope, linuxRuntime, availability, projectRoot)
                },
                onStopShell = {
                    terminalSession?.terminate()
                    terminalSession = null
                },
                onInterrupt = { terminalSession?.interrupt() },
                onSendLine = { line ->
                    terminalSession?.write(line + 10.toChar())
                },
                onBootstrap = {
                    val image = (availability as? RuntimeAvailability.NeedsBootstrap)?.image
                    if (image != null) {
                        scope.launch {
                            RootfsBootstrapper(linuxRuntime, container.httpClient)
                                .install(image)
                                .collect { progress ->
                                    bootstrapProgress = progress
                                    if (progress is BootstrapProgress.Finished) {
                                        availability = linuxRuntime.availability()
                                    }
                                }
                        }
                    }
                },
                modifier = content,
            )

            destination == Destination.PREVIEW -> PreviewScreen(
                capabilities = capabilities,
                initialUrl = "http://127.0.0.1:3000",
                servingUrl = servingUrl,
                onStartServing = {
                    runCatching { previewServer.start() }
                        .onSuccess { servingUrl = previewServer.baseUrl() }
                },
                onStopServing = {
                    previewServer.stop()
                    servingUrl = null
                },
                modifier = content,
            )

            else -> SettingsScreen(
                settings = settings,
                capabilities = capabilities,
                profile = container.deviceProfile,
                providers = emptyList(),
                activeProviderId = settings.activeProviderConfigId,
                versionName = BuildConfig.VERSION_NAME,
                keystoreWarning = container.secretStore.lastError,
                onUpdateSettings = { transform -> container.settings.update(transform) },
                onAddProvider = {},
                onEditProvider = {},
                onSetActiveProvider = { config ->
                    container.settings.update { it.copy(activeProviderConfigId = config.id) }
                },
                onOpenAuditLog = {},
                onClearKeys = { container.secretStore.clearAll() },
                modifier = content,
            )
        }
    }
}

private fun startShell(
    scope: kotlinx.coroutines.CoroutineScope,
    runtime: LinuxRuntime,
    availability: RuntimeAvailability,
    projectRoot: File,
): TerminalSession? {
    val session = TerminalSession(scope)
    val started = when (availability) {
        is RuntimeAvailability.Ready -> {
            val argv = runtime.buildProotCommand(
                image = availability.image,
                workspaceDir = projectRoot,
                command = listOf(availability.image.defaultShell, "-l"),
            )
            session.start(
                executable = argv.first(),
                arguments = argv.drop(1),
                environment = runtime.terminalEnvironment(),
                workingDirectory = projectRoot.absolutePath,
            )
        }

        is RuntimeAvailability.BusyBoxOnly -> session.start(
            executable = availability.busyBox.absolutePath,
            arguments = listOf("sh"),
            environment = runtime.terminalEnvironment(),
            workingDirectory = projectRoot.absolutePath,
        )

        else -> false
    }
    // A session that failed to start still carries its failure message, which
    // the screen shows; returning it beats returning null and saying nothing.
    return session.takeIf { started || it.failureMessage != null }
}

private fun chatFactory(
    container: AppContainer,
    project: Project,
    projectRoot: File,
) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(
        container = container,
        projectId = project.id,
        projectRoot = projectRoot,
        sessionId = project.id,
    ) as T
}
