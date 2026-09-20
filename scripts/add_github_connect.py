from pathlib import Path

def edit(path, old, new, count=1):
 p=Path(path); s=p.read_text()
 if s.count(old)!=count: raise SystemExit(f'{path}: expected {count} matches, got {s.count(old)} for {old[:80]!r}')
 p.write_text(s.replace(old,new,count))

# Preferences
edit('app/src/main/java/com/jarves/mh/data/AppPreferences.kt', '''    var antigravityEffort: String
        get() = preferences.getString("agent_antigravity_effort", "high") ?: "high"
        set(value) { preferences.edit().putString("agent_antigravity_effort", value).apply() }
''', '''    var antigravityEffort: String
        get() = preferences.getString("agent_antigravity_effort", "high") ?: "high"
        set(value) { preferences.edit().putString("agent_antigravity_effort", value).apply() }

    var githubLogin: String
        get() = preferences.getString("github_login", "") ?: ""
        set(value) { preferences.edit().putString("github_login", value).apply() }
''')

# RuntimeInstaller
edit('app/src/main/java/com/jarves/mh/runtime/RuntimeInstaller.kt', '    private val agyMarker = File(rootfs, ".pocket-agy-version")\n', '    private val agyMarker = File(rootfs, ".pocket-agy-version")\n    private val githubCliMarker = File(rootfs, ".pocket-github-cli-version")\n')
anchor='''    fun ensureDshAndroidCompatibility() {
'''
methods='''    val githubCliVersion: String get() = githubCliMarker.readTextOrNull().orEmpty()

    fun isGitHubCliInstalled(): Boolean = isInstalled() &&
        File(rootfs, GITHUB_CLI_GUEST_PATH.removePrefix("/")).canExecute() &&
        githubCliMarker.readTextOrNull() == GITHUB_CLI_VERSION

    /** Installs GitHub's official ARM64 CLI on demand with a pinned checksum. */
    suspend fun ensureGitHubCliInstalled(onProgress: suspend (RuntimeInstallProgress) -> Unit) {
        if (isGitHubCliInstalled()) return
        check(!BuildConfig.OFFLINE_RUNTIME_BUNDLES) { "GitHub sign-in needs the online APK." }
        val runtime = installedRuntime()
        downloads.mkdirs()
        val archive = File(downloads, "gh-$GITHUB_CLI_VERSION-linux-arm64.tar.gz")
        onProgress(RuntimeInstallProgress("Downloading official GitHub CLI", 0.05f))
        downloadVerified(GITHUB_CLI_RELEASE_URL, archive, GITHUB_CLI_RELEASE_SHA256) { bytes, total ->
            val ratio = if (total > 0L) bytes.toFloat() / total else 0f
            onProgress(RuntimeInstallProgress("Downloading GitHub CLI $GITHUB_CLI_VERSION", 0.05f + ratio * 0.75f, bytes, total.takeIf { it > 0L }, event = RuntimeInstallEvent.DOWNLOAD))
        }
        onProgress(RuntimeInstallProgress("Installing GitHub CLI $GITHUB_CLI_VERSION", 0.85f, indeterminate = true))
        val destination = File(rootfs, GITHUB_CLI_GUEST_PATH.removePrefix("/"))
        destination.parentFile?.mkdirs()
        var found = false
        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(archive.inputStream()))).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                if (entry.isFile && entry.name.removePrefix("./").endsWith("/bin/gh")) {
                    val staged = File(destination.parentFile, ".gh-$GITHUB_CLI_VERSION.installing")
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
        check(found) { "Official GitHub CLI archive did not contain gh" }
        verifyGuest(runtime.proot, "$GITHUB_CLI_GUEST_PATH --version", "GitHub CLI verification failed")
        githubCliMarker.writeText(GITHUB_CLI_VERSION)
        check(isGitHubCliInstalled()) { "GitHub CLI installation is incomplete" }
        onProgress(RuntimeInstallProgress("GitHub CLI is ready", 1f, event = RuntimeInstallEvent.COMPLETED))
    }

'''
edit('app/src/main/java/com/jarves/mh/runtime/RuntimeInstaller.kt', anchor, methods+anchor)
edit('app/src/main/java/com/jarves/mh/runtime/RuntimeInstaller.kt', '        private const val AGY_RELEASE_SHA512 = "ed45f6930785aa4b42f14e07ace1c9d91a94fb76e760f54acbd7d3d3951e1f957fd456a0dae2a3124dd9a3b689bf7afb7c9303a3e4ba95037fc10063424d9bf9"\n', '        private const val AGY_RELEASE_SHA512 = "ed45f6930785aa4b42f14e07ace1c9d91a94fb76e760f54acbd7d3d3951e1f957fd456a0dae2a3124dd9a3b689bf7afb7c9303a3e4ba95037fc10063424d9bf9"\n        const val GITHUB_CLI_GUEST_PATH = "/usr/local/bin/gh"\n        private const val GITHUB_CLI_VERSION = "2.100.0"\n        private const val GITHUB_CLI_RELEASE_URL = "https://github.com/cli/cli/releases/download/v2.100.0/gh_2.100.0_linux_arm64.tar.gz"\n        private const val GITHUB_CLI_RELEASE_SHA256 = "ea4e7a581a32ccad6cc7923cb1576ac5859ba4b9a16ab22eb8f8a96e78e2e961"\n')

