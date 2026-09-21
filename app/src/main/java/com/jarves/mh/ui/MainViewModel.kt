package com.jarves.mh.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.os.SystemClock
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.jarves.mh.BuildConfig
import com.jarves.mh.data.ApiKeyPool
import com.jarves.mh.data.ApiKeyVault
import com.jarves.mh.data.ExhaustedKeyStore
import com.jarves.mh.data.AppPreferences
import com.jarves.mh.data.AiUsageStore
import com.jarves.mh.data.AiUsageStatus
import com.jarves.mh.data.AiUsageSummary
import com.jarves.mh.data.ApiKeyHealth
import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ActivityItem
import com.jarves.mh.model.ChangeItem
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.ChatAttachment
import com.jarves.mh.model.DevStack
import com.jarves.mh.model.Project
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.ProjectChat
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.model.ToolRequest
import com.jarves.mh.model.WorkspaceEntry
import com.jarves.mh.model.projectSlug
import com.jarves.mh.model.generateQuickChatIdentity
import com.jarves.mh.network.ConnectionValidation
import com.jarves.mh.network.ModelDiscoveryResult
import com.jarves.mh.network.ProviderApiClient
import com.jarves.mh.runtime.ClaudeRuntimeBridge
import com.jarves.mh.runtime.DshRuntimeBridge
import com.jarves.mh.runtime.AntigravityRuntimeBridge
import com.jarves.mh.runtime.AntigravityAuthController
import com.jarves.mh.runtime.AntigravityAuthState
import com.jarves.mh.runtime.AntigravityAuthStatus
import com.jarves.mh.runtime.RuntimeBridge
import com.jarves.mh.runtime.NativeSpawnProcess
import com.jarves.mh.runtime.RuntimeInstallProgress
import com.jarves.mh.runtime.RuntimeInstaller
import com.jarves.mh.runtime.RuntimeSetupController
import com.jarves.mh.runtime.RuntimeSetupService
import com.jarves.mh.runtime.RuntimeSetupSnapshot
import com.jarves.mh.runtime.RuntimeSetupStatus
import com.jarves.mh.runtime.AndroidAppInstaller
import com.jarves.mh.update.AppUpdateInfo
import com.jarves.mh.update.AppUpdater
import java.io.File
import java.io.RandomAccessFile
import java.net.UnknownHostException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

enum class StartupStage { CHECKING, SETUP_REQUIRED, INSTALLING, MODEL_SETUP, INITIALIZING, READY, ERROR }

enum class ApiPingStatus { IDLE, PINGING, OK, FAILED }
enum class AppUpdateStatus { AVAILABLE, PERMISSION_REQUIRED, DOWNLOADING, INSTALLING, ERROR }
enum class GitHubAuthStatus { DISCONNECTED, STARTING, AWAITING_USER, CONNECTED, ERROR }

enum class ManualUpdateCheck { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, FAILED }

data class TerminalOutputLine(
    val id: String = java.util.UUID.randomUUID().toString(),
    val command: String,
    val output: String,
    val exitCode: Int = 0,
)

private val ANSI_TERMINAL_SEQUENCE = Regex("\\u001B(?:\\][^\\u0007]*(?:\\u0007|\\u001B\\\\)|\\[[0-?]*[ -/]*[@-~]|[()][A-Z0-9])")

internal fun sanitizeTerminalOutput(text: String): String = text
    .replace(ANSI_TERMINAL_SEQUENCE, "")
    .filter { it == '\n' || it == '\r' || it == '\t' || it.code >= 0x20 }

private val ANTIGRAVITY_MODEL_EFFORT = Regex("^(.*)-(low|medium|high)$")

private fun antigravityEffortFromModel(model: String): String? =
    ANTIGRAVITY_MODEL_EFFORT.matchEntire(model)?.groupValues?.get(2)

private fun antigravityModelWithEffort(model: String, effort: String): String? {
    val match = ANTIGRAVITY_MODEL_EFFORT.matchEntire(model) ?: return null
    return "${match.groupValues[1]}-$effort"
}

private data class ProjectTerminalSnapshot(
    val lines: List<TerminalOutputLine> = emptyList(),
    val cwd: String = "/workspace",
)

private data class ProjectTerminalResult(
    val output: String,
    val exitCode: Int,
    val cwd: String,
)

