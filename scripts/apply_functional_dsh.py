from pathlib import Path
import re


def replace_once(path, old, new):
    p = Path(path)
    text = p.read_text()
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}: {old[:100]!r}")
    p.write_text(text.replace(old, new, 1))

installer = "app/src/main/java/com/jarves/mh/runtime/RuntimeInstaller.kt"
replace_once(installer,
'''    private val devStacksFile = File(rootfs, ".pocket-dev-stacks.json")
    private val macosMetadataRepairMarker''',
'''    private val devStacksFile = File(rootfs, ".pocket-dev-stacks.json")
    private val dshMarker = File(rootfs, ".pocket-dsh-version")
    private val dshAndroidCompatibilityMarker = File(rootfs, ".pocket-dsh-android-compat-version")
    private val macosMetadataRepairMarker''')
replace_once(installer,
'''    /**
     * Installs one optional development stack inside Ubuntu. Safe to call again:
''',
'''    fun isDeepSeekHarnessInstalled(): Boolean = isInstalled() &&
        File(rootfs, "usr/local/lib/dsh/node_modules/.bin/dsh").isFile &&
        dshMarker.readTextOrNull() == DSH_VERSION

    suspend fun ensureDeepSeekHarnessInstalled(onProgress: suspend (RuntimeInstallProgress) -> Unit) {
        if (isDeepSeekHarnessInstalled()) {
            ensureDshAndroidCompatibility()
            onProgress(RuntimeInstallProgress("DeepSeek Harness is ready", 1f))
            return
        }
        val runtime = installedRuntime()
        onProgress(RuntimeInstallProgress("Installing DeepSeek Harness $DSH_VERSION", 0.05f, indeterminate = true))
        runGuestCommand(
            proot = runtime.proot,
            command = "set -e; rm -rf /usr/local/lib/dsh.installing; mkdir -p /usr/local/lib/dsh.installing; " +
                "cd /usr/local/lib/dsh.installing; npm init -y >/dev/null; " +
                "npm install --omit=dev --no-audit --no-fund @deepseek-ai/dsh@$DSH_VERSION; " +
                "rm -rf /usr/local/lib/dsh; mv /usr/local/lib/dsh.installing /usr/local/lib/dsh; " +
                "ln -sfn ../lib/dsh/node_modules/.bin/dsh /usr/local/bin/dsh",
            displayCommand = "npm install @deepseek-ai/dsh@$DSH_VERSION",
            fraction = 0.45f,
            timeoutMs = 20 * 60 * 1_000L,
            onProgress = onProgress,
            failureMessage = "DeepSeek Harness installation failed",
        )
        dshMarker.writeText(DSH_VERSION)
        dshAndroidCompatibilityMarker.delete()
        ensureDshAndroidCompatibility()
        verifyGuest(runtime.proot, "/usr/local/bin/dsh --profile headless --help", "DeepSeek Harness verification failed")
        check(isDeepSeekHarnessInstalled()) { "DeepSeek Harness installation is incomplete" }
        onProgress(RuntimeInstallProgress("DeepSeek Harness is ready", 1f, event = RuntimeInstallEvent.COMPLETED))
    }

    fun ensureDshAndroidCompatibility() {
        if (!isDeepSeekHarnessInstalled()) return
        val persistence = File(rootfs, "usr/local/lib/dsh/node_modules/@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js")
        val localFs = File(rootfs, "usr/local/lib/dsh/node_modules/@deepseek-ai/dsh-fs-local/lib/index.js")
        patchDshHardLinkPublication(
            persistence,
            "import { link, mkdir, mkdtemp, open, readFile, readdir, realpath, rm, stat, truncate } from \\\"node:fs/promises\\\";",
            "import { copyFile, link, mkdir, mkdtemp, open, readFile, readdir, realpath, rm, stat, truncate } from \\\"node:fs/promises\\\";",
            "await link(tmp, finalPath);",
            "await copyFile(tmp, finalPath, 1);",
        )
        patchDshHardLinkPublication(
            localFs,
            "import { chmod, link, lstat, mkdir, open, readFile, readdir, realpath, rename, rm, stat } from \\\"node:fs/promises\\\";",
            "import { chmod, copyFile, link, lstat, mkdir, open, readFile, readdir, realpath, rename, rm, stat } from \\\"node:fs/promises\\\";",
            "await linkFile(tempPath, absolutePath);",
            "await copyFile(tempPath, absolutePath, 1);",
        )
        dshAndroidCompatibilityMarker.writeText(DSH_ANDROID_COMPATIBILITY_VERSION)
    }

    private fun patchDshHardLinkPublication(file: File, importBefore: String, importAfter: String, callBefore: String, callAfter: String) {
        check(file.isFile) { "DeepSeek Harness compatibility file is missing: ${file.name}" }
        var source = file.readText()
        if (callAfter in source && importAfter in source) return
        check(callBefore in source && importBefore in source) { "DeepSeek Harness $DSH_VERSION is not compatible with this build" }
        source = source.replace(importBefore, importAfter).replace(callBefore, callAfter)
        file.writeText(source)
    }

    /**
     * Installs one optional development stack inside Ubuntu. Safe to call again:
''')
replace_once(installer,
'''        guestCommand: List<String>,
        guestWorkspacePath: String = "/workspace",
    ): Process {''',
'''        guestCommand: List<String>,
        guestWorkspacePath: String = "/workspace",
        emulateHardLinks: Boolean = true,
    ): Process {''')