# Main state and implementation
edit('app/src/main/java/com/jarves/mh/ui/MainViewModel.kt', 'enum class AppUpdateStatus { AVAILABLE, PERMISSION_REQUIRED, DOWNLOADING, INSTALLING, ERROR }\n', 'enum class AppUpdateStatus { AVAILABLE, PERMISSION_REQUIRED, DOWNLOADING, INSTALLING, ERROR }\nenum class GitHubAuthStatus { DISCONNECTED, STARTING, AWAITING_USER, CONNECTED, ERROR }\n')
edit('app/src/main/java/com/jarves/mh/ui/MainViewModel.kt', '    val apiPingMessage: String? = null,\n', '''    val apiPingMessage: String? = null,
    val githubAuthStatus: GitHubAuthStatus = GitHubAuthStatus.DISCONNECTED,
    val githubLogin: String? = null,
    val githubUserCode: String? = null,
    val githubVerificationUri: String? = null,
    val githubMessage: String? = null,
''')
edit('app/src/main/java/com/jarves/mh/ui/MainViewModel.kt', '            provider = preferences.loadProvider(vault),\n', '            provider = preferences.loadProvider(vault),\n            githubLogin = preferences.githubLogin.takeIf(String::isNotBlank),\n')
edit('app/src/main/java/com/jarves/mh/ui/MainViewModel.kt', '    init {\n        refreshUsageState()\n', '    init {\n        refreshUsageState()\n        viewModelScope.launch { refreshGitHubConnection() }\n')
github_impl=r'''    private fun runGitHubCommand(command: String, input: String? = null): Pair<Int, String> {
        val runtime = installer.installedRuntime()
        val workspace = File(getApplication<Application>().filesDir, "workspaces/github-auth").apply { mkdirs() }
        val process = installer.process(
            runtime.proot,
            runtime.rootfs,
            workspace,
            mapOf("GH_CONFIG_DIR" to "/root/.config/gh"),
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
        return exit to sanitizeTerminalOutput(output).trim()
    }

    private fun readProcessOutput(file: File): String = runCatching { file.readText() }.getOrDefault("")

    private suspend fun refreshGitHubConnection() = withContext(Dispatchers.IO) {
        if (!installer.isInstalled() || !installer.isGitHubCliInstalled()) return@withContext
        val (exit, login) = runGitHubCommand("gh api user --jq .login")
        if (exit == 0 && login.lineSequence().lastOrNull().orEmpty().isNotBlank()) {
            val account = login.lineSequence().last().trim()
            preferences.githubLogin = account
            _state.update { it.copy(githubAuthStatus = GitHubAuthStatus.CONNECTED, githubLogin = account, githubMessage = "Connected as @$account") }
        } else {
            preferences.githubLogin = ""
            _state.update { it.copy(githubAuthStatus = GitHubAuthStatus.DISCONNECTED, githubLogin = null) }
        }
    }

    fun connectGitHub() {
        if (_state.value.githubAuthStatus == GitHubAuthStatus.STARTING || _state.value.githubAuthStatus == GitHubAuthStatus.AWAITING_USER) return
        _state.update { it.copy(githubAuthStatus = GitHubAuthStatus.STARTING, githubMessage = "Preparing official GitHub CLI…", githubUserCode = null, githubVerificationUri = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                installer.ensureGitHubCliInstalled { progress -> _state.update { it.copy(githubMessage = progress.message) } }
                val runtime = installer.installedRuntime()
                val workspace = File(getApplication<Application>().filesDir, "workspaces/github-auth").apply { mkdirs() }
                val process = installer.process(
                    runtime.proot,
                    runtime.rootfs,
                    workspace,
                    mapOf("GH_CONFIG_DIR" to "/root/.config/gh", "BROWSER" to "/bin/true"),
                    listOf("/usr/bin/bash", "-lc", "gh auth login --hostname github.com --git-protocol https --web --skip-ssh-key"),
                    guestWorkspacePath = "/workspace/github-auth",
                    emulateHardLinks = false,
                )
                val native = process as? NativeSpawnProcess ?: error("GitHub login process is unavailable")
                var offset = 0L
                val output = StringBuilder()
                var continued = false
                while (process.isAlive || native.outputFile.length() > offset) {
                    val available = native.outputFile.length() - offset
                    if (available <= 0L) { delay(80); continue }
                    val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
                    val count = RandomAccessFile(native.outputFile, "r").use { f -> f.seek(offset); f.read(bytes) }
                    if (count <= 0) continue
                    offset += count
                    output.append(bytes.decodeToString(0, count))
                    val clean = sanitizeTerminalOutput(output.toString())
                    val code = Regex("\\b[A-Z0-9]{4}-[A-Z0-9]{4}\\b").find(clean)?.value
                    if (code != null) {
                        _state.update { it.copy(githubAuthStatus = GitHubAuthStatus.AWAITING_USER, githubUserCode = code, githubVerificationUri = "https://github.com/login/device", githubMessage = "Enter this one-time code on GitHub") }
                        if (!continued) {
                            process.outputStream.write("\n".toByteArray())
                            process.outputStream.flush()
                            continued = true
                        }
                    }
                }
                val exit = process.waitFor()
                check(exit == 0) { sanitizeTerminalOutput(output.toString()).takeLast(500).ifBlank { "GitHub sign-in failed" } }
                runGitHubCommand("gh auth setup-git")
                refreshGitHubConnection()
            }.onFailure { error ->
                _state.update { it.copy(githubAuthStatus = GitHubAuthStatus.ERROR, githubMessage = error.message?.take(240) ?: "GitHub sign-in failed", githubUserCode = null) }
            }
        }
    }

    fun disconnectGitHub() {
        if (_state.value.githubAuthStatus == GitHubAuthStatus.STARTING) return
        _state.update { it.copy(githubAuthStatus = GitHubAuthStatus.STARTING, githubMessage = "Signing out of GitHub…") }
        viewModelScope.launch(Dispatchers.IO) {
            val login = _state.value.githubLogin
            val command = if (login.isNullOrBlank()) "gh auth logout --hostname github.com" else "gh auth logout --hostname github.com --user ${shellQuote(login)}"
            val result = runCatching { runGitHubCommand(command, "y\n") }
            if (result.isSuccess && result.getOrThrow().first == 0) {
                preferences.githubLogin = ""
                _state.update { it.copy(githubAuthStatus = GitHubAuthStatus.DISCONNECTED, githubLogin = null, githubUserCode = null, githubVerificationUri = null, githubMessage = "Signed out of GitHub") }
            } else {
                _state.update { it.copy(githubAuthStatus = GitHubAuthStatus.ERROR, githubMessage = result.exceptionOrNull()?.message ?: result.getOrNull()?.second?.takeLast(240) ?: "GitHub sign-out failed") }
            }
        }
    }

'''
edit('app/src/main/java/com/jarves/mh/ui/MainViewModel.kt', '    fun getSavedApiKey(kind: ProviderKind): String = vault.get(kind.name).orEmpty()\n', github_impl+'    fun getSavedApiKey(kind: ProviderKind): String = vault.get(kind.name).orEmpty()\n')