data class AppUiState(
    val startupStage: StartupStage = StartupStage.CHECKING,
    val startupProgress: Float = 0f,
    val startupMessage: String = "Checking this device…",
    val startupBytes: Pair<Long, Long>? = null,
    val startupLogs: List<String> = emptyList(),
    val startupIndeterminate: Boolean = false,
    val startupError: String? = null,
    val startupErrorIsOffline: Boolean = false,
    val onboardingComplete: Boolean = false,
    val backgroundSetupComplete: Boolean = false,
    val selectedAgent: AgentKind = AgentKind.CLAUDE_CODE,
    val installedAgents: Set<AgentKind> = setOf(AgentKind.CLAUDE_CODE),
    val agentInstalling: AgentKind? = null,
    val agentInstallMessage: String? = null,
    val agentInstallProgress: Float = 0f,
    val antigravityAuth: AntigravityAuthState = AntigravityAuthState(),
    val antigravityModel: String = "",
    val antigravityEffort: String = "high",
    val antigravityModels: List<String> = emptyList(),
    val antigravityModelsLoading: Boolean = false,
    val antigravityModelTestStatus: ApiPingStatus = ApiPingStatus.IDLE,
    val antigravityModelTestMessage: String? = null,
    val provider: ProviderProfile = ProviderProfile(ProviderKind.ANTHROPIC),
    val themeMode: com.jarves.mh.ui.theme.AppThemeMode = com.jarves.mh.ui.theme.AppThemeMode.DARK,
    val apiPingStatus: ApiPingStatus = ApiPingStatus.IDLE,
    val apiPingMessage: String? = null,
    val githubAuthStatus: GitHubAuthStatus = GitHubAuthStatus.DISCONNECTED,
    val githubLogin: String? = null,
    val githubUserCode: String? = null,
    val githubVerificationUri: String? = null,
    val githubMessage: String? = null,
    val githubCanCancel: Boolean = false,
    val githubRepositories: List<GitHubRepository> = emptyList(),
    val githubRepositoriesLoading: Boolean = false,
    val githubRepositoryMessage: String? = null,
    val githubCloneInProgress: String? = null,
    val githubWork: GitHubWorkSnapshot = GitHubWorkSnapshot(),
    val githubWorkRunning: Boolean = false,
    val githubWorkChecksPassed: Boolean = false,
    val githubWorkCheckCommand: String = "",
    val githubWorkCheckOutput: String = "",
    val githubWorkPullRequestUrl: String? = null,
    val githubWorkMessage: String? = null,
    val githubChatWorkflow: GitHubChatWorkflow = GitHubChatWorkflow(),
    val aiUsage: AiUsageSummary = AiUsageSummary(),
    val apiKeyHealth: List<ApiKeyHealth> = emptyList(),
    val projects: List<Project> = emptyList(),
    val activeProject: Project? = null,
    val projectChats: List<ProjectChat> = emptyList(),
    val activeChatId: String? = null,
    val workspaceFiles: List<WorkspaceEntry> = emptyList(),
    val androidProjectDetected: Boolean = false,
    val filesLoading: Boolean = false,
    val openedFilePath: String? = null,
    val openedFileContent: String? = null,
    val fileContentLoading: Boolean = false,
    val messages: List<ChatMessage> = listOf(
        ChatMessage(fromUser = false, text = "Hi! Tell me what you want to build or change."),
    ),
    val pendingAttachments: List<ChatAttachment> = emptyList(),
    val pendingApproval: ToolRequest? = null,
    val changes: List<ChangeItem> = emptyList(),
    val activity: List<ActivityItem> = emptyList(),
    val liveProcess: List<ActivityItem> = emptyList(),
    val liveThinking: Boolean = false,
    val activeThinkingBlockId: Long? = null,
    val taskStartedAtMillis: Long? = null,
    val taskFinishedAtMillis: Long? = null,
    val workSegmentStartedAtMillis: Long? = null,
    val currentTaskRequest: String? = null,
    val previewReady: Boolean = false,
    val previewUrl: String? = null,
    val isRunning: Boolean = false,
    val activeSessionId: String? = null,
    val toastMessage: String? = null,
    val projectTerminalLines: List<TerminalOutputLine> = emptyList(),
    val projectTerminalLiveOutput: String = "",
    val projectTerminalRunning: Boolean = false,
    val projectTerminalCwd: String = "/workspace",
    val projectTerminalCommand: String? = null,
    val projectTerminalDraft: String? = null,
    val pendingTerminalCommand: String? = null,
    val suggestedProjectRoot: String? = null,
    val selectedDevStacks: Set<DevStack> = emptySet(),
    val installedDevStacks: Set<DevStack> = emptySet(),
    val devStackInstalling: DevStack? = null,
    val devStackMessage: String? = null,
    val devStackProgress: Float = 0f,
    val devStackBytes: Pair<Long, Long>? = null,
    val androidBuildRunning: Boolean = false,
    val androidBuildMessage: String? = null,
    val appUpdate: AppUpdateInfo? = null,
    val appUpdateStatus: AppUpdateStatus? = null,
    val appUpdateDownloadedBytes: Long = 0L,
    val appUpdateTotalBytes: Long = -1L,
    val appUpdateError: String? = null,
    val manualUpdateCheck: ManualUpdateCheck = ManualUpdateCheck.IDLE,
    val manualUpdateCheckMessage: String? = null,
    val appUpdateStage: String? = null,
    val appUpdatePermissionNote: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val vault = ApiKeyVault(application)
    private val preferences = AppPreferences(application)
    private val exhaustedKeys = ExhaustedKeyStore(application)
    private val usageStore = AiUsageStore(application)
    @Volatile private var activeUsageId: String? = null
    private val claudeRuntime = ClaudeRuntimeBridge(
        application,
        poolFor = { profile -> exhaustedKeys.snapshot(profile.kind.name, ApiKeyPool.parse(vault.get(profile.kind.name))) },
        onKeyExhausted = { profile, key -> exhaustedKeys.markExhausted(profile.kind.name, key); refreshUsageState() },
    )
    private val dshRuntime = DshRuntimeBridge(application) { profile ->
        exhaustedKeys.snapshot(profile.kind.name, ApiKeyPool.parse(vault.get(profile.kind.name))).available.firstOrNull()
    }
    private val antigravityConversations = ConcurrentHashMap<String, String>()
    private val antigravityRuntime = AntigravityRuntimeBridge(
        application,
        model = { _state.value.antigravityModel },
        effort = { _state.value.antigravityEffort },
        conversationId = { antigravityConversations[it] },
        saveConversationId = { projectId, conversationId -> antigravityConversations[projectId] = conversationId },
    )
    private val antigravityAuthController = AntigravityAuthController(
        application,
        initiallySignedIn = File(application.filesDir, "runtime/ubuntu/root/.gemini/antigravity-cli/antigravity-oauth-token").isFile,
        initialAccountEmail = "",
        onSignedInChanged = { _, _ -> },
    )
    private fun activeRuntime(): RuntimeBridge = when (_state.value.selectedAgent) {
        AgentKind.DEEPSEEK_HARNESS -> dshRuntime
        AgentKind.ANTIGRAVITY -> antigravityRuntime
        AgentKind.CLAUDE_CODE -> claudeRuntime
    }
    private fun configureProjectRoot(projectId: String, rootPath: String) {
        claudeRuntime.configureProjectRoot(projectId, rootPath)
        dshRuntime.configureProjectRoot(projectId, rootPath)
        antigravityRuntime.configureProjectRoot(projectId, rootPath)
    }
    private val installer = RuntimeInstaller(application)
    private val providerApi = ProviderApiClient()
    private val fileReadRequests = LatestFileRead()
    private fun appUpdater(): AppUpdater = AppUpdater(
        getApplication(),
        if (BuildConfig.DEBUG) preferences.debugUpdateManifestUrl else "",
    )
    @Volatile private var projectTerminalProcess: Process? = null
    @Volatile private var terminalProcess: Process? = null
    @Volatile private var githubAuthProcess: Process? = null
    @Volatile private var githubWorkProcess: Process? = null
    @Volatile private var githubAuthCancelledByUser: Boolean = false
    private var githubAuthJob: Job? = null
    @Volatile private var projectTerminalProjectId: String? = null
    @Volatile private var projectTerminalStopRequested: Boolean = false
    @Volatile private var setupCompletionHandled: Boolean = false
    private val _state = MutableStateFlow(
        AppUiState(
            onboardingComplete = preferences.onboardingComplete,
            backgroundSetupComplete = preferences.backgroundSetupComplete,
            selectedAgent = runCatching { AgentKind.valueOf(preferences.selectedAgentKind) }
                .getOrDefault(AgentKind.CLAUDE_CODE),
            antigravityModel = preferences.antigravityModel,
            antigravityEffort = preferences.antigravityEffort,
            provider = preferences.loadProvider(vault),
            githubLogin = preferences.githubLogin.takeIf(String::isNotBlank),
            themeMode = runCatching { com.jarves.mh.ui.theme.AppThemeMode.valueOf(preferences.themeMode.uppercase()) }
                .getOrDefault(com.jarves.mh.ui.theme.AppThemeMode.DARK),
            projects = preferences.loadProjects(),
            selectedDevStacks = preferences.selectedDevStacks.mapNotNull { name ->
                runCatching { DevStack.valueOf(name) }.getOrNull()
            }.toSet() + DevStack.WEB,
        ),
    )

    init {
        refreshUsageState()
        viewModelScope.launch { refreshGitHubConnection() }
        RuntimeSetupController.restore(application)
        if (!preferences.legacySeededCredentialRemoved) {
            vault.remove(ProviderKind.CUSTOM.name)
            preferences.legacySeededCredentialRemoved = true
            _state.update { current ->
                if (current.provider.kind == ProviderKind.CUSTOM) {
                    current.copy(provider = current.provider.copy(hasSecret = false))
                } else current
            }
        }
        if (
            BuildConfig.TEST_OPENROUTER_API_KEY.isNotBlank() &&
            preferences.testProviderDefaultsVersion < TEST_PROVIDER_DEFAULTS_VERSION
        ) {
            val testProvider = ProviderProfile(
                kind = ProviderKind.CUSTOM,
                baseUrl = TEST_OPENROUTER_BASE_URL,
                model = TEST_OPENROUTER_MODEL,
                hasSecret = true,
            )
            vault.put(ProviderKind.CUSTOM.name, BuildConfig.TEST_OPENROUTER_API_KEY)
            preferences.saveProvider(testProvider)
            preferences.testProviderDefaultsVersion = TEST_PROVIDER_DEFAULTS_VERSION
            _state.update { it.copy(provider = testProvider) }
        }

        val loadedProjects = preferences.loadProjects()
        val cleanedProjects = loadedProjects.filter { project ->
            if (project.kind == ProjectKind.QUICK_PROJECT) {
                val chats = preferences.loadProjectChats(project.id)
                val userMessages = chats.sumOf { preferences.loadMessages(project.id, it.id).count { m -> m.fromUser } }
                val workspaceDir = File(application.filesDir, "workspaces/${project.id}")
                val userFiles = if (workspaceDir.isDirectory) {
                    workspaceDir.walkTopDown().filter { file ->
                        file.isFile && !file.name.startsWith(".claude") && file.name != ".pocket-dev-stacks.json"
                    }.count()
                } else 0
                val keep = userMessages > 0 || userFiles > 0
                if (!keep) {
                    workspaceDir.deleteRecursively()
                    terminalHistoryFile(project.id).delete()
                    preferences.deleteProjectChats(project.id)
                }
                keep
            } else true
        }
        if (cleanedProjects.size != loadedProjects.size) {
            preferences.saveProjects(cleanedProjects)
            _state.update { it.copy(projects = cleanedProjects) }
        }
    }

    val state: StateFlow<AppUiState> = _state.asStateFlow()

    /** One-shot events asking the UI to open system Settings > "Install unknown apps". */
    private val openUnknownSourcesSettingsChannel = Channel<Unit>(Channel.BUFFERED)
    val openUnknownSourcesSettings: Flow<Unit> = openUnknownSourcesSettingsChannel.receiveAsFlow()
    @Volatile private var awaitingUnknownSourcesReturn = false

    private val _terminalLines = MutableStateFlow<List<TerminalOutputLine>>(
        listOf(
            TerminalOutputLine(
                command = "uname -a",
                output = "Linux pocket-dev 6.1.0-arm64 #1 SMP aarch64 GNU/Linux (PRoot Sandbox)",
                exitCode = 0,
            ),
        ),
    )
    val terminalLines: StateFlow<List<TerminalOutputLine>> = _terminalLines.asStateFlow()

    private val _isTerminalRunning = MutableStateFlow(false)
    val isTerminalRunning: StateFlow<Boolean> = _isTerminalRunning.asStateFlow()

    private val _terminalLiveOutput = MutableStateFlow("")
    val terminalLiveOutput: StateFlow<String> = _terminalLiveOutput.asStateFlow()

    private val _terminalCurrentCommand = MutableStateFlow<String?>(null)
    val terminalCurrentCommand: StateFlow<String?> = _terminalCurrentCommand.asStateFlow()

    fun runTerminalCommand(cmd: String) {
        val command = cmd.trim()
        if (command.isBlank() || _isTerminalRunning.value) return
        if (command == "clear") {
            _terminalLines.value = emptyList()
            return
        }
        _isTerminalRunning.value = true
        _terminalCurrentCommand.value = command
        _terminalLiveOutput.value = ""
        viewModelScope.launch {
            val (output, exitCode) = withContext(Dispatchers.IO) {
                runCatching {
                    if (!installer.isInstalled()) {
                        return@runCatching "Linux environment is not ready yet." to 1
                    }
                    val runtime = installer.installedRuntime()
                    val workspace = File(getApplication<Application>().filesDir, "workspaces/terminal").apply { mkdirs() }
                    val preparedCommand = prepareInteractiveShellCommand(command)
                    val proc = installer.process(
                        proot = runtime.proot,
                        rootfs = runtime.rootfs,
                        workspace = workspace,
                        environment = emptyMap(),
                        guestCommand = listOf("/usr/bin/bash", "-c", preparedCommand),
                    )
                    terminalProcess = proc
                    val native = proc as? NativeSpawnProcess
                    var offset = 0L
                    val streamed = BoundedTerminalOutput(MAX_PROJECT_TERMINAL_OUTPUT)
                    var autoConfirmed = false
                    while (proc.isAlive || (native?.outputFile?.length() ?: 0L) > offset) {
                        val file = native?.outputFile
                        val available = (file?.length() ?: 0L) - offset
                        if (file == null || available <= 0) {
                            Thread.sleep(50)
                            continue
                        }
                        val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
                        val count = RandomAccessFile(file, "r").use { input ->
                            input.seek(offset)
                            input.read(bytes)
                        }
                        if (count > 0) {
                            offset += count
                            streamed.append(bytes.decodeToString(0, count))
                            _terminalLiveOutput.value = sanitizeTerminalOutput(streamed.toString())
                                .trimEnd()
                                .takeLast(MAX_PROJECT_TERMINAL_OUTPUT)
                            if (!autoConfirmed && shouldAutoConfirmPackageCommand(command, streamed.toString())) {
                                proc.outputStream.write("y\n".toByteArray())
                                proc.outputStream.flush()
                                autoConfirmed = true
                            }
                        }
                    }
                    val exit = proc.waitFor()
                    runCatching { proc.outputStream.close() }
                    val out = sanitizeTerminalOutput(streamed.toString()).trim()
                    val finalOut = if (out.isNotEmpty() || exit == 0) out else "Process exited with code $exit"
                    finalOut to exit
                }.getOrElse { "Error: ${it.message}" to 1 }
            }
            _terminalLines.update {
                (it + TerminalOutputLine(command = command, output = output, exitCode = exitCode))
                    .takeLast(MAX_PROJECT_TERMINAL_HISTORY)
            }
            _terminalLiveOutput.value = ""
            _terminalCurrentCommand.value = null
            _isTerminalRunning.value = false
            terminalProcess = null
        }
    }

    fun sendTerminalInput(text: String) {
        sendProcessInput(terminalProcess, text)
    }

    fun interruptTerminalCommand() {
        interruptProcess(terminalProcess)
    }

    fun clearTerminal() {
        _terminalLines.value = emptyList()
    }

    fun requestProjectTerminalCommand(command: String) {
        val normalized = command.trim()
        if (normalized.isBlank() || _state.value.projectTerminalRunning || projectTerminalProcess?.isAlive == true) return
        if (isDestructiveTerminalCommand(normalized)) {
            _state.update { it.copy(pendingTerminalCommand = normalized) }
        } else {
            runProjectTerminalCommand(normalized)
        }
    }

    fun prepareProjectTerminalCommand(command: String) {
        val project = _state.value.activeProject ?: return
        if (command.isBlank() || _state.value.projectTerminalRunning) return
        _state.update {
            it.copy(
                projectTerminalCwd = projectGuestRoot(project),
                projectTerminalDraft = command.trim(),
            )
        }
    }

    fun consumeProjectTerminalDraft() {
        _state.update { it.copy(projectTerminalDraft = null) }
    }

    fun openProjectTerminal() {
        val project = _state.value.activeProject ?: return
        if (_state.value.projectTerminalRunning) return
        _state.update { it.copy(projectTerminalCwd = projectGuestRoot(project)) }
    }

    fun confirmProjectTerminalCommand() {
        val command = _state.value.pendingTerminalCommand ?: return
        _state.update { it.copy(pendingTerminalCommand = null) }
        runProjectTerminalCommand(command)
    }

    fun cancelProjectTerminalCommand() {
        _state.update { it.copy(pendingTerminalCommand = null) }
    }

    private fun runProjectTerminalCommand(command: String) {
        val project = _state.value.activeProject ?: return
        if (_state.value.projectTerminalRunning) return
        val startingCwd = _state.value.projectTerminalCwd
        val existingLines = _state.value.projectTerminalLines
        projectTerminalStopRequested = false
        val requestedPreviewUrl = detectServerUrl(command)
        _state.update {
            it.copy(
                projectTerminalRunning = true,
                projectTerminalLiveOutput = "",
                projectTerminalCommand = command,
                pendingTerminalCommand = null,
                previewReady = it.previewReady || requestedPreviewUrl != null,
                previewUrl = requestedPreviewUrl ?: it.previewUrl,
            )
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { runProjectTerminalProcess(project.id, command, startingCwd) }
                    .getOrElse { error ->
                        ProjectTerminalResult(
                            output = "Terminal error: ${error.message ?: error::class.java.simpleName}",
                            exitCode = 1,
                            cwd = startingCwd,
                        )
                    }
            }
            val completedLine = TerminalOutputLine(
                command = command,
                output = result.output.ifBlank {
                    if (result.exitCode == 0) "" else "Process exited with code ${result.exitCode}"
                },
                exitCode = result.exitCode,
            )
            val updatedLines = (existingLines + completedLine).takeLast(MAX_PROJECT_TERMINAL_HISTORY)
            saveProjectTerminal(project.id, result.cwd, updatedLines)
            if (_state.value.activeProject?.id == project.id) {
                _state.update {
                    it.copy(
                        projectTerminalLines = updatedLines,
                        projectTerminalLiveOutput = "",
                        projectTerminalRunning = false,
                        projectTerminalCwd = result.cwd,
                        projectTerminalCommand = null,
                    )
                }
                refreshProjectFiles()
            }
            projectTerminalProcess = null
            projectTerminalProjectId = null
            projectTerminalStopRequested = false
        }
    }

    fun stopProjectTerminalCommand() {
        if (!_state.value.projectTerminalRunning) return
        projectTerminalStopRequested = true
        viewModelScope.launch(Dispatchers.IO) {
            projectTerminalProcess?.destroy()
            delay(400)
            if (projectTerminalProcess?.isAlive == true) projectTerminalProcess?.destroyForcibly()
        }
    }

    fun sendProjectTerminalInput(text: String) {
        sendProcessInput(projectTerminalProcess, text)
    }

    fun interruptProjectTerminalCommand() {
        interruptProcess(projectTerminalProcess)
    }

    fun clearProjectTerminal() {
        val project = _state.value.activeProject ?: return
        if (_state.value.projectTerminalRunning) return
        _state.update { it.copy(projectTerminalLines = emptyList(), projectTerminalLiveOutput = "") }
        saveProjectTerminal(project.id, _state.value.projectTerminalCwd, emptyList())
    }

    private fun runProjectTerminalProcess(projectId: String, command: String, cwd: String): ProjectTerminalResult {
        if (!installer.isInstalled()) return ProjectTerminalResult("Linux environment is not ready yet.", 1, cwd)
        val installed = installer.installedRuntime()
        val project = _state.value.projects.firstOrNull { it.id == projectId }
            ?: _state.value.activeProject?.takeIf { it.id == projectId }
            ?: return ProjectTerminalResult("Project is no longer available.", 1, cwd)
        val workspace = projectWorkspaceRoot(project)
        val guestWorkspacePath = projectGuestRoot(project)
        val marker = "__POCKETDEV_CWD_${UUID.randomUUID()}__"
        val preparedCommand = prepareInteractiveShellCommand(command)
        val script = """
            cd -- ${shellQuote(cwd)} || exit 1
            $preparedCommand
            pocket_status=${'$'}?
            printf '\n$marker%s\n' "${'$'}PWD"
            exit ${'$'}pocket_status
        """.trimIndent()
        val process = installer.process(
            proot = installed.proot,
            rootfs = installed.rootfs,
            workspace = workspace,
            environment = emptyMap(),
            guestCommand = listOf("/usr/bin/bash", "-lc", script),
            guestWorkspacePath = guestWorkspacePath,
        )
        projectTerminalProcess = process
        projectTerminalProjectId = projectId
        if (projectTerminalStopRequested) process.destroy()
        val native = process as? NativeSpawnProcess
            ?: return ProjectTerminalResult("Unsupported terminal process.", 1, cwd)
        var offset = 0L
        val output = BoundedTerminalOutput(MAX_PROJECT_TERMINAL_OUTPUT)
        var autoConfirmed = false
        while (process.isAlive || native.outputFile.length() > offset) {
            val available = native.outputFile.length() - offset
            if (available <= 0) {
                Thread.sleep(50)
                continue
            }
            val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
            val count = RandomAccessFile(native.outputFile, "r").use { file ->
                file.seek(offset)
                file.read(bytes)
            }
            if (count > 0) {
                offset += count
                output.append(bytes.decodeToString(0, count))
                val visible = sanitizeTerminalOutput(output.toString().substringBefore(marker))
                    .takeLast(MAX_PROJECT_TERMINAL_OUTPUT)
                if (!autoConfirmed && shouldAutoConfirmPackageCommand(command, visible)) {
                    process.outputStream.write("y\n".toByteArray())
                    process.outputStream.flush()
                    autoConfirmed = true
                }
                val detectedPreviewUrl = detectPreviewUrl(visible)
                _state.update { current ->
                    if (current.activeProject?.id == projectId) {
                        current.copy(
                            projectTerminalLiveOutput = visible,
                            previewReady = current.previewReady || detectedPreviewUrl != null,
                            previewUrl = detectedPreviewUrl ?: current.previewUrl,
                        )
                    } else current
                }
            }
        }
        val exitCode = process.waitFor()
        runCatching { process.outputStream.close() }
        val raw = output.toString()
        val cwdAfter = raw.substringAfter(marker, "")
            .lineSequence()
            .firstOrNull()
            ?.trim()
            ?.takeIf { it == guestWorkspacePath || it.startsWith("$guestWorkspacePath/") }
            ?: cwd
        val cleanOutput = sanitizeTerminalOutput(raw.substringBefore(marker))
            .trim()
            .takeLast(MAX_PROJECT_TERMINAL_OUTPUT)
        return ProjectTerminalResult(cleanOutput, exitCode, cwdAfter)
    }

    private fun sendProcessInput(process: Process?, text: String) {
        if (process?.isAlive != true || text.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                process.outputStream.write((text + "\n").toByteArray())
                process.outputStream.flush()
            }.onFailure {
                _state.update { current -> current.copy(toastMessage = "This process is no longer accepting input.") }
            }
        }
    }

    private fun interruptProcess(process: Process?) {
        if (process?.isAlive != true) return
        viewModelScope.launch(Dispatchers.IO) {
            (process as? NativeSpawnProcess)?.interrupt() ?: process.destroy()
        }
    }

    /**
     * Package management must never block on Y/N, locale, timezone, service-restart,
     * or config-file dialogs in the phone UI. Other commands remain interactive and
     * can receive input through [sendProcessInput].
     */
    private fun prepareInteractiveShellCommand(command: String): String {
        val normalizedApt = command
            .replace(Regex("(?<![\\w-])sudo\\s+apt(?:-get)?\\s+"), "apt-get ")
            .replace(Regex("(?<![\\w-])apt\\s+"), "apt-get ")
            .replace(
                Regex("(?<![\\w-])apt-get\\s+(install|upgrade|full-upgrade|dist-upgrade|remove|autoremove|fix-broken)\\b"),
                "apt-get -y -o Dpkg::Options::=--force-confold $1",
            )
        return "export DEBIAN_FRONTEND=noninteractive APT_LISTCHANGES_FRONTEND=none UCF_FORCE_CONFFOLD=1 NEEDRESTART_MODE=a TZ=Etc/UTC LC_ALL=C.UTF-8; $normalizedApt"
    }

    private fun shouldAutoConfirmPackageCommand(command: String, output: String): Boolean {
        val packageCommand = Regex("(?i)(^|[;&|]\\s*)(sudo\\s+)?(apt|apt-get|dpkg)\\b").containsMatchIn(command)
        if (!packageCommand) return false
        val tail = output.takeLast(500)
        return Regex("(?i)(do you want to continue|continue\\?)\\s*\\[[Yy]/[Nn]\\]").containsMatchIn(tail)
    }

    private fun isDestructiveTerminalCommand(command: String): Boolean {
        val normalized = command.lowercase().replace(Regex("\\s+"), " ")
        return listOf(
            "rm -rf", "rm -fr", "git reset --hard", "git clean -f", "git push --force",
            "mkfs", "dd if=", "chmod -r 777", "shutdown", "reboot", ":(){", "kill \$(pgrep", "pkill -f",
        ).any(normalized::contains) || Regex("(curl|wget).*(\\||>)\\s*(sh|bash)").containsMatchIn(normalized)
    }

    private fun detectPreviewUrl(output: String): String? {
        val match = Regex("https?://(?:localhost|127\\.0\\.0\\.1|0\\.0\\.0\\.0):(\\d{2,5})(?:/[^\\s]*)?")
            .findAll(output)
            .lastOrNull()
            ?: return null
        val port = match.groupValues[1].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return "http://127.0.0.1:$port/"
    }

    private fun detectServerUrl(command: String): String? {
        val match = Regex("""python(?:3)?\s+-m\s+http\.server(?:\s+(\d{2,5}))?""")
            .find(command)
            ?: return null
        val port = match.groupValues.getOrNull(1)?.toIntOrNull() ?: 8000
        return port.takeIf { it in 1..65535 }?.let { "http://127.0.0.1:$it/" }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun terminalHistoryFile(projectId: String): File =
        File(getApplication<Application>().filesDir, "terminal-history/$projectId.json")

    private fun loadProjectTerminal(project: Project): ProjectTerminalSnapshot {
        val file = terminalHistoryFile(project.id)
        val guestRoot = projectGuestRoot(project)
        if (!file.isFile) return ProjectTerminalSnapshot(cwd = guestRoot)
        return runCatching {
            val root = JSONObject(file.readText())
            val array = root.optJSONArray("lines") ?: JSONArray()
            val lines = (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                TerminalOutputLine(
                    id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                    command = item.optString("command"),
                    output = item.optString("output"),
                    exitCode = item.optInt("exitCode"),
                )
            }
            ProjectTerminalSnapshot(
                lines = lines.takeLast(MAX_PROJECT_TERMINAL_HISTORY),
                cwd = root.optString("cwd", guestRoot).takeIf {
                    it == guestRoot || it.startsWith("$guestRoot/")
                } ?: guestRoot,
            )
        }.getOrDefault(ProjectTerminalSnapshot(cwd = guestRoot))
    }

    private fun saveProjectTerminal(projectId: String, cwd: String, lines: List<TerminalOutputLine>) {
        runCatching {
            val file = terminalHistoryFile(projectId)
            file.parentFile?.mkdirs()
            val array = JSONArray()
            lines.takeLast(MAX_PROJECT_TERMINAL_HISTORY).forEach { line ->
                array.put(
                    JSONObject()
                        .put("id", line.id)
                        .put("command", line.command)
                        .put("output", line.output.takeLast(MAX_PROJECT_TERMINAL_OUTPUT))
                        .put("exitCode", line.exitCode),
                )
            }
            file.writeText(JSONObject().put("cwd", cwd).put("lines", array).toString())
        }
    }

    private fun projectGuestRoot(project: Project): String = "/workspace/${project.slug}"

    private fun projectWorkspaceRoot(project: Project): File {
        val base = File(getApplication<Application>().filesDir, "workspaces/${project.id}")
            .apply { mkdirs() }
            .canonicalFile
        if (project.rootPath.isBlank()) return base
        val selected = File(base, project.rootPath).canonicalFile
        require(selected.toPath().startsWith(base.toPath())) { "Unsafe project root" }
        return selected.apply { mkdirs() }
    }

    fun buildAndRunAndroidApp() {
        val project = _state.value.activeProject ?: return
        if (_state.value.androidBuildRunning) return
        if (_state.value.isRunning) {
            _state.update { it.copy(toastMessage = "Wait for Claude to finish creating the project before building.") }
            return
        }
        if (_state.value.projectTerminalRunning) {
            _state.update { it.copy(toastMessage = "Wait for the project terminal command to finish before building.") }
            return
        }
        _state.update { it.copy(androidBuildRunning = true, androidBuildMessage = "Building debug APK…", toastMessage = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val installed = installer.installedRuntime()
                val workspace = findAndroidGradleProjectRoot(projectWorkspaceRoot(project))
                    ?: error("No Android Gradle project found yet. Ask Claude to create it, then wait for the task to finish.")
                val process = installer.process(
                    installed.proot, installed.rootfs, workspace, emptyMap(),
                    listOf(
                        "/usr/bin/bash", "-lc",
                        "gradle --init-script /root/.gradle/init.d/pocketdev-android.gradle " +
                            "-Pandroid.aapt2FromMavenOverride=/root/android-sdk/build-tools/35.0.0/aapt2 " +
                            "--no-daemon assembleDebug --console=plain",
                    ),
                    projectGuestRoot(project),
                )
                val exitCode = process.waitFor()
                val buildOutput = (process as? NativeSpawnProcess)?.outputFile?.readText().orEmpty()
                check(exitCode == 0) {
                    buildOutput.trim().takeLast(2_000).ifBlank { "Gradle build failed (exit code $exitCode)" }
                }
                val apk = workspace.walkTopDown()
                    .filter { it.isFile && it.extension.equals("apk", ignoreCase = true) && it.path.contains("/outputs/apk/debug/") }
                    .maxByOrNull(File::lastModified)
                    ?: error("Gradle finished but no debug APK was found")
                AndroidAppInstaller.install(getApplication(), apk)
            }.onSuccess {
                withContext(Dispatchers.Main) {
                    _state.update {
                        it.copy(
                            androidBuildRunning = false,
                            androidBuildMessage = "APK sent to Android installer",
                            toastMessage = "APK built. Complete Android's install prompt.",
                        )
                    }
                }
            }.onFailure { error ->
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(androidBuildRunning = false, androidBuildMessage = null, toastMessage = error.message ?: "Could not build APK") }
                }
            }
        }
    }

    private fun findAndroidGradleProjectRoot(workspace: File): File? {
        val settingsNames = setOf("settings.gradle", "settings.gradle.kts", "settings.gradle.dcl")
        return workspace.walkTopDown()
            .maxDepth(4)
            .filter { it.isFile && it.name in settingsNames }
            .map(File::getParentFile)
            .sortedBy { it.absolutePath.length }
            .firstOrNull { root ->
                root.walkTopDown()
                    .maxDepth(4)
                    .any { it.isFile && it.invariantSeparatorsPath.endsWith("src/main/AndroidManifest.xml") }
            }
    }


    fun toggleTheme() {
        val next = if (_state.value.themeMode == com.jarves.mh.ui.theme.AppThemeMode.DARK) {
            com.jarves.mh.ui.theme.AppThemeMode.LIGHT
        } else {
            com.jarves.mh.ui.theme.AppThemeMode.DARK
        }
        setThemeMode(next)
    }

    fun setThemeMode(mode: com.jarves.mh.ui.theme.AppThemeMode) {
        preferences.themeMode = mode.name.lowercase()
        _state.update { it.copy(themeMode = mode) }
    }

    fun selectAgent(agent: AgentKind) {
        if (_state.value.isRunning || _state.value.projectTerminalRunning || _state.value.agentInstalling != null) {
            _state.update { it.copy(toastMessage = "Stop running work before switching coding agents.") }
            return
        }
        val alreadyInstalled = when (agent) {
            AgentKind.CLAUDE_CODE -> true
            AgentKind.DEEPSEEK_HARNESS -> installer.isDeepSeekHarnessInstalled()
            AgentKind.ANTIGRAVITY -> installer.isAntigravityInstalled()
        }
        if (alreadyInstalled) {
            preferences.selectedAgentKind = agent.name
            _state.update { it.copy(selectedAgent = agent, installedAgents = it.installedAgents + agent, toastMessage = if (agent == AgentKind.ANTIGRAVITY && it.antigravityAuth.status != AntigravityAuthStatus.SIGNED_IN) "Antigravity selected. Sign in with Google below." else "${agent.title} is active.") }
            return
        }
        _state.update { it.copy(agentInstalling = agent, agentInstallMessage = "Preparing ${agent.title}…", agentInstallProgress = 0f) }
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val callback: suspend (RuntimeInstallProgress) -> Unit = { progress ->
                        _state.update { current -> current.copy(agentInstallMessage = progress.message, agentInstallProgress = progress.fraction.coerceIn(0f, 1f)) }
                    }
                    when (agent) {
                        AgentKind.DEEPSEEK_HARNESS -> installer.ensureDeepSeekHarnessInstalled(callback)
                        AgentKind.ANTIGRAVITY -> installer.ensureAntigravityInstalled(callback)
                        AgentKind.CLAUDE_CODE -> Unit
                    }
                }
            }
            if (result.isSuccess) {
                preferences.selectedAgentKind = agent.name
                _state.update { it.copy(selectedAgent = agent, installedAgents = it.installedAgents + agent, agentInstalling = null, agentInstallMessage = "${agent.title} is ready", agentInstallProgress = 1f, toastMessage = if (agent == AgentKind.ANTIGRAVITY) "Antigravity installed. Sign in with Google below." else "${agent.title} installed and activated.") }
            } else {
                _state.update { it.copy(agentInstalling = null, agentInstallProgress = 0f, agentInstallMessage = result.exceptionOrNull()?.message?.take(240), toastMessage = result.exceptionOrNull()?.message ?: "${agent.title} installation failed") }
            }
        }
    }

    fun beginAntigravityLogin() {
        viewModelScope.launch { antigravityAuthController.beginLogin() }
    }

    fun submitAntigravityCode(code: String) {
        runCatching { antigravityAuthController.submitCode(code) }
            .onFailure { error -> _state.update { it.copy(toastMessage = error.message ?: "Could not submit Google code") } }
    }

    fun logoutAntigravity() {
        viewModelScope.launch { runCatching { antigravityAuthController.logout() } }
    }

    fun setAntigravityModel(model: String) {
        if (model.isBlank()) return
        preferences.antigravityModel = model
        val modelEffort = antigravityEffortFromModel(model)
        if (modelEffort != null) preferences.antigravityEffort = modelEffort
        _state.update {
            it.copy(
                antigravityModel = model,
                antigravityEffort = modelEffort ?: it.antigravityEffort,
                antigravityModelTestStatus = ApiPingStatus.IDLE,
                antigravityModelTestMessage = null,
                toastMessage = "Google AI model selected: $model",
            )
        }
    }

    fun setAntigravityEffort(effort: String) {
        if (effort !in setOf("low", "medium", "high")) return
        val current = _state.value
        val matchingModel = antigravityModelWithEffort(current.antigravityModel, effort)
            ?.takeIf { candidate -> current.antigravityModels.isEmpty() || candidate in current.antigravityModels }
        if (current.antigravityModel.isNotBlank() &&
            antigravityEffortFromModel(current.antigravityModel) != null &&
            matchingModel == null
        ) {
            _state.update { it.copy(toastMessage = "This Google model does not offer ${effort.replaceFirstChar(Char::uppercase)} reasoning") }
            return
        }
        preferences.antigravityEffort = effort
        matchingModel?.let { preferences.antigravityModel = it }
        _state.update {
            it.copy(
                antigravityEffort = effort,
                antigravityModel = matchingModel ?: it.antigravityModel,
                antigravityModelTestStatus = ApiPingStatus.IDLE,
                antigravityModelTestMessage = null,
            )
        }
    }

    fun refreshAntigravityModels() {
        val current = _state.value
        if (current.antigravityModelsLoading || !installer.isAntigravityInstalled()) return
        if (current.antigravityAuth.status != AntigravityAuthStatus.SIGNED_IN) {
            _state.update { it.copy(toastMessage = "Connect Google before loading Antigravity models") }
            return
        }
        _state.update { it.copy(antigravityModelsLoading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val runtime = installer.installedRuntime()
                val workspace = File(getApplication<Application>().filesDir, "workspaces/antigravity-models").apply { mkdirs() }
                val process = installer.process(
                    runtime.proot,
                    runtime.rootfs,
                    workspace,
                    emptyMap(),
                    listOf(RuntimeInstaller.AGY_GUEST_PATH, "models"),
                    guestWorkspacePath = "/workspace/antigravity-models",
                    emulateHardLinks = false,
                )
                while (process.isAlive) delay(50)
                check(process.waitFor() == 0) { "Could not list Google AI models" }
                val output = (process as? NativeSpawnProcess)?.outputFile?.readText().orEmpty()
                output.lineSequence()
                    .map { sanitizeTerminalOutput(it).trim() }
                    .mapNotNull { line -> line.split(Regex("\\s+"), limit = 2).firstOrNull() }
                    .filter { it.matches(Regex("[a-z0-9][a-z0-9._-]+")) }
                    .filter { it.startsWith("gemini-") || it.contains("google", ignoreCase = true) }
                    .distinct()
                    .toList()
                    .also { check(it.isNotEmpty()) { "Antigravity returned no Google AI models" } }
            }
            withContext(Dispatchers.Main) {
                _state.update { state ->
                    result.fold(
                        onSuccess = { models ->
                            val preferred = antigravityModelWithEffort(state.antigravityModel, state.antigravityEffort)
                                ?.takeIf(models::contains)
                            val selected = preferred
                                ?: state.antigravityModel.takeIf(models::contains)
                                ?: models.first()
                            val selectedEffort = antigravityEffortFromModel(selected) ?: state.antigravityEffort
                            preferences.antigravityModel = selected
                            preferences.antigravityEffort = selectedEffort
                            state.copy(
                                antigravityModelsLoading = false,
                                antigravityModels = models,
                                antigravityModel = selected,
                                antigravityEffort = selectedEffort,
                                toastMessage = "${models.size} Google AI models available",
                            )
                        },
                        onFailure = { error -> state.copy(
                            antigravityModelsLoading = false,
                            toastMessage = error.message ?: "Could not load Google AI models",
                        ) },
                    )
                }
            }
        }
    }

    fun testAntigravityModel() {
        val current = _state.value
        if (current.antigravityModelTestStatus == ApiPingStatus.PINGING) return
        if (current.antigravityAuth.status != AntigravityAuthStatus.SIGNED_IN) {
            _state.update { it.copy(
                antigravityModelTestStatus = ApiPingStatus.FAILED,
                antigravityModelTestMessage = "Connect Google before testing a model",
            ) }
            return
        }
        val selectedModel = current.antigravityModel
        if (selectedModel.isBlank()) {
            _state.update { it.copy(
                antigravityModelTestStatus = ApiPingStatus.FAILED,
                antigravityModelTestMessage = "Select a Google AI model first",
            ) }
            return
        }
        _state.update { it.copy(
            antigravityModelTestStatus = ApiPingStatus.PINGING,
            antigravityModelTestMessage = "Testing $selectedModel with Antigravity…",
        ) }
        viewModelScope.launch {
            val result = runCatching { antigravityRuntime.hello() }
            _state.update { state ->
                if (state.antigravityModel != selectedModel) {
                    state.copy(
                        antigravityModelTestStatus = ApiPingStatus.IDLE,
                        antigravityModelTestMessage = "Model changed. Test the newly selected model.",
                    )
                } else {
                    result.fold(
                        onSuccess = { reply -> state.copy(
                            antigravityModelTestStatus = ApiPingStatus.OK,
                            antigravityModelTestMessage = "$selectedModel responded: ${reply.trim().take(120)}",
                        ) },
                        onFailure = { error -> state.copy(
                            antigravityModelTestStatus = ApiPingStatus.FAILED,
                            antigravityModelTestMessage = error.message?.take(240) ?: "The selected model did not respond",
                        ) },
                    )
                }
            }
        }
    }

    private fun runGitHubCommand(command: String, input: String? = null): Pair<Int, String> {
        val runtime = installer.installedRuntime()
        val workspace = File(getApplication<Application>().filesDir, "workspaces/github-auth").apply { mkdirs() }
        val process = installer.process(
            runtime.proot,
            runtime.rootfs,
            workspace,
            mapOf("GH_CONFIG_DIR" to GITHUB_CONFIG_DIR),
            listOf("/usr/bin/bash", "-lc", command),
            guestWorkspacePath = "/workspace/github-auth",
            emulateHardLinks = false,
        )
        input?.let {
            process.outputStream.write(it.toByteArray())
            process.outputStream.flush()
            process.outputStream.close()
        }
        val exit = process.waitFor()
        val output = (process as? NativeSpawnProcess)?.outputFile?.let(::readProcessOutput).orEmpty()
        return exit to redactGitHubSensitiveOutput(sanitizeTerminalOutput(output)).trim()
    }

    private fun readProcessOutput(file: File): String = runCatching { file.readText() }.getOrDefault("")

    private fun stopGitHubAuthProcess(process: Process?) {
        if (process == null) return
        runCatching { process.outputStream.close() }
        if (process.isAlive) runCatching { process.destroy() }
        repeat(5) {
            if (!process.isAlive) return
            Thread.sleep(40)
        }
        if (process.isAlive) runCatching { process.destroyForcibly() }
    }

    private fun hardenGitHubCredentialStorage() {
        val (exit, output) = runGitHubCommand(
            "umask 077; mkdir -p /root/.config/gh; chmod 700 /root/.config/gh; " +
                "find /root/.config/gh -type d -exec chmod 700 {} +; " +
                "find /root/.config/gh -type f -exec chmod 600 {} +",
        )
        check(exit == 0) { output.ifBlank { "Could not secure GitHub credential files" } }
    }

    private suspend fun refreshGitHubConnection() = withContext(Dispatchers.IO) {
        if (!installer.isInstalled() || !installer.isGitHubCliInstalled()) return@withContext
        val savedLogin = preferences.githubLogin.takeIf(String::isNotBlank)
        try {
            val (exit, output) = runGitHubCommand("gh api user --jq .login")
            if (exit == 0 && output.lineSequence().lastOrNull().orEmpty().isNotBlank()) {
                val account = output.lineSequence().last().trim()
                preferences.githubLogin = account
                _state.update {
                    it.copy(
                        githubAuthStatus = GitHubAuthStatus.CONNECTED,
                        githubLogin = account,
                        githubMessage = "Connected as @$account",
                        githubCanCancel = false,
                    )
                }
                return@withContext
            }
            when (classifyGitHubProbeFailure(output)) {
                GitHubProbeFailure.UNAUTHORIZED -> {
                    preferences.githubLogin = ""
                    _state.update {
                        it.copy(
                            githubAuthStatus = GitHubAuthStatus.DISCONNECTED,
                            githubLogin = null,
                            githubMessage = "GitHub authorization expired. Connect again.",
                            githubCanCancel = false,
                        )
                    }
                }
                GitHubProbeFailure.OFFLINE -> _state.update {
                    it.copy(
                        githubAuthStatus = if (savedLogin == null) GitHubAuthStatus.ERROR else GitHubAuthStatus.CONNECTED,
                        githubLogin = savedLogin,
                        githubMessage = if (savedLogin == null) "GitHub could not be reached. Check your connection and retry."
                            else "Offline: saved connection for @$savedLogin was kept. Verification will retry later.",
                        githubCanCancel = false,
                    )
                }
                GitHubProbeFailure.OTHER -> _state.update {
                    it.copy(
                        githubAuthStatus = GitHubAuthStatus.ERROR,
                        githubLogin = savedLogin,
                        githubMessage = output.takeLast(240).ifBlank { "Could not verify GitHub connection" },
                        githubCanCancel = false,
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _state.update {
                it.copy(
                    githubAuthStatus = GitHubAuthStatus.ERROR,
                    githubLogin = savedLogin,
                    githubMessage = "Could not check GitHub connection: ${error.message?.take(160) ?: "runtime error"}",
                    githubCanCancel = false,
                )
            }
        }
    }

    fun refreshGitHubConnectionNow() {
        _state.update { it.copy(githubAuthStatus = GitHubAuthStatus.STARTING, githubMessage = "Checking GitHub connection…", githubCanCancel = false) }
        viewModelScope.launch { refreshGitHubConnection() }
    }

    fun refreshGitHubRepositories() {
        val current = _state.value
        if (current.githubAuthStatus != GitHubAuthStatus.CONNECTED || current.githubRepositoriesLoading) return
        _state.update { it.copy(githubRepositoriesLoading = true, githubRepositoryMessage = "Loading repositories…") }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val (exit, output) = runGitHubCommand(
                    "gh repo list --limit 100 --json nameWithOwner,url,isPrivate,description,updatedAt",
                )
                check(exit == 0) { output.takeLast(300).ifBlank { "Could not load GitHub repositories" } }
                val repositories = parseGitHubRepositories(output)
                _state.update {
                    it.copy(
                        githubRepositories = repositories,
                        githubRepositoriesLoading = false,
                        githubRepositoryMessage = if (repositories.isEmpty()) "No repositories found for this account"
                            else "${repositories.size} repositories available",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        githubRepositoriesLoading = false,
                        githubRepositoryMessage = redactGitHubSensitiveOutput(error.message.orEmpty())
                            .take(240).ifBlank { "Could not load GitHub repositories" },
                    )
                }
            }
        }
    }

    fun cloneGitHubRepository(repository: GitHubRepository) {
        val current = _state.value
        if (current.githubAuthStatus != GitHubAuthStatus.CONNECTED ||
            current.githubCloneInProgress != null || current.isRunning || current.projectTerminalRunning
        ) return
        val baseSlug = projectSlug(repository.name)
        val usedSlugs = current.projects.mapTo(mutableSetOf()) { it.slug }
        val slug = generateSequence(1) { it + 1 }
            .map { number -> if (number == 1) baseSlug else "$baseSlug-$number" }
            .first { it !in usedSlugs }
        val project = Project(
            name = repository.name,
            description = repository.description.ifBlank { "GitHub repository · ${repository.nameWithOwner}" },
            language = "Git",
            slug = slug,
        )
        _state.update { it.copy(githubCloneInProgress = repository.nameWithOwner, githubRepositoryMessage = "Cloning ${repository.nameWithOwner}…") }
        viewModelScope.launch(Dispatchers.IO) {
            val workspace = File(getApplication<Application>().filesDir, "workspaces/${project.id}").apply { mkdirs() }
            try {
                val runtime = installer.installedRuntime()
                val process = installer.process(
                    runtime.proot,
                    runtime.rootfs,
                    workspace,
                    mapOf("GH_CONFIG_DIR" to GITHUB_CONFIG_DIR),
                    listOf(
                        "/usr/bin/bash", "-lc",
                        "gh repo clone ${shellQuote(repository.nameWithOwner)} . -- --depth 1",
                    ),
                    guestWorkspacePath = projectGuestRoot(project),
                    emulateHardLinks = false,
                )
                val exit = process.waitFor()
                val output = (process as? NativeSpawnProcess)?.outputFile?.let(::readProcessOutput).orEmpty()
                check(exit == 0) {
                    redactGitHubSensitiveOutput(sanitizeTerminalOutput(output)).takeLast(500)
                        .ifBlank { "Repository clone failed" }
                }
                val firstChat = ProjectChat(title = "Main chat")
                preferences.saveProjectChats(project.id, listOf(firstChat))
                _state.update { state ->
                    state.copy(
                        projects = listOf(project) + state.projects,
                        githubCloneInProgress = null,
                        githubRepositoryMessage = "Cloned ${repository.nameWithOwner}",
                        toastMessage = "Repository cloned. Opening ${project.name}…",
                    )
                }
                preferences.saveProjects(_state.value.projects)
                withContext(Dispatchers.Main) { openProject(project) }
            } catch (cancelled: CancellationException) {
                workspace.deleteRecursively()
                _state.update { it.copy(githubCloneInProgress = null) }
                throw cancelled
            } catch (error: Exception) {
                workspace.deleteRecursively()
                _state.update {
                    it.copy(
                        githubCloneInProgress = null,
                        githubRepositoryMessage = redactGitHubSensitiveOutput(error.message.orEmpty())
                            .take(300).ifBlank { "Repository clone failed" },
                    )
                }
            }
        }
    }

    private fun gitHubWorkSnapshotCommand(): String = """
        if [ ! -d .git ]; then
          printf '__REPOSITORY__\nno\n__BRANCH__\n\n__BASE__\nmain\n__STATUS__\n\n__DIFF__\n'
          exit 0
        fi
        base=${'$'}(git symbolic-ref --quiet --short refs/remotes/origin/HEAD 2>/dev/null | sed 's#^origin/##' || true)
        if [ -z "${'$'}base" ]; then
          if git show-ref --verify --quiet refs/remotes/origin/main; then base=main
          elif git show-ref --verify --quiet refs/remotes/origin/master; then base=master
          else base=main; fi
        fi
        printf '__REPOSITORY__\nyes\n__BRANCH__\n%s\n__BASE__\n%s\n__STATUS__\n' "${'$'}(git branch --show-current)" "${'$'}base"
        git status --short
        printf '__DIFF__\n'
        { git diff --stat; git diff --cached --stat; } | awk 'NF && !seen[${'$'}0]++'
    """.trimIndent()

    private fun runGitHubProjectCommand(project: Project, command: String, timeoutMs: Long = GITHUB_WORK_TIMEOUT_MS): Pair<Int, String> {
        val runtime = installer.installedRuntime()
        val workspace = projectWorkspaceRoot(project)
        val process = installer.process(
            runtime.proot,
            runtime.rootfs,
            workspace,
            mapOf("GH_CONFIG_DIR" to GITHUB_CONFIG_DIR),
            listOf("/usr/bin/bash", "-lc", "cd -- ${shellQuote(projectGuestRoot(project))} && $command"),
            guestWorkspacePath = projectGuestRoot(project),
            emulateHardLinks = false,
        )
        githubWorkProcess = process
        val deadline = System.currentTimeMillis() + timeoutMs
        while (process.isAlive && System.currentTimeMillis() < deadline) Thread.sleep(100)
        if (process.isAlive) {
            process.destroy()
            Thread.sleep(250)
            if (process.isAlive) process.destroyForcibly()
            githubWorkProcess = null
            error("GitHub work timed out")
        }
        val exit = process.waitFor()
        val output = (process as? NativeSpawnProcess)?.outputFile?.let(::readProcessOutput).orEmpty()
        githubWorkProcess = null
        return exit to redactGitHubSensitiveOutput(sanitizeTerminalOutput(output)).trim()
    }

    fun refreshGitHubWork() {
        val project = _state.value.activeProject ?: return
        if (_state.value.githubWorkRunning || _state.value.isRunning || _state.value.projectTerminalRunning) return
        _state.update { it.copy(githubWorkRunning = true, githubWorkMessage = "Checking Git repository…") }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { runGitHubProjectCommand(project, gitHubWorkSnapshotCommand(), GITHUB_WORK_READ_TIMEOUT_MS) }
                .onSuccess { (exit, output) ->
                    val snapshot = if (exit == 0) parseGitHubWorkSnapshot(output) else GitHubWorkSnapshot()
                    _state.update { current ->
                        if (current.activeProject?.id != project.id) current else current.copy(
                            githubWork = snapshot,
                            githubWorkRunning = false,
                            githubWorkChecksPassed = current.githubWorkChecksPassed &&
                                snapshot.status == current.githubWork.status,
                            githubWorkPullRequestUrl = current.githubWorkPullRequestUrl,
                            githubWorkMessage = when {
                                exit != 0 -> output.takeLast(300).ifBlank { "Could not inspect Git repository" }
                                !snapshot.isRepository -> "This project is not a Git repository"
                                isProtectedGitBranch(snapshot.branch, snapshot.baseBranch) -> "Protected branch detected. Prepare a work branch before editing."
                                snapshot.status.isBlank() -> "Work branch is clean"
                                else -> "${snapshot.status.lineSequence().count()} changed files ready for review"
                            },
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { current ->
                        if (current.activeProject?.id != project.id) current else current.copy(
                            githubWorkRunning = false,
                            githubWorkMessage = redactGitHubSensitiveOutput(error.message.orEmpty()).take(300).ifBlank { "Could not inspect Git repository" },
                        )
                    }
                }
        }
    }

    private fun prepareGitHubWorkBranchCommand(branchSeed: String): String = """
        set -e
        test -d .git || { echo 'This project is not a Git repository'; exit 2; }
        base=${'$'}(git symbolic-ref --quiet --short refs/remotes/origin/HEAD 2>/dev/null | sed 's#^origin/##' || true)
        if [ -z "${'$'}base" ]; then
          if git show-ref --verify --quiet refs/remotes/origin/main; then base=main
          elif git show-ref --verify --quiet refs/remotes/origin/master; then base=master
          else base=main; fi
        fi
        branch=${'$'}(git branch --show-current)
        case "${'$'}branch" in main|master|"${'$'}base"|'') git switch -c ${shellQuote(branchSeed)} ;; esac
        ${gitHubWorkSnapshotCommand()}
    """.trimIndent()

    fun prepareGitHubWorkBranch() {
        val project = _state.value.activeProject ?: return
        val current = _state.value
        if (current.githubAuthStatus != GitHubAuthStatus.CONNECTED || current.githubWorkRunning || current.isRunning || current.projectTerminalRunning) return
        _state.update { it.copy(githubWorkRunning = true, githubWorkMessage = "Preparing a protected work branch…", githubWorkPullRequestUrl = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val branchSeed = "agent/mobile-harness-${System.currentTimeMillis().toString().takeLast(10)}"
            val command = prepareGitHubWorkBranchCommand(branchSeed)
            runCatching { runGitHubProjectCommand(project, command) }
                .onSuccess { (exit, output) ->
                    val snapshot = parseGitHubWorkSnapshot(output)
                    _state.update { state ->
                        if (state.activeProject?.id != project.id) state else state.copy(
                            githubWork = snapshot,
                            githubWorkRunning = false,
                            githubWorkChecksPassed = false,
                            githubWorkCheckCommand = "",
                            githubWorkCheckOutput = "",
                            githubWorkMessage = if (exit == 0 && snapshot.isRepository && !isProtectedGitBranch(snapshot.branch, snapshot.baseBranch))
                                "Safe work branch ready: ${snapshot.branch}" else output.takeLast(400).ifBlank { "Could not prepare a work branch" },
                        )
                    }
                }
                .onFailure { error -> _state.update { it.copy(githubWorkRunning = false, githubWorkMessage = error.message?.take(300) ?: "Could not prepare a work branch") } }
        }
    }

    fun runGitHubWorkChecks(command: String) {
        val project = _state.value.activeProject ?: return
        val checkCommand = command.trim()
        val current = _state.value
        if (checkCommand.isBlank() || current.githubWorkRunning || current.isRunning || current.projectTerminalRunning ||
            !current.githubWork.isRepository || isProtectedGitBranch(current.githubWork.branch, current.githubWork.baseBranch)
        ) return
        _state.update { it.copy(
            githubWorkRunning = true,
            githubWorkChecksPassed = false,
            githubWorkCheckCommand = checkCommand,
            githubWorkCheckOutput = "",
            githubWorkMessage = "Running checks…",
            githubChatWorkflow = it.githubChatWorkflow.copy(stage = GitHubChatStage.CHECKING, message = "Running checks before PR publication…"),
        ) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { runGitHubProjectCommand(project, checkCommand, GITHUB_WORK_CHECK_TIMEOUT_MS) }
                .onSuccess { (exit, output) ->
                    _state.update { state -> state.copy(
                        githubWorkRunning = false,
                        githubWorkChecksPassed = exit == 0,
                        githubWorkCheckOutput = output.takeLast(6_000),
                        githubWorkMessage = if (exit == 0) "Checks passed" else "Checks failed with exit code $exit",
                        githubChatWorkflow = state.githubChatWorkflow.copy(
                            stage = if (exit == 0) GitHubChatStage.READY_TO_PUBLISH else GitHubChatStage.REVIEW,
                            message = if (exit == 0) "Checks passed. Review and confirm PR publication." else "Checks failed. Fix the issue or run checks again.",
                        ),
                    ) }
                    refreshGitHubWork()
                }
                .onFailure { error -> _state.update { it.copy(
                    githubWorkRunning = false,
                    githubWorkChecksPassed = false,
                    githubWorkCheckOutput = error.message.orEmpty().takeLast(2_000),
                    githubWorkMessage = "Checks failed",
                    githubChatWorkflow = it.githubChatWorkflow.copy(stage = GitHubChatStage.ERROR, message = "Checks could not complete."),
                ) } }
        }
    }

    fun publishGitHubPullRequest(commitMessage: String, pullRequestTitle: String) {
        val project = _state.value.activeProject ?: return
        val current = _state.value
        val commit = commitMessage.trim()
        val title = pullRequestTitle.trim()
        val work = current.githubWork
        if (commit.isBlank() || title.isBlank() || current.githubWorkRunning || current.isRunning || current.projectTerminalRunning ||
            current.githubAuthStatus != GitHubAuthStatus.CONNECTED || !work.isRepository ||
            isProtectedGitBranch(work.branch, work.baseBranch) || !current.githubWorkChecksPassed
        ) return
        _state.update { it.copy(
            githubWorkRunning = true,
            githubWorkMessage = "Committing, pushing, and opening a pull request…",
            githubWorkPullRequestUrl = null,
            githubChatWorkflow = it.githubChatWorkflow.copy(stage = GitHubChatStage.PUBLISHING, message = "Publishing the confirmed pull request…"),
        ) }
        viewModelScope.launch(Dispatchers.IO) {
            val body = buildString {
                append("Created from Mobile Harness GitHub Work Mode.\\n\\n")
                append("Checks: `")
                append(current.githubWorkCheckCommand.replace("`", "'"))
                append("` (passed)")
            }
            val command = """
                set -e
                branch=${'$'}(git branch --show-current)
                case "${'$'}branch" in ''|main|master|${shellQuote(work.baseBranch)}) echo 'Refusing to publish from a protected branch'; exit 3 ;; esac
                git add -A
                if ! git diff --cached --quiet; then git commit -m ${shellQuote(commit)}; fi
                test "${'$'}(git rev-list --count origin/${shellQuote(work.baseBranch)}..HEAD)" -gt 0 || { echo 'No commits to publish'; exit 4; }
                git push --set-upstream origin HEAD
                gh pr create --base ${shellQuote(work.baseBranch)} --head "${'$'}branch" --title ${shellQuote(title)} --body ${shellQuote(body)}
            """.trimIndent()
            runCatching { runGitHubProjectCommand(project, command, GITHUB_WORK_PUBLISH_TIMEOUT_MS) }
                .onSuccess { (exit, output) ->
                    val url = if (exit == 0) extractGitHubPullRequestUrl(output) else null
                    _state.update { state -> state.copy(
                        githubWorkRunning = false,
                        githubWorkPullRequestUrl = url,
                        githubWorkMessage = if (exit == 0 && url != null) "Pull request opened successfully" else output.takeLast(800).ifBlank { "Could not open pull request" },
                        githubChatWorkflow = state.githubChatWorkflow.copy(
                            stage = if (exit == 0 && url != null) GitHubChatStage.COMPLETE else GitHubChatStage.ERROR,
                            message = if (exit == 0 && url != null) "Pull request opened successfully." else "PR publication failed. Review the error and retry.",
                            pullRequestUrl = url,
                        ),
                    ) }
                    refreshGitHubWork()
                }
                .onFailure { error -> _state.update { it.copy(
                    githubWorkRunning = false,
                    githubWorkMessage = redactGitHubSensitiveOutput(error.message.orEmpty()).take(500).ifBlank { "Could not open pull request" },
                    githubChatWorkflow = it.githubChatWorkflow.copy(stage = GitHubChatStage.ERROR, message = "PR publication failed. Review the error and retry."),
                ) } }
        }
    }

    fun cancelGitHubWork() {
        val process = githubWorkProcess ?: return
        runCatching { process.destroy() }
        viewModelScope.launch(Dispatchers.IO) {
            Thread.sleep(300)
            if (process.isAlive) runCatching { process.destroyForcibly() }
            if (githubWorkProcess === process) githubWorkProcess = null
            _state.update { it.copy(githubWorkRunning = false, githubWorkMessage = "GitHub work cancelled") }
        }
    }

    fun connectGitHub() {
        if (githubAuthJob?.isActive == true) return
        githubAuthCancelledByUser = false
        _state.update {
            it.copy(
                githubAuthStatus = GitHubAuthStatus.STARTING,
                githubMessage = "Preparing official GitHub CLI…",
                githubUserCode = null,
                githubVerificationUri = null,
                githubCanCancel = true,
            )
        }
        githubAuthJob = viewModelScope.launch(Dispatchers.IO) {
            var process: Process? = null
            try {
                withTimeout(GITHUB_LOGIN_TIMEOUT_MS) {
                    installer.ensureGitHubCliInstalled { progress -> _state.update { it.copy(githubMessage = progress.message) } }
                    val runtime = installer.installedRuntime()
                    val workspace = File(getApplication<Application>().filesDir, "workspaces/github-auth").apply { mkdirs() }
                    process = installer.process(
                        runtime.proot,
                        runtime.rootfs,
                        workspace,
                        mapOf("GH_CONFIG_DIR" to GITHUB_CONFIG_DIR, "BROWSER" to "/bin/true"),
                        listOf("/usr/bin/bash", "-lc", GITHUB_LOGIN_COMMAND),
                        guestWorkspacePath = "/workspace/github-auth",
                        emulateHardLinks = false,
                    ).also { githubAuthProcess = it }
                    val native = process as? NativeSpawnProcess ?: error("GitHub login process is unavailable")
                    var offset = 0L
                    val output = StringBuilder()
                    var continued = false
                    while (process?.isAlive == true || native.outputFile.length() > offset) {
                        val available = native.outputFile.length() - offset
                        if (available <= 0L) { delay(80); continue }
                        val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
                        val count = RandomAccessFile(native.outputFile, "r").use { file -> file.seek(offset); file.read(bytes) }
                        if (count <= 0) continue
                        offset += count
                        output.append(bytes.decodeToString(0, count))
                        val clean = redactGitHubSensitiveOutput(sanitizeTerminalOutput(output.toString()))
                        val code = extractGitHubDeviceCode(clean)
                        if (code != null) {
                            _state.update {
                                it.copy(
                                    githubAuthStatus = GitHubAuthStatus.AWAITING_USER,
                                    githubUserCode = code,
                                    githubVerificationUri = GITHUB_DEVICE_URL,
                                    githubMessage = "Enter this one-time code on GitHub",
                                    githubCanCancel = true,
                                )
                            }
                            if (!continued) {
                                process?.outputStream?.write("\n".toByteArray())
                                process?.outputStream?.flush()
                                continued = true
                            }
                        }
                    }
                    val exit = process?.waitFor() ?: -1
                    check(exit == 0) {
                        redactGitHubSensitiveOutput(sanitizeTerminalOutput(output.toString())).takeLast(500)
                            .ifBlank { "GitHub sign-in failed" }
                    }
                    hardenGitHubCredentialStorage()
                    val (setupExit, setupOutput) = runGitHubCommand("gh auth setup-git")
                    check(setupExit == 0) {
                        setupOutput.takeLast(300).ifBlank { "Git credential setup failed. Retry GitHub connection." }
                    }
                    refreshGitHubConnection()
                    check(_state.value.githubAuthStatus == GitHubAuthStatus.CONNECTED) {
                        _state.value.githubMessage ?: "GitHub account verification failed"
                    }
                }
            } catch (timeout: TimeoutCancellationException) {
                _state.update {
                    it.copy(
                        githubAuthStatus = GitHubAuthStatus.ERROR,
                        githubMessage = "GitHub sign-in timed out. Retry when ready.",
                        githubUserCode = null,
                        githubVerificationUri = null,
                        githubCanCancel = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                if (githubAuthCancelledByUser) {
                    _state.update {
                        it.copy(
                            githubAuthStatus = GitHubAuthStatus.DISCONNECTED,
                            githubMessage = "GitHub sign-in cancelled",
                            githubUserCode = null,
                            githubVerificationUri = null,
                            githubCanCancel = false,
                        )
                    }
                } else {
                    throw cancelled
                }
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        githubAuthStatus = GitHubAuthStatus.ERROR,
                        githubMessage = redactGitHubSensitiveOutput(error.message.orEmpty()).take(240).ifBlank { "GitHub sign-in failed" },
                        githubUserCode = null,
                        githubVerificationUri = null,
                        githubCanCancel = false,
                    )
                }
            } finally {
                stopGitHubAuthProcess(process ?: githubAuthProcess)
                githubAuthProcess = null
                githubAuthJob = null
                githubAuthCancelledByUser = false
            }
        }
    }

    fun cancelGitHubConnection() {
        if (githubAuthJob?.isActive != true) return
        githubAuthCancelledByUser = true
        stopGitHubAuthProcess(githubAuthProcess)
        githubAuthJob?.cancel()
    }

    fun disconnectGitHub() {
        if (githubAuthJob?.isActive == true) return
        _state.update { it.copy(githubAuthStatus = GitHubAuthStatus.STARTING, githubMessage = "Signing out of GitHub…", githubCanCancel = false) }
        viewModelScope.launch(Dispatchers.IO) {
            val login = _state.value.githubLogin
            val command = if (login.isNullOrBlank()) "gh auth logout --hostname github.com" else "gh auth logout --hostname github.com --user ${shellQuote(login)}"
            val result = try {
                runGitHubCommand(command, "y\n")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                -1 to (error.message ?: "GitHub sign-out failed")
            }
            if (result.first == 0) {
                preferences.githubLogin = ""
                _state.update {
                    it.copy(
                        githubAuthStatus = GitHubAuthStatus.DISCONNECTED,
                        githubLogin = null,
                        githubUserCode = null,
                        githubVerificationUri = null,
                        githubMessage = "Signed out of GitHub",
                        githubCanCancel = false,
                        githubRepositories = emptyList(),
                        githubRepositoryMessage = null,
                        githubCloneInProgress = null,
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        githubAuthStatus = GitHubAuthStatus.ERROR,
                        githubMessage = redactGitHubSensitiveOutput(result.second).takeLast(240).ifBlank { "GitHub sign-out failed" },
                        githubCanCancel = false,
                    )
                }
            }
        }
    }

    override fun onCleared() {
        githubAuthJob?.cancel()
        stopGitHubAuthProcess(githubAuthProcess)
        githubAuthProcess = null
        stopGitHubAuthProcess(githubWorkProcess)
        githubWorkProcess = null
        super.onCleared()
    }

    fun getSavedApiKey(kind: ProviderKind): String = vault.get(kind.name).orEmpty()

    init {
        viewModelScope.launch { RuntimeSetupController.snapshot.collect(::onSetupSnapshot) }
        viewModelScope.launch { claudeRuntime.events.collect(::onRuntimeEvent) }
        viewModelScope.launch { dshRuntime.events.collect(::onRuntimeEvent) }
        viewModelScope.launch { antigravityRuntime.events.collect(::onRuntimeEvent) }
        viewModelScope.launch {
            antigravityAuthController.state.collect { auth ->
                _state.update { it.copy(antigravityAuth = auth) }
                if (auth.status == AntigravityAuthStatus.SIGNED_IN && _state.value.antigravityModels.isEmpty()) {
                    refreshAntigravityModels()
                }
            }
        }
        viewModelScope.launch { bootstrap() }
    }

    private suspend fun bootstrap() {
        val setupSnapshot = RuntimeSetupController.snapshot.value
        if (setupSnapshot.status == RuntimeSetupStatus.RUNNING) {
            onSetupSnapshot(setupSnapshot)
            resumeRuntimeSetupService()
            return
        }
        val installed = withContext(Dispatchers.IO) {
            // Upgrades from the old single-bundle layout keep every already-installed tool.
            installer.migrateLegacyToolMarkers()
            installer.isInstalled().also { ready ->
                if (ready) installer.cleanupLegacyWorkspaceScaffolding()
            }
        }
        _state.update { current ->
            val agents = buildSet {
                add(AgentKind.CLAUDE_CODE)
                if (installed && installer.isDeepSeekHarnessInstalled()) add(AgentKind.DEEPSEEK_HARNESS)
                if (installed && installer.isAntigravityInstalled()) add(AgentKind.ANTIGRAVITY)
            }
            val selected = if (current.selectedAgent in agents) current.selectedAgent else AgentKind.CLAUDE_CODE
            if (selected != current.selectedAgent) preferences.selectedAgentKind = selected.name
            current.copy(
                selectedAgent = selected,
                installedAgents = agents,
                installedDevStacks = if (installed) installer.installedStacks() else current.installedDevStacks,
            )
        }
        when {
            !installed && setupSnapshot.status == RuntimeSetupStatus.ERROR -> onSetupSnapshot(setupSnapshot)
            !installed -> _state.update { it.copy(startupStage = StartupStage.SETUP_REQUIRED, startupProgress = 0f) }
            !preferences.onboardingComplete -> {
                preferences.runtimeSetupComplete = true
                _state.update { it.copy(startupStage = StartupStage.MODEL_SETUP, startupProgress = 1f) }
            }
            else -> initializeRuntime()
        }
    }

    fun startRuntimeSetup() {
        if (state.value.startupStage == StartupStage.INSTALLING) return
        setupCompletionHandled = false
        _state.update {
            it.copy(
                startupStage = StartupStage.INSTALLING,
                startupProgress = 0.01f,
                startupMessage = "Preparing your private coding workspace",
                startupBytes = null,
                startupLogs = listOf("\$ Preparing your private coding workspace"),
                startupIndeterminate = false,
                startupError = null,
                startupErrorIsOffline = false,
            )
        }
        resumeRuntimeSetupService()
    }

    fun retryStartup() {
        if (installer.isInstalled()) viewModelScope.launch { initializeRuntime() } else {
            _state.update { it.copy(startupStage = StartupStage.SETUP_REQUIRED, startupError = null) }
            startRuntimeSetup()
        }
    }

    private fun resumeRuntimeSetupService() {
        val stacks = _state.value.selectedDevStacks.joinToString(",") { it.name }
        ContextCompat.startForegroundService(
            getApplication(),
            Intent(getApplication(), RuntimeSetupService::class.java)
                .setAction(RuntimeSetupService.ACTION_START)
                .putExtra(RuntimeSetupService.EXTRA_STACKS, stacks),
        )
    }

    private fun onSetupSnapshot(snapshot: RuntimeSetupSnapshot) {
        when (snapshot.status) {
            RuntimeSetupStatus.RUNNING -> _state.update {
                it.copy(
                    startupStage = StartupStage.INSTALLING,
                    startupProgress = snapshot.progress,
                    startupMessage = snapshot.message,
                    startupBytes = snapshot.totalBytes?.let { total -> (snapshot.downloadedBytes ?: 0L) to total },
                    startupLogs = snapshot.logs,
                    startupIndeterminate = snapshot.indeterminate,
                    startupError = null,
                    startupErrorIsOffline = false,
                )
            }
            RuntimeSetupStatus.COMPLETE -> {
                if (setupCompletionHandled) return
                setupCompletionHandled = true
                preferences.runtimeSetupComplete = true
                if (preferences.onboardingComplete) {
                    viewModelScope.launch { initializeRuntime() }
                } else {
                    _state.update {
                        it.copy(
                            startupStage = StartupStage.MODEL_SETUP,
                            startupProgress = 1f,
                            startupBytes = null,
                            startupIndeterminate = false,
                        )
                    }
                }
            }
            RuntimeSetupStatus.ERROR -> _state.update {
                it.copy(
                    startupStage = StartupStage.ERROR,
                    startupMessage = snapshot.message,
                    startupProgress = snapshot.progress,
                    startupLogs = snapshot.logs,
                    startupIndeterminate = false,
                    startupError = snapshot.errorMessage,
                    startupErrorIsOffline = snapshot.offline,
                )
            }
            RuntimeSetupStatus.CANCELLED -> _state.update {
                it.copy(
                    startupStage = StartupStage.SETUP_REQUIRED,
                    startupMessage = "Setup paused",
                    startupProgress = snapshot.progress,
                    startupLogs = snapshot.logs,
                    startupIndeterminate = false,
                )
            }
            RuntimeSetupStatus.IDLE -> Unit
        }
    }

    private suspend fun initializeRuntime() {
        val startedAt = SystemClock.elapsedRealtime()
        _state.update {
            it.copy(
                startupStage = StartupStage.INITIALIZING,
                startupProgress = 0.05f,
                startupMessage = "Opening your private workspace",
                startupBytes = null,
                startupLogs = listOf("\$ Opening your private workspace"),
                startupIndeterminate = false,
                startupError = null,
                startupErrorIsOffline = false,
            )
        }
        val result = runCatching {
            withContext(Dispatchers.IO) {
                installer.initializeExisting { progress ->
                    _state.update { current ->
                        current.copy(
                            startupProgress = 0.05f + progress.fraction * 0.95f,
                            startupMessage = progress.message,
                            startupBytes = null,
                            startupLogs = mergeStartupLog(current.startupLogs, progress),
                        )
                    }
                }
            }
        }
        if (result.isSuccess) {
            // The real version probe can finish in a fraction of a second on fast phones.
            // Keep the successful loading state visible long enough to be understandable.
            val remaining = MINIMUM_INITIALIZATION_SCREEN_MS - (SystemClock.elapsedRealtime() - startedAt)
            if (remaining > 0) delay(remaining)
            _state.update { it.copy(startupStage = StartupStage.READY, startupProgress = 1f) }
            pingApi()
            checkForAppUpdate()
        } else {
            showStartupError(result.exceptionOrNull() ?: IllegalStateException("Claude Code initialization failed"))
        }
    }

    fun checkForAppUpdate(force: Boolean = false, manual: Boolean = false) {
        if (_state.value.manualUpdateCheck == ManualUpdateCheck.CHECKING) return
        if (!force && System.currentTimeMillis() - preferences.lastAppUpdateCheckMillis < APP_UPDATE_CHECK_INTERVAL_MS) return
        if (manual) _state.update { it.copy(manualUpdateCheck = ManualUpdateCheck.CHECKING, manualUpdateCheckMessage = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { appUpdater().check() }
            // Only stamp successful fetches: a network failure must not blind the
            // auto-check for the whole throttle window.
            if (result.isSuccess) preferences.lastAppUpdateCheckMillis = System.currentTimeMillis()
            val update = result.getOrNull()
            if (update != null) {
                _state.update {
                    it.copy(
                        appUpdate = update,
                        appUpdateStatus = AppUpdateStatus.AVAILABLE,
                        appUpdateError = null,
                        appUpdateStage = null,
                        appUpdatePermissionNote = null,
                        manualUpdateCheck = if (manual) ManualUpdateCheck.AVAILABLE else it.manualUpdateCheck,
                        manualUpdateCheckMessage = if (manual) "v${update.versionName} is ready to install." else it.manualUpdateCheckMessage,
                    )
                }
            } else if (manual) {
                val failed = result.isFailure
                _state.update {
                    it.copy(
                        manualUpdateCheck = if (failed) ManualUpdateCheck.FAILED else ManualUpdateCheck.UP_TO_DATE,
                        manualUpdateCheckMessage = if (failed) {
                            result.exceptionOrNull()?.message?.take(160) ?: "Could not reach the update server. Check your connection."
                        } else {
                            "You're on the latest version (v${BuildConfig.VERSION_NAME})."
                        },
                    )
                }
            }
        }
    }

    /** Settings → "Check for Updates": always hits the network, with on-screen feedback. */
    fun checkForAppUpdateManual() = checkForAppUpdate(force = true, manual = true)

    /** Debug builds only: persist a manifest URL override and re-check immediately. */
    fun setDebugUpdateManifestUrl(url: String) {
        if (!BuildConfig.DEBUG) return
        preferences.debugUpdateManifestUrl = url.trim()
        preferences.lastAppUpdateCheckMillis = 0L
        checkForAppUpdate(force = true)
    }

    /** Debug builds only: clear the manifest URL override and re-check the default channel. */
    fun clearDebugUpdateManifestUrl() {
        if (!BuildConfig.DEBUG) return
        preferences.debugUpdateManifestUrl = ""
        preferences.lastAppUpdateCheckMillis = 0L
        checkForAppUpdate(force = true)
    }

    /** Debug builds only: the currently-active manifest URL override (empty = default). */
    fun debugUpdateManifestUrl(): String = if (BuildConfig.DEBUG) preferences.debugUpdateManifestUrl else ""

    /**
     * Entry point for every "Update now" button (dialog, Settings card, dashboard card).
     * If Android's "Install unknown apps" permission is missing, we redirect to system
     * Settings BEFORE downloading; the download starts automatically on return.
     */
    fun installAppUpdate() {
        val info = _state.value.appUpdate ?: return
        val status = _state.value.appUpdateStatus
        if (status == AppUpdateStatus.DOWNLOADING || status == AppUpdateStatus.INSTALLING) return
        if (!canInstallPackages()) {
            awaitingUnknownSourcesReturn = true
            _state.update {
                it.copy(
                    appUpdateStatus = AppUpdateStatus.PERMISSION_REQUIRED,
                    appUpdateError = null,
                    appUpdateStage = null,
                    appUpdatePermissionNote = "Opening system Settings\u2026 enable \u201cInstall unknown apps\u201d, then come back \u2014 download starts automatically.",
                )
            }
            openUnknownSourcesSettingsChannel.trySend(Unit)
            return
        }
        startAppUpdateDownload(info)
    }

    /** Fired when the user returns from the system "Install unknown apps" screen. */
    fun onReturnedFromUnknownSourcesSettings() {
        if (!awaitingUnknownSourcesReturn) return
        awaitingUnknownSourcesReturn = false
        val info = _state.value.appUpdate ?: return
        val status = _state.value.appUpdateStatus
        if (status == AppUpdateStatus.DOWNLOADING || status == AppUpdateStatus.INSTALLING) return
        if (canInstallPackages()) {
            startAppUpdateDownload(info)
        } else {
            _state.update {
                it.copy(
                    appUpdateStatus = AppUpdateStatus.PERMISSION_REQUIRED,
                    appUpdatePermissionNote = "Permission is still off \u2014 tap Update Now to open Settings again.",
                )
            }
        }
    }

    private fun canInstallPackages(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || getApplication<Application>().packageManager.canRequestPackageInstalls()

    private fun startAppUpdateDownload(info: AppUpdateInfo) {
        _state.update {
            it.copy(
                appUpdateStatus = AppUpdateStatus.DOWNLOADING,
                appUpdateDownloadedBytes = 0L,
                appUpdateTotalBytes = info.sizeBytes,
                appUpdateError = null,
                appUpdateStage = null,
                appUpdatePermissionNote = null,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                appUpdater().download(
                    info = info,
                    progress = { downloaded, total ->
                        _state.update { current -> current.copy(appUpdateDownloadedBytes = downloaded, appUpdateTotalBytes = total) }
                    },
                    onStage = { stage ->
                        _state.update { it.copy(appUpdateStatus = AppUpdateStatus.INSTALLING, appUpdateStage = stage) }
                    },
                )
            }.onSuccess { apk ->
                _state.update { it.copy(appUpdateStatus = AppUpdateStatus.INSTALLING, appUpdateStage = "Preparing system installer\u2026") }
                runCatching {
                    AndroidAppInstaller.install(getApplication(), apk) { written, total ->
                        val pct = if (total > 0) ((written.toFloat() / total) * 100).toInt().coerceIn(0, 100) else 0
                        _state.update { it.copy(appUpdateStage = "Preparing system installer\u2026 $pct%") }
                    }
                }.onSuccess {
                    _state.update { it.copy(appUpdateStage = "Launching installer\u2026") }
                }.onFailure { error ->
                    _state.update { it.copy(appUpdateStatus = AppUpdateStatus.ERROR, appUpdateError = error.message ?: "Could not start the Android installer", appUpdateStage = null) }
                }
            }.onFailure { error ->
                _state.update { it.copy(appUpdateStatus = AppUpdateStatus.ERROR, appUpdateError = error.message ?: "Update download failed", appUpdateStage = null) }
            }
        }
    }

    fun dismissAppUpdateError() {
        _state.update { it.copy(appUpdateStatus = AppUpdateStatus.AVAILABLE, appUpdateError = null, appUpdateStage = null, appUpdatePermissionNote = null) }
    }

    private fun mergeStartupLog(
        existing: List<String>,
        progress: RuntimeInstallProgress,
    ): List<String> {
        val prefix = "\$ ${progress.message}"
        val bytes = progress.totalBytes?.let { total ->
            val downloaded = progress.downloadedBytes ?: 0L
            " — %.1f / %.1f MB".format(downloaded / 1_048_576.0, total / 1_048_576.0)
        }.orEmpty()
        val nextLine = prefix + bytes
        val updated = if (existing.lastOrNull()?.startsWith(prefix) == true) {
            existing.dropLast(1) + nextLine
        } else {
            existing + nextLine
        }
        return updated.takeLast(80)
    }

    private fun showStartupError(error: Throwable) {
        val isOffline = generateSequence(error as Throwable?) { it.cause }
            .any { cause ->
                cause is UnknownHostException ||
                    cause.message.orEmpty().contains("unable to resolve host", ignoreCase = true) ||
                    cause.message.orEmpty().contains("no address associated with hostname", ignoreCase = true)
            }
        val message = if (isOffline) {
            "Connect to Wi-Fi or mobile data, then try again. Internet is required to finish the first-time setup."
        } else {
            error.message?.take(300) ?: "Something went wrong while preparing Mobile Harness. Please try again."
        }
        _state.update {
            it.copy(
                startupStage = StartupStage.ERROR,
                startupError = message,
                startupErrorIsOffline = isOffline,
            )
        }
    }

    fun finishOnboarding(profile: ProviderProfile, secret: String) {
        vault.put(profile.kind.name, secret)
        val saved = profile.copy(
            hasSecret = secret.isNotBlank() || vault.contains(profile.kind.name) || profile.kind == ProviderKind.CLAUDE,
        )
        preferences.saveProvider(saved)
        preferences.onboardingComplete = true
        _state.update { it.copy(onboardingComplete = true, provider = saved, startupStage = StartupStage.READY) }
        refreshUsageState()
        pingApi()
    }

    fun updateProvider(profile: ProviderProfile, secret: String) = finishOnboarding(profile, secret)

    fun finishBackgroundSetup() {
        preferences.backgroundSetupComplete = true
        _state.update { it.copy(backgroundSetupComplete = true) }
    }

    /** Called from the first-launch tool picker; persists the choice for setup and Settings. */
    fun toggleDevStack(stack: DevStack) {
        if (stack == DevStack.WEB) return
        val updated = _state.value.selectedDevStacks.toMutableSet().apply {
            if (!add(stack)) remove(stack)
        }
        preferences.selectedDevStacks = updated.map { it.name }.toSet()
        _state.update { it.copy(selectedDevStacks = updated) }
    }

    /** Installs one development stack on demand (Settings) with live progress. */
    fun installDevStack(stack: DevStack) {
        if (_state.value.devStackInstalling != null) return
        _state.update {
            it.copy(
                devStackInstalling = stack,
                devStackMessage = "Preparing ${stack.label}…",
                devStackProgress = 0f,
                devStackBytes = null,
            )
        }
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    installer.ensureStackInstalled(stack) { progress ->
                        _state.update { current ->
                            current.copy(
                                devStackMessage = progress.message,
                                devStackProgress = progress.fraction.coerceIn(0f, 1f),
                                devStackBytes = progress.totalBytes?.let { total ->
                                    (progress.downloadedBytes ?: 0L) to total
                                },
                            )
                        }
                    }
                }
            }
            _state.update { current ->
                current.copy(
                    devStackInstalling = null,
                    installedDevStacks = if (result.isSuccess) current.installedDevStacks + stack else current.installedDevStacks,
                    devStackProgress = 0f,
                    devStackBytes = null,
                    devStackMessage = result.fold(
                        onSuccess = { "${stack.label} tools are ready" },
                        onFailure = { _ -> result.exceptionOrNull()?.message?.take(200) ?: "Could not install ${stack.label}" },
                    ),
                )
            }
        }
    }

    /** First usable key: a freshly typed pool wins, else the first non-exhausted saved key. */
    private fun activeKey(profile: ProviderProfile, typed: String): String {
        ApiKeyPool.parse(typed).firstOrNull()?.let { return it }
        return exhaustedKeys.snapshot(profile.kind.name, ApiKeyPool.parse(vault.get(profile.kind.name))).available.firstOrNull().orEmpty()
    }

    suspend fun discoverModels(profile: ProviderProfile, secret: String): ModelDiscoveryResult {
        val key = activeKey(profile, secret)
        return providerApi.discoverModels(profile.baseUrl, key, profile.kind.protocol)
    }

    suspend fun validateProvider(
        profile: ProviderProfile,
        secret: String,
        models: List<com.jarves.mh.network.DiscoveredModel>,
    ): ConnectionValidation {
        val key = activeKey(profile, secret)
        return providerApi.validate(profile.baseUrl, profile.model, key, profile.kind.protocol, models)
    }

    fun pingApi() {
        val profile = _state.value.provider
        if (profile.baseUrl.isBlank() || profile.model.isBlank()) return
        if (_state.value.apiPingStatus == ApiPingStatus.PINGING) return
        _state.update { it.copy(apiPingStatus = ApiPingStatus.PINGING, apiPingMessage = "Sending a minimal test request…") }
        viewModelScope.launch {
            val key = activeKey(profile, "")
            val result = providerApi.validate(profile.baseUrl, profile.model, key, profile.kind.protocol, emptyList())
            when (result) {
                is ConnectionValidation.Success -> _state.update {
                    it.copy(apiPingStatus = ApiPingStatus.OK, apiPingMessage = "API responded successfully")
                }
                is ConnectionValidation.Failure -> _state.update {
                    it.copy(apiPingStatus = ApiPingStatus.FAILED, apiPingMessage = result.message)
                }
            }
        }
    }

    fun switchActiveProject(project: Project) {
        val current = _state.value
        if (current.isRunning || current.projectTerminalRunning || current.activeProject?.id == project.id) return
        persistMessages()
        openProject(project)
    }

    fun openProject(project: Project) {
        closeFile()
        configureProjectRoot(project.id, project.rootPath)
        val terminal = loadProjectTerminal(project)
        val suggestedRoot = if (project.rootPath.isBlank()) detectNestedProjectRoot(project) else null
        val chats = preferences.loadProjectChats(project.id).ifEmpty {
            listOf(ProjectChat(title = "Main chat")).also { preferences.saveProjectChats(project.id, it) }
        }
        val activeChat = chats.first()
        val saved = preferences.loadMessages(project.id, activeChat.id)
        val msgs = saved.ifEmpty { listOf(ChatMessage(fromUser = false, text = "Hi! Tell me what you want to build or change.")) }
        _state.update {
            it.copy(
                activeProject = project,
                projectChats = chats,
                activeChatId = activeChat.id,
                messages = msgs,
                liveProcess = emptyList(),
                liveThinking = false,
                taskStartedAtMillis = null,
                taskFinishedAtMillis = null,
                changes = emptyList(),
                workspaceFiles = emptyList(),
                androidProjectDetected = false,
                filesLoading = true,
                projectTerminalLines = terminal.lines,
                projectTerminalLiveOutput = "",
                projectTerminalRunning = false,
                projectTerminalCwd = terminal.cwd,
                projectTerminalCommand = null,
                projectTerminalDraft = null,
                pendingTerminalCommand = null,
                suggestedProjectRoot = suggestedRoot,
                previewReady = false,
                previewUrl = null,
                pendingAttachments = emptyList(),
                githubWork = GitHubWorkSnapshot(),
                githubWorkRunning = false,
                githubWorkChecksPassed = false,
                githubWorkCheckCommand = "",
                githubWorkCheckOutput = "",
                githubWorkPullRequestUrl = null,
                githubWorkMessage = null,
                githubChatWorkflow = GitHubChatWorkflow(),
            )
        }
        refreshProjectFiles()
        refreshGitHubWork()
        viewModelScope.launch {
            val pending = activeRuntime().loadPendingChanges(project.id)
            if (_state.value.activeProject?.id == project.id) _state.update { it.copy(changes = pending) }
        }
    }

    fun closeProject() {
        closeFile()
        val active = _state.value.activeProject
        persistMessages()
        if (_state.value.isRunning) {
            viewModelScope.launch { activeRuntime().stopActiveSession() }
        }
        if (_state.value.projectTerminalRunning) stopProjectTerminalCommand()

        if (active != null) {
            val chats = preferences.loadProjectChats(active.id)
            val userMessages = chats.sumOf { preferences.loadMessages(active.id, it.id).count { m -> m.fromUser } }
            val workspaceDir = File(getApplication<Application>().filesDir, "workspaces/${active.id}")
            val userFiles = if (workspaceDir.isDirectory) {
                workspaceDir.walkTopDown().filter { file ->
                    file.isFile && !file.name.startsWith(".claude") && file.name != ".pocket-dev-stacks.json"
                }.count()
            } else 0

            if (userMessages == 0 && userFiles == 0 && !_state.value.isRunning && !_state.value.projectTerminalRunning) {
                // Unused empty project; delete immediately so it does not clutter the project list.
                _state.update { current -> current.copy(projects = current.projects.filterNot { it.id == active.id }) }
                preferences.saveProjects(_state.value.projects)
                viewModelScope.launch(Dispatchers.IO) {
                    workspaceDir.deleteRecursively()
                    terminalHistoryFile(active.id).delete()
                    preferences.deleteProjectChats(active.id)
                }
            }
        }

        _state.update {
            it.copy(
                activeProject = null,
                projectChats = emptyList(),
                activeChatId = null,
                changes = emptyList(),
                workspaceFiles = emptyList(),
                androidProjectDetected = false,
                filesLoading = false,
                isRunning = false,
                activeSessionId = null,
                pendingApproval = null,
                projectTerminalLines = emptyList(),
                projectTerminalLiveOutput = "",
                projectTerminalRunning = false,
                projectTerminalCwd = "/workspace",
                projectTerminalCommand = null,
                projectTerminalDraft = null,
                pendingTerminalCommand = null,
                suggestedProjectRoot = null,
                previewReady = false,
                previewUrl = null,
                pendingAttachments = emptyList(),
            )
        }
    }

    fun consumeToast() = _state.update { it.copy(toastMessage = null) }

    fun createProject(name: String) {
        if (name.isBlank()) return
        closeFile()
        val baseSlug = projectSlug(name)
        val usedSlugs = _state.value.projects.mapTo(mutableSetOf()) { it.slug }
        val slug = generateSequence(1) { it + 1 }
            .map { number -> if (number == 1) baseSlug else "$baseSlug-$number" }
            .first { it !in usedSlugs }
        val project = Project(
            name = name.trim(),
            description = "Starter web project",
            language = "TypeScript",
            slug = slug,
        )
        configureProjectRoot(project.id, project.rootPath)
        val guestRoot = projectGuestRoot(project)
        _state.update {
            it.copy(
                projects = listOf(project) + it.projects,
                activeProject = project,
                messages = listOf(ChatMessage(fromUser = false, text = "Hi! Tell me what you want to build or change.")),
                liveProcess = emptyList(),
                liveThinking = false,
                taskStartedAtMillis = null,
                taskFinishedAtMillis = null,
                changes = emptyList(),
                workspaceFiles = emptyList(),
                androidProjectDetected = false,
                filesLoading = true,
                projectTerminalLines = emptyList(),
                projectTerminalLiveOutput = "",
                projectTerminalRunning = false,
                projectTerminalCwd = guestRoot,
                projectTerminalCommand = null,
                projectTerminalDraft = null,
                pendingTerminalCommand = null,
                suggestedProjectRoot = null,
                previewReady = false,
                previewUrl = null,
            )
        }
        preferences.saveProjects(_state.value.projects)
        File(getApplication<Application>().filesDir, "workspaces/${project.id}").mkdirs()
        val firstChat = ProjectChat(title = "New chat")
        preferences.saveProjectChats(project.id, listOf(firstChat))
        _state.update { it.copy(projectChats = listOf(firstChat), activeChatId = firstChat.id) }
        refreshProjectFiles()
    }

    fun createQuickProject() {
        val identity = generateQuickChatIdentity(_state.value.projects.mapTo(mutableSetOf()) { it.slug })
        val project = Project(
            name = identity.displayName,
            description = "Quick project workspace",
            language = "General",
            slug = identity.slug,
            kind = ProjectKind.QUICK_PROJECT,
        )
        val firstChat = ProjectChat(title = "New chat")
        File(getApplication<Application>().filesDir, "workspaces/${project.id}").mkdirs()
        preferences.saveProjectChats(project.id, listOf(firstChat))
        _state.update { it.copy(projects = listOf(project) + it.projects) }
        preferences.saveProjects(_state.value.projects)
        openProject(project)
    }

    fun renameProject(projectId: String, newName: String) {
        val clean = newName.replace(Regex("\\s+"), " ").trim().take(60)
        if (clean.isBlank()) return
        _state.update { current ->
            val projects = current.projects.map { project ->
                if (project.id == projectId) project.copy(name = clean) else project
            }
            val active = current.activeProject?.let { project ->
                if (project.id == projectId) project.copy(name = clean) else project
            }
            current.copy(projects = projects, activeProject = active)
        }
        preferences.saveProjects(_state.value.projects)
    }

    fun deleteProject(projectId: String) {
        val project = _state.value.projects.firstOrNull { it.id == projectId } ?: return
        if (_state.value.activeProject?.id == projectId || _state.value.isRunning || _state.value.projectTerminalRunning) return
        _state.update { current -> current.copy(projects = current.projects.filterNot { it.id == projectId }) }
        preferences.saveProjects(_state.value.projects)
        viewModelScope.launch(Dispatchers.IO) {
            val filesDir = getApplication<Application>().filesDir
            File(filesDir, "workspaces/${project.id}").deleteRecursively()
            terminalHistoryFile(project.id).delete()
            preferences.deleteProjectChats(project.id)
        }
    }

    private fun detectNestedProjectRoot(project: Project): String? {
        val base = File(getApplication<Application>().filesDir, "workspaces/${project.id}")
        if (!base.isDirectory) return null
        val visible = base.listFiles().orEmpty().filterNot { file ->
            file.name == ".claude" || file.name == ".claude.json"
        }
        val onlyDirectory = visible.singleOrNull()?.takeIf(File::isDirectory) ?: return null
        val containsProjectFiles = onlyDirectory.walkTopDown()
            .maxDepth(2)
            .any { it.isFile && it.name !in setOf(".DS_Store", ".claude.json") }
        return onlyDirectory.name.takeIf { containsProjectFiles && !it.contains("..") }
    }

    fun useSuggestedProjectRoot() {
        val current = _state.value
        val project = current.activeProject ?: return
        val root = current.suggestedProjectRoot ?: return
        if (current.isRunning || current.projectTerminalRunning) return
        closeFile()
        val updated = project.copy(rootPath = root)
        configureProjectRoot(updated.id, updated.rootPath)
        val projects = current.projects.map { if (it.id == updated.id) updated else it }
        val guestRoot = projectGuestRoot(updated)
        preferences.saveProjects(projects)
        saveProjectTerminal(updated.id, guestRoot, current.projectTerminalLines)
        _state.update {
            it.copy(
                projects = projects,
                activeProject = updated,
                suggestedProjectRoot = null,
                projectTerminalCwd = guestRoot,
                changes = emptyList(),
                toastMessage = "$root is now the project root",
            )
        }
        refreshProjectFiles()
    }

    fun exportActiveProject(uri: Uri) {
        val current = _state.value
        val project = current.activeProject ?: return
        if (current.isRunning || current.projectTerminalRunning) {
            _state.update { it.copy(toastMessage = "Stop the running task before exporting") }
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val root = projectWorkspaceRoot(project)
                    val rootPath = root.canonicalFile.toPath()
                    val output = getApplication<Application>().contentResolver.openOutputStream(uri)
                        ?: error("The selected location could not be opened")
                    output.buffered().use { stream ->
                        ZipOutputStream(stream).use { zip ->
                            zip.putNextEntry(ZipEntry("${project.slug}/"))
                            zip.closeEntry()
                            root.walkTopDown()
                                .onEnter { directory ->
                                    if (directory == root) {
                                        true
                                    } else {
                                        val relative = directory.relativeTo(root).invariantSeparatorsPath
                                        !isExportExcludedPath(relative) &&
                                            !Files.isSymbolicLink(directory.toPath()) &&
                                            runCatching { directory.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
                                    }
                                }
                                .drop(1)
                                .filter { file ->
                                    !Files.isSymbolicLink(file.toPath()) &&
                                        runCatching { file.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false) &&
                                        !isExportExcludedPath(file.relativeTo(root).invariantSeparatorsPath)
                                }
                                .forEach { file ->
                                    val relative = file.relativeTo(root).invariantSeparatorsPath
                                    val entryName = "${project.slug}/$relative" + if (file.isDirectory) "/" else ""
                                    zip.putNextEntry(ZipEntry(entryName).apply { time = file.lastModified() })
                                    if (file.isFile) file.inputStream().buffered().use { it.copyTo(zip) }
                                    zip.closeEntry()
                                }
                        }
                    }
                }
            }
            _state.update {
                it.copy(
                    toastMessage = result.fold(
                        onSuccess = { "${project.slug}.zip exported" },
                        onFailure = { error -> "Export failed: ${error.message ?: "Unknown error"}" },
                    ),
                )
            }
        }
    }

    fun createChat() {
        val project = _state.value.activeProject ?: return
        if (_state.value.isRunning) return
        persistMessages()
        val chat = ProjectChat()
        val chats = listOf(chat) + _state.value.projectChats
        preferences.saveProjectChats(project.id, chats)
        _state.update {
            it.copy(
                projectChats = chats,
                activeChatId = chat.id,
                messages = listOf(ChatMessage(fromUser = false, text = "Hi! Tell me what you want to build or change.")),
                liveProcess = emptyList(),
                liveThinking = false,
                taskStartedAtMillis = null,
                taskFinishedAtMillis = null,
                pendingApproval = null,
                pendingAttachments = emptyList(),
            )
        }
    }

    fun switchChat(chatId: String) {
        val current = _state.value
        val project = current.activeProject ?: return
        if (current.isRunning || current.activeChatId == chatId) return
        val chat = current.projectChats.firstOrNull { it.id == chatId } ?: return
        persistMessages()
        val saved = preferences.loadMessages(project.id, chat.id)
        _state.update {
            it.copy(
                activeChatId = chat.id,
                messages = saved.ifEmpty { listOf(ChatMessage(fromUser = false, text = "Hi! Tell me what you want to build or change.")) },
                liveProcess = emptyList(),
                liveThinking = false,
                taskStartedAtMillis = null,
                taskFinishedAtMillis = null,
                pendingApproval = null,
                pendingAttachments = emptyList(),
            )
        }
    }

    fun refreshProjectFiles() {
        val project = _state.value.activeProject ?: return
        _state.update { it.copy(filesLoading = true) }
        viewModelScope.launch {
            val (entries, suggestedRoot, androidProjectDetected) = withContext(Dispatchers.IO) {
                Triple(
                    readWorkspace(project),
                    if (project.rootPath.isBlank()) detectNestedProjectRoot(project) else null,
                    findAndroidGradleProjectRoot(projectWorkspaceRoot(project)) != null,
                )
            }
            if (_state.value.activeProject?.id == project.id) {
                _state.update {
                    it.copy(
                        workspaceFiles = entries,
                        filesLoading = false,
                        suggestedProjectRoot = suggestedRoot,
                        androidProjectDetected = androidProjectDetected,
                    )
                }
            }
        }
    }

    fun openFile(entry: WorkspaceEntry) {
        if (entry.isDirectory) return
        val project = _state.value.activeProject ?: return
        val request = fileReadRequests.begin(project.id, entry.path)
        _state.update { it.copy(openedFilePath = entry.path, openedFileContent = null, fileContentLoading = true) }
        viewModelScope.launch {
            val content = withContext(Dispatchers.IO) {
                val file = File(projectWorkspaceRoot(project), entry.path)
                runCatching {
                    if (file.length() > 512_000L) {
                        file.inputStream().use { stream ->
                            val buf = ByteArray(512_000)
                            val read = stream.read(buf)
                            String(buf, 0, read)
                        } + "\n\n[File truncated — too large to display fully]"
                    } else {
                        file.readText()
                    }
                }.getOrElse { "Could not read file: ${it.message}" }
            }
            _state.update { current ->
                if (fileReadRequests.isCurrent(request, current.activeProject?.id, current.openedFilePath)) {
                    current.copy(openedFileContent = content, fileContentLoading = false)
                } else current
            }
        }
    }

    fun closeFile() {
        fileReadRequests.invalidate()
        _state.update { it.copy(openedFilePath = null, openedFileContent = null, fileContentLoading = false) }
    }


    private fun readWorkspace(project: Project): List<WorkspaceEntry> {
        val root = projectWorkspaceRoot(project)
        if (!root.isDirectory) return emptyList()
        val rootPath = root.canonicalFile.toPath()
        return root.walkTopDown()
            .maxDepth(12)
            .onEnter { directory ->
                val relative = if (directory == root) "" else directory.relativeTo(root).invariantSeparatorsPath
                directory == root || (!isClaudeRuntimeMetadata(relative) &&
                    !Files.isSymbolicLink(directory.toPath()) &&
                    runCatching { directory.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
                    )
            }
            .drop(1)
            .filter { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                !isClaudeRuntimeMetadata(relative) &&
                    !Files.isSymbolicLink(file.toPath()) &&
                    runCatching { file.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
            }
            .take(MAX_VISIBLE_WORKSPACE_ENTRIES)
            .map { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                WorkspaceEntry(
                    path = relative,
                    name = file.name,
                    isDirectory = file.isDirectory,
                    depth = relative.count { it == '/' },
                    sizeBytes = if (file.isFile) file.length() else 0,
                )
            }
            .sortedWith(compareBy<WorkspaceEntry> { it.path.lowercase() }.thenByDescending { it.isDirectory })
            .toList()
    }

    private fun isClaudeRuntimeMetadata(relativePath: String): Boolean {
        return relativePath == ".claude" ||
            relativePath == ".claude.json" ||
            relativePath.startsWith(".claude/")
    }

    private fun isExportExcludedPath(relativePath: String): Boolean {
        val excludedNames = setOf(
            ".git", ".claude", ".gradle", ".idea", ".next", ".cache",
            "node_modules", ".venv", "venv", "__pycache__", "build",
        )
        return relativePath.split('/').any { it in excludedNames } || isClaudeRuntimeMetadata(relativePath)
    }

    fun addChatAttachments(uris: List<Uri>) {
        val current = _state.value
        val project = current.activeProject ?: return
        val chatId = current.activeChatId ?: return
        if (current.isRunning || uris.isEmpty()) return
        val remaining = (MAX_ATTACHMENTS_PER_MESSAGE - current.pendingAttachments.size).coerceAtLeast(0)
        if (remaining == 0) {
            _state.update { it.copy(toastMessage = "You can attach up to $MAX_ATTACHMENTS_PER_MESSAGE files per message") }
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val added = mutableListOf<ChatAttachment>()
                val errors = mutableListOf<String>()
                uris.take(remaining).forEach { uri ->
                    runCatching { copyChatAttachment(project, chatId, uri) }
                        .onSuccess(added::add)
                        .onFailure { errors += (it.message ?: "Could not attach file") }
                }
                added to errors
            }
            val (added, errors) = result
            _state.update { state ->
                state.copy(
                    pendingAttachments = state.pendingAttachments + added,
                    toastMessage = errors.firstOrNull() ?: if (uris.size > remaining) "Only $remaining more file${if (remaining == 1) "" else "s"} could be added" else null,
                )
            }
            if (added.isNotEmpty()) refreshProjectFiles()
        }
    }

    fun removePendingAttachment(attachmentId: String) {
        val current = _state.value
        val project = current.activeProject ?: return
        val attachment = current.pendingAttachments.firstOrNull { it.id == attachmentId } ?: return
        _state.update { it.copy(pendingAttachments = it.pendingAttachments.filterNot { item -> item.id == attachmentId }) }
        viewModelScope.launch(Dispatchers.IO) {
            val root = projectWorkspaceRoot(project)
            val file = File(root, attachment.relativePath).canonicalFile
            if (file.toPath().startsWith(root.canonicalFile.toPath())) file.delete()
        }
    }

    fun openChatAttachment(attachment: ChatAttachment) {
        val project = _state.value.activeProject ?: return
        runCatching {
            val root = projectWorkspaceRoot(project).canonicalFile
            val file = File(root, attachment.relativePath).canonicalFile
            require(file.isFile && file.toPath().startsWith(root.toPath())) { "Attachment is unavailable" }
            val app = getApplication<Application>()
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, attachment.mimeType.ifBlank { "application/octet-stream" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            app.startActivity(intent)
        }.onFailure { error ->
            _state.update { it.copy(toastMessage = error.message ?: "No app can open this attachment") }
        }
    }

    private fun copyChatAttachment(project: Project, chatId: String, uri: Uri): ChatAttachment {
        val resolver = getApplication<Application>().contentResolver
        var displayName = "attachment"
        var declaredSize = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { displayName = cursor.getString(it) ?: displayName }
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { declaredSize = cursor.getLong(it) }
            }
        }
        val mimeType = resolver.getType(uri).orEmpty().ifBlank { "application/octet-stream" }
        val extension = displayName.substringAfterLast('.', "").lowercase()
        val supportedTextExtensions = setOf(
            "txt", "md", "markdown", "json", "jsonl", "csv", "tsv", "xml", "yaml", "yml", "log",
            "kt", "kts", "java", "py", "js", "mjs", "cjs", "ts", "tsx", "jsx", "html", "htm",
            "css", "scss", "sass", "less", "c", "cc", "cpp", "h", "hpp", "sh", "bash", "zsh",
            "gradle", "properties", "toml", "ini", "conf", "sql",
        )
        val supported = mimeType.startsWith("image/") ||
            mimeType.startsWith("text/") || mimeType == "application/json" || mimeType == "application/xml" ||
            mimeType.endsWith("+json") || mimeType.endsWith("+xml") || extension in supportedTextExtensions
        require(supported) { "Only images and text files are supported" }
        require(declaredSize <= MAX_ATTACHMENT_BYTES || declaredSize < 0) { "$displayName is larger than 25 MB" }
        val safeName = sanitizeAttachmentName(displayName)
        val root = projectWorkspaceRoot(project).canonicalFile
        val folder = File(root, "attachments/$chatId").apply { mkdirs() }.canonicalFile
        require(folder.toPath().startsWith(root.toPath())) { "Unsafe attachment folder" }
        val stem = safeName.substringBeforeLast('.', safeName)
        val safeExtension = safeName.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }
        var destination = File(folder, safeName)
        var suffix = 2
        while (destination.exists()) destination = File(folder, "$stem-${suffix++}$safeExtension")
        var copied = 0L
        try {
            resolver.openInputStream(uri)?.buffered()?.use { input ->
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        require(copied <= MAX_ATTACHMENT_BYTES) { "$displayName is larger than 25 MB" }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("Could not read $displayName")
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
        return ChatAttachment(
            displayName = displayName.take(120),
            relativePath = destination.relativeTo(root).invariantSeparatorsPath,
            mimeType = mimeType,
            sizeBytes = copied,
        )
    }

    private fun sanitizeAttachmentName(name: String): String {
        val clean = name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().trim('.').take(100)
        return clean.ifBlank { "attachment-${UUID.randomUUID().toString().take(8)}" }
    }

    private fun refreshUsageState() {
        val profile = _state.value.provider
        val keys = ApiKeyPool.parse(vault.get(profile.kind.name))
        val available = keys.indices.filterNot { exhaustedKeys.isExhausted(profile.kind.name, keys[it]) }
        val current = available.firstOrNull()
        val health = keys.mapIndexed { index, key ->
            val exhausted = exhaustedKeys.isExhausted(profile.kind.name, key)
            ApiKeyHealth(index + 1, key.takeLast(4), when { exhausted -> "Exhausted today"; index == current -> "Active / next"; else -> "Ready" }, index == current)
        }
        _state.update { it.copy(aiUsage = usageStore.snapshot(), apiKeyHealth = health) }
    }

    fun sendPrompt(prompt: String) {
        val current = state.value
        val project = current.activeProject ?: return
        val attachments = current.pendingAttachments
        if ((prompt.isBlank() && attachments.isEmpty()) || current.isRunning || current.githubWorkRunning) return
        val requestText = prompt.trim().ifBlank { "Please review the attached files." }
        if (current.githubAuthStatus == GitHubAuthStatus.CONNECTED) {
            _state.update { it.copy(
                githubWorkRunning = true,
                githubChatWorkflow = GitHubChatWorkflow(
                    stage = GitHubChatStage.PREPARING_BRANCH,
                    request = requestText,
                    checkCommand = "git diff --check",
                    commitMessage = defaultGitHubCommitMessage(requestText, project.name),
                    pullRequestTitle = defaultGitHubPullRequestTitle(requestText, project.name),
                    message = "Inspecting the repository and preparing a safe work branch…",
                ),
            ) }
            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    val (_, snapshotOutput) = runGitHubProjectCommand(project, gitHubWorkSnapshotCommand(), GITHUB_WORK_READ_TIMEOUT_MS)
                    var snapshot = parseGitHubWorkSnapshot(snapshotOutput)
                    if (snapshot.isRepository && isProtectedGitBranch(snapshot.branch, snapshot.baseBranch)) {
                        val branch = "agent/mobile-harness-${System.currentTimeMillis().toString().takeLast(10)}"
                        val (exit, output) = runGitHubProjectCommand(project, prepareGitHubWorkBranchCommand(branch))
                        if (exit != 0) error(output.ifBlank { "Could not prepare a safe work branch" })
                        snapshot = parseGitHubWorkSnapshot(output)
                    }
                    snapshot
                }.onSuccess { snapshot ->
                    _state.update { it.copy(
                        githubWork = snapshot,
                        githubWorkRunning = false,
                        githubWorkChecksPassed = false,
                        githubChatWorkflow = if (snapshot.isRepository) it.githubChatWorkflow.copy(
                            stage = GitHubChatStage.EDITING,
                            message = "Safe branch ready. The agent is editing the project now…",
                        ) else GitHubChatWorkflow(),
                    ) }
                    withContext(Dispatchers.Main) { startPrompt(prompt) }
                }.onFailure { error ->
                    _state.update { it.copy(
                        githubWorkRunning = false,
                        githubChatWorkflow = it.githubChatWorkflow.copy(
                            stage = GitHubChatStage.ERROR,
                            message = redactGitHubSensitiveOutput(error.message.orEmpty()).ifBlank { "Could not prepare GitHub Work Mode." },
                        ),
                    ) }
                }
            }
            return
        }
        startPrompt(prompt)
    }

    private fun startPrompt(prompt: String) {
        val project = state.value.activeProject ?: return
        val attachments = state.value.pendingAttachments
        if ((prompt.isBlank() && attachments.isEmpty()) || state.value.isRunning) return
        val requestText = prompt.trim().ifBlank { "Please review the attached files." }
        val u = state.value
        val provider = if (u.selectedAgent == AgentKind.ANTIGRAVITY) "Google Antigravity" else u.provider.kind.title
        val model = if (u.selectedAgent == AgentKind.ANTIGRAVITY) u.antigravityModel.ifBlank { "Google AI" } else u.provider.model.ifBlank { "Default model" }
        activeUsageId = usageStore.start(u.selectedAgent, provider, model)
        refreshUsageState()
        updateActiveChatTitle(requestText)
        _state.update {
            val startedAt = System.currentTimeMillis()
            it.copy(
                messages = it.messages + ChatMessage(fromUser = true, text = prompt.trim(), attachments = attachments),
                pendingAttachments = emptyList(),
                isRunning = true,
                activity = listOf(ActivityItem("Understanding your request", "Preparing a safe plan", false)) + it.activity,
                liveProcess = listOf(ActivityItem("Think", requestPlanningSummary(requestText), false)),
                liveThinking = true,
                activeThinkingBlockId = null,
                taskStartedAtMillis = startedAt,
                taskFinishedAtMillis = null,
                workSegmentStartedAtMillis = startedAt,
                currentTaskRequest = requestText,
                githubChatWorkflow = if (it.githubWork.isRepository) it.githubChatWorkflow.copy(
                    stage = GitHubChatStage.EDITING,
                    request = requestText,
                    commitMessage = it.githubChatWorkflow.commitMessage.ifBlank { defaultGitHubCommitMessage(requestText, project.name) },
                    pullRequestTitle = it.githubChatWorkflow.pullRequestTitle.ifBlank { defaultGitHubPullRequestTitle(requestText, project.name) },
                    message = "The agent is editing the safe work branch…",
                ) else it.githubChatWorkflow,
            )
        }
        touchProject(project.id)
        persistMessages()
        val history = state.value.messages // includes all messages up to now
        val guardedRequest = if (_state.value.githubWork.isRepository) buildString {
            appendLine("<github_work_mode>")
            appendLine("Work only inside the active project. Never switch to, commit on, or push main/master/the base branch.")
            appendLine("Do not commit, push, or open a pull request yourself; the user will review checks and publish through GitHub Work Mode.")
            appendLine("Implement the requested code changes and run only non-destructive local inspection or tests needed for the task.")
            appendLine("</github_work_mode>")
            appendLine(requestText)
        } else requestText
        val runtimePrompt = if (attachments.isEmpty()) guardedRequest else buildString {
            appendLine(guardedRequest)
            appendLine()
            appendLine("<attached_files>")
            attachments.forEach { attachment ->
                appendLine("- ${attachment.displayName}: ${projectGuestRoot(project)}/${attachment.relativePath} (${attachment.mimeType})")
            }
            appendLine("These files were explicitly attached by the user. Inspect them only as needed for the request.")
            appendLine("</attached_files>")
        }
        viewModelScope.launch {
            activeRuntime().startSession(project.id, project.slug, project.kind, runtimePrompt, history, state.value.provider)
        }
    }

    fun answerApproval(approved: Boolean) {
        val request = state.value.pendingApproval ?: return
        viewModelScope.launch { activeRuntime().respondToApproval(request, approved) }
    }

    fun stopTask() {
        if (!_state.value.isRunning) return
        viewModelScope.launch { activeRuntime().stopActiveSession() }
    }

    fun undoLastChanges() {
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            val restored = activeRuntime().undoLastChanges(project.id)
            _state.update { current ->
                current.copy(
                    changes = if (restored) emptyList() else current.changes,
                    activity = listOf(
                        ActivityItem(
                            if (restored) "Changes undone" else "Undo unavailable",
                            if (restored) "Restored files to their state before the task" else "No restorable checkpoint was found",
                        ),
                    ) + current.activity,
                )
            }
            if (restored) refreshProjectFiles()
        }
    }

    fun keepLastChanges() {
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            activeRuntime().acceptLastChanges(project.id)
            _state.update {
                it.copy(
                    changes = emptyList(),
                    activity = listOf(ActivityItem("Changes kept", "Accepted the task's file changes")) + it.activity,
                )
            }
        }
    }

    fun undoFileChange(path: String) {
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            if (activeRuntime().undoFileChange(project.id, path)) {
                _state.update { current -> current.copy(changes = current.changes.filterNot { it.path == path }) }
                refreshProjectFiles()
            }
        }
    }

    fun keepFileChange(path: String) {
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            if (activeRuntime().acceptFileChange(project.id, path)) {
                _state.update { it.copy(changes = it.changes.filterNot { change -> change.path == path }) }
            }
        }
    }

    private fun isNoisyRuntimeItem(item: ActivityItem): Boolean {
        val combined = "${item.title} ${item.detail}"
        return combined.contains("Starting Claude Code", true) ||
            combined.contains("Agent process started", true) ||
            combined.contains("Claude Code connected", true) ||
            combined.contains("Runtime warning", true) ||
            combined.contains("unrecognized_model", true) ||
            combined.contains("Writing response", true) ||
            combined.contains("Claude Code finished", true) ||
            combined.contains("Task completed", true)
    }

    private fun toolPlanSummary(toolName: String, detail: String): String {
        val clean = detail.replace(Regex("\\s+"), " ").trim()
        val short = clean.take(90).ifBlank { "the current project" }
        return when (toolName) {
            "Write" -> "Preparing to create ${clean.substringAfterLast('/').ifBlank { "a project file" }}"
            "Edit", "NotebookEdit" -> "Preparing to update ${clean.substringAfterLast('/').ifBlank { "a project file" }}"
            "Read" -> "Preparing to inspect ${clean.substringAfterLast('/').ifBlank { "a project file" }}"
            "Glob" -> "Preparing to find matching project files"
            "Grep" -> "Preparing to search the project for $short"
            "Bash" -> if (clean.contains("cat ", true) || clean.contains("printf ", true) || clean.contains(" >")) {
                "Preparing to create or update project files with Bash"
            } else {
                "Preparing to run: $short"
            }
            else -> "Preparing to use $toolName for the next step"
        }
    }

    private fun requestPlanningSummary(request: String, toolName: String? = null, detail: String = ""): String {
        val cleanRequest = request.replace(Regex("\\s+"), " ").trim().take(110)
        val requestPart = if (cleanRequest.isBlank()) {
            "Claude is reviewing the request"
        } else {
            "The user is asking: “$cleanRequest”"
        }
        return if (toolName == null) {
            "$requestPart. Claude is deciding the next useful step."
        } else {
            "$requestPart. ${toolPlanSummary(toolName, detail)}."
        }
    }

    private fun finishWorkSegment(current: AppUiState, finishedAt: Long = System.currentTimeMillis()): AppUiState {
        val meaningfulItems = current.liveProcess.filterNot(::isNoisyRuntimeItem)
            .map { if (it.isComplete) it else it.copy(isComplete = true) }
        if (!current.liveThinking && meaningfulItems.isEmpty()) {
            return current.copy(liveProcess = emptyList(), workSegmentStartedAtMillis = null)
        }
        val startedAt = current.workSegmentStartedAtMillis ?: current.taskStartedAtMillis ?: finishedAt
        val block = ChatMessage(
            fromUser = false,
            text = "",
            workItems = meaningfulItems,
            workedMillis = (finishedAt - startedAt).coerceAtLeast(0L),
        )
        return current.copy(
            messages = current.messages + block,
            liveProcess = emptyList(),
            liveThinking = false,
            activeThinkingBlockId = null,
            workSegmentStartedAtMillis = null,
        )
    }

    private fun appendWorkItem(current: AppUiState, item: ActivityItem): AppUiState {
        if (isNoisyRuntimeItem(item)) return current
        return current.copy(
            liveProcess = current.liveProcess.map { if (it.isComplete) it else it.copy(isComplete = true) } + item,
            liveThinking = false,
            workSegmentStartedAtMillis = current.workSegmentStartedAtMillis ?: System.currentTimeMillis(),
        )
    }

    private fun onRuntimeEvent(event: RuntimeEvent) {
        _state.update { current ->
            if (!current.isRunning) {
                current
            } else if (current.activeSessionId != null && current.activeSessionId != event.sessionId) {
                current
            } else when (event) {
                is RuntimeEvent.SessionStarted -> current.copy(
                    activeSessionId = event.sessionId,
                    activity = current.activity.mapIndexed { index, item -> if (index == 0) item.copy(isComplete = true) else item },
                )
                is RuntimeEvent.AssistantDelta -> {
                    val timeline = if (current.liveThinking || current.liveProcess.any { !isNoisyRuntimeItem(it) }) {
                        finishWorkSegment(current)
                    } else {
                        current
                    }
                    val lastMessage = timeline.messages.lastOrNull()
                    if (lastMessage != null && !lastMessage.fromUser && lastMessage.workItems.isEmpty() && lastMessage.workedMillis == 0L) {
                        timeline.copy(messages = timeline.messages.dropLast(1) + lastMessage.copy(text = lastMessage.text + event.text))
                    } else {
                        timeline.copy(messages = timeline.messages + ChatMessage(fromUser = false, text = event.text))
                    }
                }
                is RuntimeEvent.RetractAssistantText -> {
                    // Failover helper: drop the partial text a rate-limited attempt streamed,
                    // so the retry's answer continues cleanly instead of duplicating it.
                    val lastMessage = current.messages.lastOrNull()
                    if (lastMessage != null && !lastMessage.fromUser && event.chars > 0) {
                        val trimmed = lastMessage.text.dropLast(event.chars.coerceAtMost(lastMessage.text.length))
                        if (trimmed.isBlank()) {
                            current.copy(messages = current.messages.dropLast(1))
                        } else {
                            current.copy(messages = current.messages.dropLast(1) + lastMessage.copy(text = trimmed))
                        }
                    } else {
                        current
                    }
                }
                is RuntimeEvent.ReasoningProgress -> {
                    val existingIndex = current.liveProcess.indexOfLast { it.title == "Think" }
                    // The request-level Think summary is seeded once in sendPrompt.
                    // After that segment has been committed to the timeline, later
                    // agent turns must not repeat the same request summary.
                    if (existingIndex < 0) return@update current
                    val reasoning = ActivityItem(
                        title = "Think",
                        detail = current.liveProcess.getOrNull(existingIndex)?.detail
                            ?: requestPlanningSummary(current.currentTaskRequest.orEmpty()),
                        isComplete = false,
                    )
                    val process = if (existingIndex >= 0) {
                        current.liveProcess.toMutableList().also { it[existingIndex] = reasoning }
                    } else {
                        current.liveProcess + reasoning
                    }
                    current.copy(
                        liveProcess = process,
                        liveThinking = true,
                        workSegmentStartedAtMillis = current.workSegmentStartedAtMillis ?: System.currentTimeMillis(),
                    )
                }
                is RuntimeEvent.ReasoningSummary -> {
                    val summary = event.summary.trim()
                    val process = current.liveProcess.toMutableList()
                    val existingIndex = process.indexOfLast { !it.isComplete && it.title == "Think" }
                    if (event.startsNewBlock) {
                        process.indices.forEach { index ->
                            if (!process[index].isComplete) process[index] = process[index].copy(isComplete = true)
                        }
                        val initial = summary.ifBlank { "Thinking…" }
                        val replaceFallback = current.activeThinkingBlockId == null &&
                            process.size == 1 && process.first().title == "Think"
                        if (replaceFallback) {
                            process[0] = ActivityItem("Think", initial, event.isFinal)
                        } else {
                            process += ActivityItem("Think", initial, event.isFinal)
                        }
                    } else if (current.activeThinkingBlockId == event.blockId && existingIndex >= 0 && summary.isNotBlank()) {
                        process[existingIndex] = process[existingIndex].copy(
                            detail = summary,
                            isComplete = event.isFinal,
                        )
                    } else {
                        return@update current
                    }
                    current.copy(
                        liveProcess = process,
                        liveThinking = !event.isFinal,
                        activeThinkingBlockId = if (event.isFinal) null else event.blockId,
                        workSegmentStartedAtMillis = current.workSegmentStartedAtMillis ?: System.currentTimeMillis(),
                    )
                }
                is RuntimeEvent.ToolStarted -> {
                    val planned = current.copy(
                        liveProcess = current.liveProcess.map { item ->
                            if (!item.isComplete) item.copy(isComplete = true) else item
                        },
                        liveThinking = false,
                        activeThinkingBlockId = null,
                        activity = listOf(
                            ActivityItem("Running ${event.toolName}", event.detail, false, isCommand = event.toolName == "Bash"),
                        ) + current.activity.map { if (!it.isComplete) it.copy(isComplete = true) else it },
                    )
                    appendWorkItem(
                        planned,
                        ActivityItem("Running ${event.toolName}", event.detail, false, isCommand = event.toolName == "Bash"),
                    )
                }
                is RuntimeEvent.RuntimeLog -> appendWorkItem(
                    current.copy(activity = listOf(ActivityItem(event.title, event.detail)) + current.activity),
                    ActivityItem(event.title, event.detail),
                )
                is RuntimeEvent.ToolRequested -> appendWorkItem(current.copy(
                    pendingApproval = event.request,
                    activity = listOf(ActivityItem("Waiting for approval", event.request.explanation, false)) + current.activity,
                ), ActivityItem("Waiting for approval", event.request.explanation, false))
                is RuntimeEvent.ToolApproved -> appendWorkItem(current.copy(
                    pendingApproval = null,
                    activity = listOf(ActivityItem("Applying approved changes", "Editing project files", false)) + current.activity,
                ), ActivityItem("Action approved", "Claude is continuing the task", false))
                is RuntimeEvent.ToolRejected -> appendWorkItem(current.copy(
                    pendingApproval = null,
                ), ActivityItem("Action rejected", "Claude will continue without this action"))
                is RuntimeEvent.ToolCompleted -> {
                    val runningIndex = current.liveProcess.indexOfLast {
                        !it.isComplete && it.title == "Running ${event.toolName}"
                    }
                    val process = if (runningIndex >= 0) {
                        current.liveProcess.toMutableList().also { items ->
                            val runningItem = items[runningIndex]
                            items[runningIndex] = ActivityItem(
                                "${event.toolName} completed",
                                runningItem.detail.ifBlank { event.summary },
                                isCommand = event.toolName == "Bash",
                            )
                        }
                    } else {
                        current.liveProcess + ActivityItem(
                            "${event.toolName} completed",
                            event.summary,
                            isCommand = event.toolName == "Bash",
                        )
                    }
                    current.copy(
                        activity = listOf(ActivityItem(event.summary, event.toolName)) + current.activity,
                        liveProcess = process,
                        liveThinking = false,
                        workSegmentStartedAtMillis = current.workSegmentStartedAtMillis ?: System.currentTimeMillis(),
                    )
                }
                is RuntimeEvent.FilesChanged -> current.copy(
                    changes = event.changes,
                    liveThinking = false,
                    liveProcess = if (event.paths.isEmpty()) current.liveProcess else current.liveProcess +
                        ActivityItem(
                            "Files changed",
                            event.paths.take(4).joinToString(", ") + if (event.paths.size > 4) " +${event.paths.size - 4} more" else "",
                        ),
                    workSegmentStartedAtMillis = current.workSegmentStartedAtMillis ?: System.currentTimeMillis(),
                )
                is RuntimeEvent.PreviewStarted -> current.copy(
                    previewReady = true,
                    previewUrl = event.url,
                    activity = listOf(ActivityItem("Preview ready", event.url)) + current.activity,
                    liveProcess = current.liveProcess + ActivityItem("Preview ready", event.url),
                    liveThinking = false,
                    workSegmentStartedAtMillis = current.workSegmentStartedAtMillis ?: System.currentTimeMillis(),
                )
                is RuntimeEvent.SessionCompleted -> finishWorkSegment(current).copy(
                    isRunning = false,
                    activeSessionId = null,
                    activity = listOf(ActivityItem("Task completed", "Claude Code finished successfully")) +
                        current.activity.map { if (!it.isComplete) it.copy(isComplete = true) else it },
                    taskFinishedAtMillis = System.currentTimeMillis(),
                    currentTaskRequest = null,
                    githubChatWorkflow = if (current.githubWork.isRepository) current.githubChatWorkflow.copy(
                        stage = GitHubChatStage.REVIEW,
                        message = "Edits finished. Review the changed files, then run checks from this chat.",
                    ) else current.githubChatWorkflow,
                )
                is RuntimeEvent.SessionFailed -> finishWorkSegment(
                    appendWorkItem(current, ActivityItem("Task stopped", event.reason)),
                ).copy(
                    isRunning = false,
                    activeSessionId = null,
                    pendingApproval = null,
                    toastMessage = event.reason.takeIf { reason ->
                        reason.contains("user not found", true) ||
                            reason.contains("API key", true) ||
                            reason.contains("authentication", true)
                    },
                    activity = listOf(ActivityItem("Task stopped", event.reason)) + current.activity,
                    taskFinishedAtMillis = System.currentTimeMillis(),
                    currentTaskRequest = null,
                    githubChatWorkflow = if (current.githubWork.isRepository) current.githubChatWorkflow.copy(
                        stage = GitHubChatStage.ERROR,
                        message = "The coding task stopped before PR preparation completed: ${event.reason}",
                    ) else current.githubChatWorkflow,
                )
            }
        }
        when (event) {
            is RuntimeEvent.SessionCompleted -> { usageStore.finish(activeUsageId, AiUsageStatus.SUCCESS); activeUsageId = null; refreshUsageState() }
            is RuntimeEvent.SessionFailed -> {
                val limited = event.reason.contains("429") || event.reason.contains("rate limit", true) || event.reason.contains("quota", true) || event.reason.contains("credit", true)
                usageStore.finish(activeUsageId, if (limited) AiUsageStatus.RATE_LIMITED else AiUsageStatus.FAILED)
                activeUsageId = null
                refreshUsageState()
            }
            else -> Unit
        }
        if (event is RuntimeEvent.FilesChanged || event is RuntimeEvent.SessionCompleted) {
            _state.value.activeProject?.id?.let { touchProject(it) }
            refreshProjectFiles()
            if (event is RuntimeEvent.SessionCompleted && _state.value.githubWork.isRepository) refreshGitHubWork()
        }
        if (event is RuntimeEvent.AssistantDelta || event is RuntimeEvent.SessionCompleted || event is RuntimeEvent.SessionFailed) {
            persistMessages()
        }
    }

    private fun touchProject(projectId: String) {
        val now = System.currentTimeMillis()
        _state.update { current ->
            val updatedProjects = current.projects.map { p ->
                if (p.id == projectId) p.copy(updatedAtMillis = now) else p
            }
            val active = if (current.activeProject?.id == projectId) current.activeProject?.copy(updatedAtMillis = now) else current.activeProject
            current.copy(projects = updatedProjects, activeProject = active)
        }
        preferences.saveProjects(_state.value.projects)
    }

    private fun persistMessages() {
        val project = _state.value.activeProject ?: return
        val chatId = _state.value.activeChatId ?: return
        val msgs = _state.value.messages
        viewModelScope.launch(Dispatchers.IO) {
            preferences.saveMessages(project.id, chatId, msgs)
        }
    }

    private fun updateActiveChatTitle(prompt: String) {
        val project = _state.value.activeProject ?: return
        val chatId = _state.value.activeChatId ?: return
        val now = System.currentTimeMillis()
        val title = prompt.replace(Regex("\\s+"), " ").trim().let {
            if (it.length <= 42) it else it.take(39).trimEnd() + "…"
        }
        _state.update { current ->
            val chats = current.projectChats.map { chat ->
                if (chat.id == chatId) {
                    chat.copy(
                        title = if (chat.title == "New chat") title else chat.title,
                        updatedAtMillis = now,
                    )
                } else chat
            }.sortedByDescending { it.updatedAtMillis }
            current.copy(projectChats = chats)
        }
        preferences.saveProjectChats(project.id, _state.value.projectChats)
    }

    companion object {
        private const val MINIMUM_INITIALIZATION_SCREEN_MS = 3_000L
        private const val APP_UPDATE_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L
        private const val MAX_VISIBLE_WORKSPACE_ENTRIES = 2_000
        private const val MAX_PROJECT_TERMINAL_HISTORY = 100
        private const val MAX_PROJECT_TERMINAL_OUTPUT = 200_000
        private const val MAX_ATTACHMENTS_PER_MESSAGE = 5
        private const val MAX_ATTACHMENT_BYTES = 25L * 1024L * 1024L
        private const val TEST_PROVIDER_DEFAULTS_VERSION = 1
        private const val TEST_OPENROUTER_BASE_URL = "https://openrouter.ai/api"
        private const val TEST_OPENROUTER_MODEL = "stealth/ox-alpha"
        private const val GITHUB_CONFIG_DIR = "/root/.config/gh"
        private const val GITHUB_DEVICE_URL = "https://github.com/login/device"
        private const val GITHUB_LOGIN_COMMAND = "gh auth login --hostname github.com --git-protocol https --web --skip-ssh-key"
        private const val GITHUB_LOGIN_TIMEOUT_MS = 5L * 60L * 1000L
        private const val GITHUB_WORK_READ_TIMEOUT_MS = 30_000L
        private const val GITHUB_WORK_TIMEOUT_MS = 2L * 60L * 1000L
        private const val GITHUB_WORK_CHECK_TIMEOUT_MS = 20L * 60L * 1000L
        private const val GITHUB_WORK_PUBLISH_TIMEOUT_MS = 10L * 60L * 1000L
    }
}
