from pathlib import Path


def replace_once(path, old, new):
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}: {old[:100]!r}")
    p.write_text(text.replace(old, new, 1))

runtime = "app/src/main/java/com/jarves/mh/runtime/RuntimeInstaller.kt"
replace_once(runtime,
'''    private val dshMarker = File(rootfs, ".pocket-dsh-version")
    private val dshAndroidCompatibilityMarker''',
'''    private val dshMarker = File(rootfs, ".pocket-dsh-version")
    private val agyMarker = File(rootfs, ".pocket-agy-version")
    private val dshAndroidCompatibilityMarker''')
replace_once(runtime,
'''    fun ensureDshAndroidCompatibility() {''',
'''    fun isAntigravityInstalled(): Boolean = isInstalled() &&
        File(rootfs, AGY_GUEST_PATH.removePrefix("/")).canExecute() &&
        agyMarker.readTextOrNull() == AGY_VERSION

    suspend fun ensureAntigravityInstalled(onProgress: suspend (RuntimeInstallProgress) -> Unit) {
        if (isAntigravityInstalled()) {
            onProgress(RuntimeInstallProgress("Antigravity CLI is ready", 1f))
            return
        }
        val runtime = installedRuntime()
        downloads.mkdirs()
        val archive = File(downloads, "antigravity-$AGY_VERSION-linux-arm64.tar.gz")
        onProgress(RuntimeInstallProgress("Downloading Antigravity CLI $AGY_VERSION", 0.05f))
        downloadVerified(AGY_RELEASE_URL, archive, AGY_RELEASE_SHA512, algorithm = "SHA-512") { bytes, total ->
            val ratio = if (total > 0L) bytes.toFloat() / total else 0f
            onProgress(RuntimeInstallProgress("Downloading Antigravity CLI $AGY_VERSION", 0.05f + ratio * 0.75f, bytes, total.takeIf { it > 0L }, event = RuntimeInstallEvent.DOWNLOAD))
        }
        onProgress(RuntimeInstallProgress("Installing Antigravity CLI $AGY_VERSION", 0.85f, indeterminate = true))
        val destination = File(rootfs, AGY_GUEST_PATH.removePrefix("/"))
        destination.parentFile?.mkdirs()
        var found = false
        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(archive.inputStream()))).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                if (entry.isFile && entry.name.removePrefix("./") == "antigravity") {
                    val staged = File(destination.parentFile, ".agy-$AGY_VERSION.installing")
                    FileOutputStream(staged).use { tar.copyTo(it) }
                    Os.chmod(staged.absolutePath, 0b111101101)
                    Os.rename(staged.absolutePath, destination.absolutePath)
                    found = true
                    break
                }
                entry = tar.nextEntry
            }
        }
        archive.delete()
        check(found) { "Official Antigravity archive did not contain the expected binary" }
        verifyGuest(runtime.proot, "$AGY_GUEST_PATH --version", "Antigravity CLI verification failed")
        agyMarker.writeText(AGY_VERSION)
        check(isAntigravityInstalled()) { "Antigravity CLI installation is incomplete" }
        onProgress(RuntimeInstallProgress("Antigravity CLI is ready", 1f, event = RuntimeInstallEvent.COMPLETED))
    }

    fun ensureDshAndroidCompatibility() {''')
replace_once(runtime,
'''    companion object {
        private const val LEGACY_README''',
'''    companion object {
        const val AGY_GUEST_PATH = "/root/.local/bin/agy"
        private const val AGY_VERSION = "1.1.27"
        private const val AGY_RELEASE_URL = "https://storage.googleapis.com/antigravity-public/antigravity-cli/1.1.27-5211191891591168/linux-arm/cli_linux_arm64.tar.gz"
        private const val AGY_RELEASE_SHA512 = "ed45f6930785aa4b42f14e07ace1c9d91a94fb76e760f54acbd7d3d3951e1f957fd456a0dae2a3124dd9a3b689bf7afb7c9303a3e4ba95037fc10063424d9bf9"
        private const val LEGACY_README''')

# Adapt imported upstream files to this app's installer surface.
for file in [
    "app/src/main/java/com/jarves/mh/runtime/AntigravityAuthController.kt",
    "app/src/main/java/com/jarves/mh/runtime/AntigravityRuntimeBridge.kt",
]:
    p = Path(file)
    p.write_text(p.read_text().replace(
        "installer.isAgentInstalled(com.jarves.mh.model.AgentKind.ANTIGRAVITY)",
        "installer.isAntigravityInstalled()",
    ))