replace_once(installer,
'''            add(proot.absolutePath)
            add("--link2symlink")
            add("-0")''',
'''            add(proot.absolutePath)
            if (emulateHardLinks) add("--link2symlink")
            add("-0")''')
replace_once(installer,
'''        private val CLAUDE_VERSION_PATTERN = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")
        private val CORE_BUNDLE''',
'''        private val CLAUDE_VERSION_PATTERN = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")
        const val DSH_VERSION = "0.1.2-rc.1"
        private const val DSH_ANDROID_COMPATIBILITY_VERSION = "copyfile-excl-v1"
        private val CORE_BUNDLE''')

vm = "app/src/main/java/com/jarves/mh/ui/MainViewModel.kt"
replace_once(vm, 'import com.jarves.mh.runtime.ClaudeRuntimeBridge\n', 'import com.jarves.mh.runtime.ClaudeRuntimeBridge\nimport com.jarves.mh.runtime.DshRuntimeBridge\nimport com.jarves.mh.runtime.RuntimeBridge\n')
replace_once(vm,
'''    val selectedAgent: AgentKind = AgentKind.CLAUDE_CODE,
    val provider: ProviderProfile''',
'''    val selectedAgent: AgentKind = AgentKind.CLAUDE_CODE,
    val installedAgents: Set<AgentKind> = setOf(AgentKind.CLAUDE_CODE),
    val agentInstalling: AgentKind? = null,
    val agentInstallMessage: String? = null,
    val agentInstallProgress: Float = 0f,
    val provider: ProviderProfile''')
replace_once(vm,
'''    private val runtime = ClaudeRuntimeBridge(
        application,
        poolFor = { profile -> exhaustedKeys.snapshot(profile.kind.name, ApiKeyPool.parse(vault.get(profile.kind.name))) },
        onKeyExhausted = { profile, key -> exhaustedKeys.markExhausted(profile.kind.name, key) },
    )
    private val installer = RuntimeInstaller(application)''',
'''    private val claudeRuntime = ClaudeRuntimeBridge(
        application,
        poolFor = { profile -> exhaustedKeys.snapshot(profile.kind.name, ApiKeyPool.parse(vault.get(profile.kind.name))) },
        onKeyExhausted = { profile, key -> exhaustedKeys.markExhausted(profile.kind.name, key) },
    )
    private val dshRuntime = DshRuntimeBridge(application) { profile ->
        exhaustedKeys.snapshot(profile.kind.name, ApiKeyPool.parse(vault.get(profile.kind.name))).available.firstOrNull()
    }
    private fun activeRuntime(): RuntimeBridge = when (_state.value.selectedAgent) {
        AgentKind.DEEPSEEK_HARNESS -> dshRuntime
        AgentKind.CLAUDE_CODE, AgentKind.ANTIGRAVITY -> claudeRuntime
    }
    private fun configureProjectRoot(projectId: String, rootPath: String) {
        claudeRuntime.configureProjectRoot(projectId, rootPath)
        dshRuntime.configureProjectRoot(projectId, rootPath)
    }
    private val installer = RuntimeInstaller(application)''')
