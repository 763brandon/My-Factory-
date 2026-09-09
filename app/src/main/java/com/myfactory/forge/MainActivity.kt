package com.myfactory.forge

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.myfactory.forge.core.ai.ProviderConfig
import com.myfactory.forge.core.capability.LinuxStrategy
import com.myfactory.forge.core.checkpoint.Checkpoint
import com.myfactory.forge.core.checkpoint.CheckpointManager
import com.myfactory.forge.core.diff.FilePatch
import com.myfactory.forge.core.diff.WorkspaceDiff
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
import androidx.compose.ui.unit.dp
import com.myfactory.forge.ui.components.TierChip
import com.myfactory.forge.ui.navigation.Destination
import com.myfactory.forge.core.session.AuditEntry
import com.myfactory.forge.ui.screens.AuditLogScreen
import com.myfactory.forge.ui.screens.ChatScreen
import com.myfactory.forge.ui.screens.DiffReviewScreen
import com.myfactory.forge.ui.screens.CheckpointsScreen
import com.myfactory.forge.ui.screens.EditorScreen
import com.myfactory.forge.ui.screens.FilesScreen
import com.myfactory.forge.ui.screens.PreviewScreen
import com.myfactory.forge.ui.screens.ProjectsScreen
import com.myfactory.forge.ui.screens.ProviderDraft
import com.myfactory.forge.ui.screens.ProviderEditScreen
import com.myfactory.forge.ui.screens.SettingsScreen
import com.myfactory.forge.ui.screens.TerminalScreen
import com.myfactory.forge.ui.theme.ForgeTheme
import com.myfactory.forge.ui.viewmodel.ChatViewModel
import com.myfactory.forge.core.preview.StaticHttpServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * AppCompatActivity rather than ComponentActivity, so the per-app language
 * picker in Settings can take effect: AppCompatDelegate re-creates only
 * AppCompat activities when the application locale changes.
 */
class MainActivity : AppCompatActivity() {

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
    var providers by remember { mutableStateOf<List<ProviderConfig>>(emptyList()) }
    var editingProvider by remember { mutableStateOf<ProviderConfig?>(null) }
    var addingProvider by remember { mutableStateOf(false) }
    var providerTestResult by remember { mutableStateOf<String?>(null) }
    var showAudit by remember { mutableStateOf(false) }
    var auditEntries by remember { mutableStateOf<List<AuditEntry>>(emptyList()) }
    var reviewPatches by remember { mutableStateOf<List<FilePatch>?>(null) }