main = "app/src/main/java/com/jarves/mh/ui/MainViewModel.kt"
replace_once(main,
'''import com.jarves.mh.runtime.ClaudeRuntimeBridge
import com.jarves.mh.runtime.DshRuntimeBridge''',
'''import com.jarves.mh.runtime.ClaudeRuntimeBridge
import com.jarves.mh.runtime.DshRuntimeBridge
import com.jarves.mh.runtime.AntigravityRuntimeBridge
import com.jarves.mh.runtime.AntigravityAuthController
import com.jarves.mh.runtime.AntigravityAuthState
import com.jarves.mh.runtime.AntigravityAuthStatus''')
replace_once(main,
'''import java.util.UUID
import java.util.zip.ZipEntry''',
'''import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry''')
replace_once(main,
'''    val agentInstallProgress: Float = 0f,
    val provider:''',
'''    val agentInstallProgress: Float = 0f,
    val antigravityAuth: AntigravityAuthState = AntigravityAuthState(),
    val provider:''')
replace_once(main,
'''    private val dshRuntime = DshRuntimeBridge(application) { profile ->
        exhaustedKeys.snapshot(profile.kind.name, ApiKeyPool.parse(vault.get(profile.kind.name))).available.firstOrNull()
    }
    private fun activeRuntime(): RuntimeBridge = when (_state.value.selectedAgent) {
        AgentKind.DEEPSEEK_HARNESS -> dshRuntime
        AgentKind.CLAUDE_CODE, AgentKind.ANTIGRAVITY -> claudeRuntime
    }
    private fun configureProjectRoot(projectId: String, rootPath: String) {
        claudeRuntime.configureProjectRoot(projectId, rootPath)
        dshRuntime.configureProjectRoot(projectId, rootPath)
    }''',
'''    private val dshRuntime = DshRuntimeBridge(application) { profile ->
        exhaustedKeys.snapshot(profile.kind.name, ApiKeyPool.parse(vault.get(profile.kind.name))).available.firstOrNull()
    }
    private val antigravityConversations = ConcurrentHashMap<String, String>()
    private val antigravityRuntime = AntigravityRuntimeBridge(
        application,
        model = { "" },
        effort = { "medium" },
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
    }''')
replace_once(main,
'''        if (agent == AgentKind.ANTIGRAVITY) {
            _state.update { it.copy(toastMessage = "Antigravity requires Google sign-in and is not available yet.") }
            return
        }
        if (agent == AgentKind.CLAUDE_CODE || installer.isDeepSeekHarnessInstalled()) {
            preferences.selectedAgentKind = agent.name
            _state.update { it.copy(selectedAgent = agent, installedAgents = it.installedAgents + agent, toastMessage = "${agent.title} is active.") }
            return
        }
        _state.update { it.copy(agentInstalling = agent, agentInstallMessage = "Preparing ${agent.title}…", agentInstallProgress = 0f) }
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    installer.ensureDeepSeekHarnessInstalled { progress ->
                        _state.update { current -> current.copy(agentInstallMessage = progress.message, agentInstallProgress = progress.fraction.coerceIn(0f, 1f)) }
                    }
                }
            }''',
'''        val alreadyInstalled = when (agent) {
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
            }''')
replace_once(main,
'''                _state.update { it.copy(selectedAgent = agent, installedAgents = it.installedAgents + agent, agentInstalling = null, agentInstallMessage = "DeepSeek Harness is ready", agentInstallProgress = 1f, toastMessage = "DeepSeek Harness installed and activated.") }''',
'''                _state.update { it.copy(selectedAgent = agent, installedAgents = it.installedAgents + agent, agentInstalling = null, agentInstallMessage = "${agent.title} is ready", agentInstallProgress = 1f, toastMessage = if (agent == AgentKind.ANTIGRAVITY) "Antigravity installed. Sign in with Google below." else "${agent.title} installed and activated.") }''')
replace_once(main,
'''toastMessage = result.exceptionOrNull()?.message ?: "DeepSeek Harness installation failed"''',
'''toastMessage = result.exceptionOrNull()?.message ?: "${agent.title} installation failed"''')
replace_once(main,
'''    fun getSavedApiKey(kind: ProviderKind): String = vault.get(kind.name).orEmpty()

    init {
        viewModelScope.launch { RuntimeSetupController.snapshot.collect(::onSetupSnapshot) }
        viewModelScope.launch { claudeRuntime.events.collect(::onRuntimeEvent) }
        viewModelScope.launch { dshRuntime.events.collect(::onRuntimeEvent) }
        viewModelScope.launch { bootstrap() }
    }''',
'''    fun beginAntigravityLogin() {
        viewModelScope.launch { antigravityAuthController.beginLogin() }
    }

    fun submitAntigravityCode(code: String) {
        runCatching { antigravityAuthController.submitCode(code) }
            .onFailure { error -> _state.update { it.copy(toastMessage = error.message ?: "Could not submit Google code") } }
    }

    fun logoutAntigravity() {
        viewModelScope.launch { runCatching { antigravityAuthController.logout() } }
    }

    fun getSavedApiKey(kind: ProviderKind): String = vault.get(kind.name).orEmpty()

    init {
        viewModelScope.launch { RuntimeSetupController.snapshot.collect(::onSetupSnapshot) }
        viewModelScope.launch { claudeRuntime.events.collect(::onRuntimeEvent) }
        viewModelScope.launch { dshRuntime.events.collect(::onRuntimeEvent) }
        viewModelScope.launch { antigravityRuntime.events.collect(::onRuntimeEvent) }
        viewModelScope.launch { antigravityAuthController.state.collect { auth -> _state.update { it.copy(antigravityAuth = auth) } } }
        viewModelScope.launch { bootstrap() }
    }''')