replace_once(vm,
'''    fun selectAgent(agent: AgentKind) {
        if (_state.value.isRunning || _state.value.projectTerminalRunning) {
            _state.update { it.copy(toastMessage = "Stop running tasks and terminal commands before switching agents.") }
            return
        }
        preferences.selectedAgentKind = agent.name
        _state.update {
            it.copy(selectedAgent = agent, toastMessage = "${agent.title} selected in Settings.")
        }
    }
''',
'''    fun selectAgent(agent: AgentKind) {
        if (_state.value.isRunning || _state.value.projectTerminalRunning || _state.value.agentInstalling != null) {
            _state.update { it.copy(toastMessage = "Stop running work before switching coding agents.") }
            return
        }
        if (agent == AgentKind.ANTIGRAVITY) {
            _state.update { it.copy(toastMessage = "Antigravity still needs its Google sign-in runtime. It is not active yet.") }
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
            }
            if (result.isSuccess) {
                preferences.selectedAgentKind = agent.name
                _state.update { it.copy(selectedAgent = agent, installedAgents = it.installedAgents + agent, agentInstalling = null, agentInstallMessage = "DeepSeek Harness is ready", agentInstallProgress = 1f, toastMessage = "DeepSeek Harness installed and activated.") }
            } else {
                _state.update { it.copy(agentInstalling = null, agentInstallProgress = 0f, agentInstallMessage = result.exceptionOrNull()?.message?.take(240), toastMessage = result.exceptionOrNull()?.message ?: "DeepSeek Harness installation failed") }
            }
        }
    }
''')
replace_once(vm,
'''        viewModelScope.launch { RuntimeSetupController.snapshot.collect(::onSetupSnapshot) }
        viewModelScope.launch { runtime.events.collect(::onRuntimeEvent) }
        viewModelScope.launch { bootstrap() }''',
'''        viewModelScope.launch { RuntimeSetupController.snapshot.collect(::onSetupSnapshot) }
        viewModelScope.launch { claudeRuntime.events.collect(::onRuntimeEvent) }
        viewModelScope.launch { dshRuntime.events.collect(::onRuntimeEvent) }
        viewModelScope.launch { bootstrap() }''')
text = Path(vm).read_text()
text = re.sub(r'(?<![.\w])runtime\.', 'activeRuntime().', text)
text = text.replace('activeRuntime().configureProjectRoot(', 'configureProjectRoot(')
Path(vm).write_text(text)
replace_once(vm,
'''        _state.update { current ->
            current.copy(installedDevStacks = if (installed) installer.installedStacks() else current.installedDevStacks)
        }''',
'''        _state.update { current ->
            val agents = buildSet {
                add(AgentKind.CLAUDE_CODE)
                if (installed && installer.isDeepSeekHarnessInstalled()) add(AgentKind.DEEPSEEK_HARNESS)
            }
            val selected = if (current.selectedAgent in agents) current.selectedAgent else AgentKind.CLAUDE_CODE
            if (selected != current.selectedAgent) preferences.selectedAgentKind = selected.name
            current.copy(
                selectedAgent = selected,
                installedAgents = agents,
                installedDevStacks = if (installed) installer.installedStacks() else current.installedDevStacks,
            )
        }''')

settings = "app/src/main/java/com/jarves/mh/ui/SettingsScreenModern.kt"
replace_once(settings,
'''                        val statusText = when (agent) {
                            AgentKind.CLAUDE_CODE -> "Ready"
                            AgentKind.DEEPSEEK_HARNESS -> "Routing foundation installed"
                            AgentKind.ANTIGRAVITY -> "Protocol foundation installed"
                        }
                        Row(
                            Modifier.fillMaxWidth().clickable { onSelectAgent(agent) }.padding(vertical = 11.dp),''',
'''                        val installing = state.agentInstalling == agent
                        val installed = agent in state.installedAgents
                        val statusText = when {
                            installing -> state.agentInstallMessage ?: "Installing…"
                            installed && state.selectedAgent == agent -> "Active"
                            installed -> "Installed · tap to activate"
                            agent == AgentKind.DEEPSEEK_HARNESS -> "Tap to download and install"
                            agent == AgentKind.ANTIGRAVITY -> "Unavailable · Google sign-in runtime pending"
                            else -> "Ready"
                        }
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = state.agentInstalling == null) { onSelectAgent(agent) }.padding(vertical = 11.dp),''')
replace_once(settings,
'''                                Text(statusText, fontSize = 10.sp, color = if (agent == AgentKind.CLAUDE_CODE) Color(0xFF58C9A3) else PocketOrange)
                            }
                            SelectionDot(state.selectedAgent == agent)
                        }
                        if (index != AgentKind.entries.lastIndex)''',
'''                                Text(statusText, fontSize = 10.sp, color = if (installed) Color(0xFF58C9A3) else PocketOrange)
                                if (installing) LinearProgressIndicator(progress = { state.agentInstallProgress }, modifier = Modifier.fillMaxWidth().padding(top = 5.dp))
                            }
                            SelectionDot(state.selectedAgent == agent)
                        }
                        if (index != AgentKind.entries.lastIndex)''')
replace_once(settings,
'''                    RuntimeInfoRow("Agent", "Claude Code + Node.js 24")''',
'''                    RuntimeInfoRow("Agent", "${state.selectedAgent.title} + Node.js 24")''')