    LaunchedEffect(Unit) {
        container.repository.observeProjects().collect { projects = it }
    }
    LaunchedEffect(Unit) {
        container.repository.observeProviderConfigs().collect { providers = it }
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
            onDelete = { target ->
                scope.launch {
                    container.repository.deleteProject(target)
                    // The database row is gone; take the files with it, or the
                    // storage stays used with nothing pointing at it.
                    runCatching { File(target.rootPath).deleteRecursively() }
                    runCatching {
                        File(container.checkpointsRoot, target.id).deleteRecursively()
                    }
                }
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
    LaunchedEffect(showAudit) {
        if (showAudit) {
            container.repository.observeAudit().collect { auditEntries = it }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(project.name, modifier = Modifier.weight(1f, fill = false))
                        // The tier drives what the app can do, so it is shown
                        // rather than buried in Settings.
                        TierChip(
                            label = stringResource(
                                if (capabilities.isLightweight) {
                                    R.string.tier_badge_lightweight
                                } else {
                                    R.string.tier_badge_full
                                },
                            ),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                },
                actions = {
                    if (editorPath == null && !showCheckpoints && !showAudit &&
                        reviewPatches == null && !addingProvider && editingProvider == null
                    ) {
                        IconButton(onClick = { showCheckpoints = true }) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = stringResource(R.string.nav_checkpoints),
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            when {
                                editorPath != null -> editorPath = null
                                addingProvider || editingProvider != null -> {
                                    addingProvider = false
                                    editingProvider = null
                                    providerTestResult = null
                                }
                                reviewPatches != null -> reviewPatches = null
                                showAudit -> showAudit = false
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
            if (editorPath == null && !showCheckpoints && !showAudit &&
                !addingProvider && editingProvider == null && reviewPatches == null
            ) {
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
                onClose = { editorPath = null },
                modifier = content,
            )

            showCheckpoints -> CheckpointsScreen(
                checkpoints = checkpoints,
                storageBytes = checkpointManager.totalStorageBytes(),
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

            reviewPatches != null -> DiffReviewScreen(
                patches = reviewPatches!!,
                onApply = { rejected ->
                    scope.launch {
                        val reverted = WorkspaceDiff.revert(workspace, reviewPatches!!, rejected)
                        if (reverted.isNotEmpty()) {
                            container.repository.record(
                                category = AuditCategory.FILE_WRITE,
                                summary = "Reverted changes in ${reverted.size} file(s)",
                                detail = reverted.joinToString(", "),
                                projectId = project.id,
                            )
                        }
                        reviewPatches = null
                    }
                },
                onCancel = { reviewPatches = null },
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
                onReviewChanges = {
                    scope.launch {
                        val newest = container.repository
                            .observeCheckpoints(project.id).first().firstOrNull()
                        reviewPatches = if (newest == null) {
                            emptyList()
                        } else {
                            runCatching {
                                WorkspaceDiff.against(
                                    workspace,
                                    File(
                                        File(container.checkpointsRoot, project.id),
                                        newest.id + ".zip",
                                    ),
                                )
                            }.getOrDefault(emptyList())
                        }
                    }
                },
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

            addingProvider || editingProvider != null -> {
                val existing = editingProvider
                ProviderEditScreen(
                    existing = existing,
                    hasStoredKey = existing?.apiKeyAlias
                        ?.let { container.secretStore.contains(it) } == true,
                    defaultBaseUrlFor = { id ->
                        container.providers.getOrNull(id)?.defaultBaseUrl().orEmpty()
                    },
                    suggestedModelsFor = { id ->
                        container.providers.getOrNull(id)?.fallbackModels()?.map { it.id }
                            .orEmpty()
                    },
                    requiresKeyFor = { id ->
                        container.providers.getOrNull(id)?.requiresApiKey() ?: true
                    },
                    testResult = providerTestResult,
                    onTest = { draft ->
                        scope.launch {
                            providerTestResult = testProvider(context, container, draft)
                        }
                    },
                    onSave = { draft ->
                        scope.launch {
                            saveProvider(container, draft)
                            addingProvider = false
                            editingProvider = null
                            providerTestResult = null
                            chatViewModel.refreshProvider()
                        }
                    },
                    onDelete = existing?.let { config ->
                        {
                            scope.launch {
                                config.apiKeyAlias?.let { container.secretStore.remove(it) }
                                container.repository.deleteProviderConfig(config.id)
                                editingProvider = null
                                chatViewModel.refreshProvider()
                            }
                            Unit
                        }
                    },
                    onCancel = {
                        addingProvider = false
                        editingProvider = null
                        providerTestResult = null
                    },
                    modifier = content,
                )
            }

            showAudit -> AuditLogScreen(
                entries = auditEntries,
                onClear = { scope.launch { container.repository.clearAudit() } },
                modifier = content,
            )

            else -> SettingsScreen(
                settings = settings,
                capabilities = capabilities,
                profile = container.deviceProfile,
                providers = providers,
                activeProviderId = settings.activeProviderConfigId,
                versionName = BuildConfig.VERSION_NAME,
                keystoreWarning = container.secretStore.lastError,
                onUpdateSettings = { transform -> container.settings.update(transform) },
                onAddProvider = { addingProvider = true },
                onEditProvider = { editingProvider = it },
                onSetActiveProvider = { config ->
                    container.settings.update { it.copy(activeProviderConfigId = config.id) }
                    chatViewModel.refreshProvider()
                },
                onOpenAuditLog = { showAudit = true },
                onClearKeys = { container.secretStore.clearAll() },
                onSetLanguage = { tag ->
                    container.settings.update { it.copy(languageTag = tag) }
                    // AppCompatDelegate applies a per-app locale from API 21
                    // through the support library, and hands off to the
                    // platform LocaleManager on API 33+. Recreation is
                    // automatic; nothing here restarts the activity.
                    androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                        if (tag == null) {
                            androidx.core.os.LocaleListCompat.getEmptyLocaleList()
                        } else {
                            androidx.core.os.LocaleListCompat.forLanguageTags(tag)
                        },
                    )
                },
                onOpenSource = {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(SOURCE_URL),
                            ),
                        )
                    }
                },
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

/**
 * Persists an endpoint, storing the key separately in the platform keystore.
 *
 * An empty key field means "leave what is already stored alone", which is why
 * the form never reads an existing key back: blank is a meaningful value.
 */
private suspend fun saveProvider(container: AppContainer, draft: ProviderDraft) {
    if (draft.apiKey.isNotBlank()) {
        draft.config.apiKeyAlias?.let { alias ->
            container.secretStore.put(alias, draft.apiKey.trim())
            container.repository.record(
                category = AuditCategory.KEY_STORED,
                summary = "Stored a key for ${draft.config.displayName}",
                detail = draft.config.destinationHost,
            )
        }
    }
    container.repository.saveProviderConfig(draft.config)

    // The first provider added becomes the active one, so a new user is not
    // left with a configured endpoint and nothing selected.
    if (container.settings.current.activeProviderConfigId == null) {
        container.settings.update { it.copy(activeProviderConfigId = draft.config.id) }
    }
}

/**
 * Asks the endpoint for its model list. That is the cheapest request that
 * proves the URL, the key and the network all work, and it costs no tokens.
 */
private suspend fun testProvider(
    context: android.content.Context,
    container: AppContainer,
    draft: ProviderDraft,
): String {
    val provider = container.providers.getOrNull(draft.config.providerId)
        ?: return context.getString(R.string.settings_test_failed, "no adapter registered")

    val key = draft.apiKey.ifBlank {
        draft.config.apiKeyAlias?.let { container.secretStore.get(it) }.orEmpty()
    }

    return provider.listModels(draft.config, key.ifBlank { null }).fold(
        onSuccess = { models ->
            context.resources.getQuantityString(
                R.plurals.settings_test_ok,
                models.size,
                models.size,
            )
        },
        onFailure = { error ->
            context.getString(R.string.settings_test_failed, error.message.orEmpty())
        },
    )
}

/** Shown in Settings so the licence claim is checkable, not just asserted. */
private const val SOURCE_URL = "https://github.com/763brandon/My-Factory-"