replace_once(main,
'''                if (installed && installer.isDeepSeekHarnessInstalled()) add(AgentKind.DEEPSEEK_HARNESS)
            }''',
'''                if (installed && installer.isDeepSeekHarnessInstalled()) add(AgentKind.DEEPSEEK_HARNESS)
                if (installed && installer.isAntigravityInstalled()) add(AgentKind.ANTIGRAVITY)
            }''')

settings = "app/src/main/java/com/jarves/mh/ui/SettingsScreenModern.kt"
replace_once(settings,
'''import com.jarves.mh.network.ConnectionValidation''',
'''import com.jarves.mh.runtime.AntigravityAuthStatus
import com.jarves.mh.network.ConnectionValidation''')
replace_once(settings,
'''    onSelectAgent: (AgentKind) -> Unit,
    onSetThemeMode:''',
'''    onSelectAgent: (AgentKind) -> Unit,
    onBeginAntigravityLogin: () -> Unit,
    onSubmitAntigravityCode: (String) -> Unit,
    onLogoutAntigravity: () -> Unit,
    onSetThemeMode:''')
replace_once(settings,
'''    var terminalCleared by remember { mutableStateOf(false) }''',
'''    var terminalCleared by remember { mutableStateOf(false) }
    var antigravityCode by rememberSaveable { mutableStateOf("") }''')
replace_once(settings,
'''                            agent == AgentKind.ANTIGRAVITY -> "Unavailable · Google sign-in runtime pending"''',
'''                            agent == AgentKind.ANTIGRAVITY -> "Tap to download and install"''')
replace_once(settings,
'''                            SelectionDot(state.selectedAgent == agent)
                        }
                        if (index != AgentKind.entries.lastIndex) HorizontalDivider''',
'''                            SelectionDot(state.selectedAgent == agent)
                        }
                        if (agent == AgentKind.ANTIGRAVITY && installed) {
                            when (state.antigravityAuth.status) {
                                AntigravityAuthStatus.SIGNED_IN -> {
                                    Text(state.antigravityAuth.message ?: "Google account connected", fontSize = 12.sp, color = Color(0xFF58C9A3))
                                    OutlinedButton(onClick = onLogoutAntigravity, modifier = Modifier.fillMaxWidth()) { Text("Sign out of Google") }
                                }
                                AntigravityAuthStatus.STARTING, AntigravityAuthStatus.COMPLETING -> {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    Text(state.antigravityAuth.message ?: "Starting Google sign-in…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                AntigravityAuthStatus.AWAITING_CODE -> {
                                    state.antigravityAuth.authorizationUrl?.let { url ->
                                        Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }, modifier = Modifier.fillMaxWidth()) { Text("Open Google sign-in") }
                                    }
                                    OutlinedTextField(value = antigravityCode, onValueChange = { antigravityCode = it }, label = { Text("One-time authorization code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    Button(onClick = { onSubmitAntigravityCode(antigravityCode); antigravityCode = "" }, enabled = antigravityCode.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Complete sign-in") }
                                }
                                AntigravityAuthStatus.SIGNED_OUT, AntigravityAuthStatus.ERROR -> {
                                    state.antigravityAuth.message?.let { Text(it, fontSize = 12.sp, color = if (state.antigravityAuth.status == AntigravityAuthStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                                    Button(onClick = onBeginAntigravityLogin, modifier = Modifier.fillMaxWidth()) { Text("Sign in with Google") }
                                }
                            }
                        }
                        if (index != AgentKind.entries.lastIndex) HorizontalDivider''')

app = "app/src/main/java/com/jarves/mh/ui/PocketDevApp.kt"
replace_once(app,
'''                    onSelectAgent = viewModel::selectAgent,
                    onSetThemeMode''',
'''                    onSelectAgent = viewModel::selectAgent,
                    onBeginAntigravityLogin = viewModel::beginAntigravityLogin,
                    onSubmitAntigravityCode = viewModel::submitAntigravityCode,
                    onLogoutAntigravity = viewModel::logoutAntigravity,
                    onSetThemeMode''')