# Settings UI
edit('app/src/main/java/com/jarves/mh/ui/SettingsScreenModern.kt', 'private enum class SettingsSection { AGENT, CONNECTION, APPEARANCE, TOOLS, RUNTIME, APP_UPDATE, UPDATE_CHANNEL }\n', 'private enum class SettingsSection { AGENT, GITHUB, CONNECTION, APPEARANCE, TOOLS, RUNTIME, APP_UPDATE, UPDATE_CHANNEL }\n')
edit('app/src/main/java/com/jarves/mh/ui/SettingsScreenModern.kt', '    onLogoutAntigravity: () -> Unit,\n', '    onLogoutAntigravity: () -> Unit,\n    onConnectGitHub: () -> Unit,\n    onDisconnectGitHub: () -> Unit,\n')
github_ui='''            item {
                SettingsAccordion(
                    title = "GitHub",
                    subtitle = state.githubLogin?.let { "Connected as @$it" } ?: "Connect repositories and private Git history",
                    icon = Icons.Default.Code,
                    expanded = expanded == SettingsSection.GITHUB,
                    onClick = { toggle(SettingsSection.GITHUB) },
                ) {
                    Text("Uses GitHub's official device sign-in and CLI. No token is stored in the app UI.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    when (state.githubAuthStatus) {
                        GitHubAuthStatus.CONNECTED -> {
                            Text(state.githubMessage ?: "Connected as @${state.githubLogin}", color = Color(0xFF58C9A3), fontWeight = FontWeight.SemiBold)
                            Text("Private HTTPS repository URLs can now be cloned from the project import option. Git push, pull and PR commands work through the terminal.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(onClick = onDisconnectGitHub, modifier = Modifier.fillMaxWidth()) { Text("Disconnect GitHub") }
                        }
                        GitHubAuthStatus.STARTING -> {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(state.githubMessage ?: "Preparing GitHub sign-in…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        GitHubAuthStatus.AWAITING_USER -> {
                            Text(state.githubMessage ?: "Enter this code on GitHub", fontSize = 12.sp)
                            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                                Text(state.githubUserCode.orEmpty(), Modifier.padding(16.dp), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            }
                            Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.githubVerificationUri ?: "https://github.com/login/device"))) }, modifier = Modifier.fillMaxWidth()) { Text("Open GitHub authorization") }
                        }
                        GitHubAuthStatus.DISCONNECTED, GitHubAuthStatus.ERROR -> {
                            state.githubMessage?.let { Text(it, fontSize = 12.sp, color = if (state.githubAuthStatus == GitHubAuthStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                            Button(onClick = onConnectGitHub, modifier = Modifier.fillMaxWidth()) { Text(if (state.githubAuthStatus == GitHubAuthStatus.ERROR) "Retry GitHub sign-in" else "Connect GitHub") }
                        }
                    }
                }
            }

'''
edit('app/src/main/java/com/jarves/mh/ui/SettingsScreenModern.kt', '            item {\n                SettingsAccordion(\n                    title = "AI connection",\n', github_ui+'            item {\n                SettingsAccordion(\n                    title = "AI connection",\n')

# Wire root UI
p=Path('app/src/main/java/com/jarves/mh/ui/PocketDevApp.kt'); s=p.read_text()
needle='onBeginAntigravityLogin = viewModel::beginAntigravityLogin,'
if needle not in s: raise SystemExit('PocketDevApp: Settings callback anchor missing')
s=s.replace(needle, needle+'\n                    onConnectGitHub = viewModel::connectGitHub,\n                    onDisconnectGitHub = viewModel::disconnectGitHub,',1)
p.write_text(s)
print('GitHub Connect feature added')
