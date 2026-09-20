from pathlib import Path


def replace_once(path, old, new):
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1))

prefs = "app/src/main/java/com/jarves/mh/data/AppPreferences.kt"
replace_once(prefs,
'''    var selectedAgentKind: String
        get() = preferences.getString("selected_agent_kind", AgentKind.CLAUDE_CODE.name) ?: AgentKind.CLAUDE_CODE.name
        set(value) { preferences.edit().putString("selected_agent_kind", value).apply() }

    var legacySeededCredentialRemoved: Boolean''',
'''    var selectedAgentKind: String
        get() = preferences.getString("selected_agent_kind", AgentKind.CLAUDE_CODE.name) ?: AgentKind.CLAUDE_CODE.name
        set(value) { preferences.edit().putString("selected_agent_kind", value).apply() }

    var antigravityModel: String
        get() = preferences.getString("agent_antigravity_model", "") ?: ""
        set(value) { preferences.edit().putString("agent_antigravity_model", value).apply() }

    var antigravityEffort: String
        get() = preferences.getString("agent_antigravity_effort", "high") ?: "high"
        set(value) { preferences.edit().putString("agent_antigravity_effort", value).apply() }

    var legacySeededCredentialRemoved: Boolean''')

vm = "app/src/main/java/com/jarves/mh/ui/MainViewModel.kt"
replace_once(vm,
'''internal fun sanitizeTerminalOutput(text: String): String = text
    .replace(ANSI_TERMINAL_SEQUENCE, "")
    .filter { it == '\\n' || it == '\\r' || it == '\\t' || it.code >= 0x20 }

private data class ProjectTerminalSnapshot''',
'''internal fun sanitizeTerminalOutput(text: String): String = text
    .replace(ANSI_TERMINAL_SEQUENCE, "")
    .filter { it == '\\n' || it == '\\r' || it == '\\t' || it.code >= 0x20 }

private val ANTIGRAVITY_MODEL_EFFORT = Regex("^(.*)-(low|medium|high)$")

private fun antigravityEffortFromModel(model: String): String? =
    ANTIGRAVITY_MODEL_EFFORT.matchEntire(model)?.groupValues?.get(2)

private fun antigravityModelWithEffort(model: String, effort: String): String? {
    val match = ANTIGRAVITY_MODEL_EFFORT.matchEntire(model) ?: return null
    return "${match.groupValues[1]}-$effort"
}

private data class ProjectTerminalSnapshot''')
replace_once(vm,
'''    val antigravityAuth: AntigravityAuthState = AntigravityAuthState(),
    val provider: ProviderProfile''',
'''    val antigravityAuth: AntigravityAuthState = AntigravityAuthState(),
    val antigravityModel: String = "",
    val antigravityEffort: String = "high",
    val antigravityModels: List<String> = emptyList(),
    val antigravityModelsLoading: Boolean = false,
    val provider: ProviderProfile''')
replace_once(vm,
'''        model = { "" },
        effort = { "medium" },''',
'''        model = { _state.value.antigravityModel },
        effort = { _state.value.antigravityEffort },''')
replace_once(vm,
'''            selectedAgent = runCatching { AgentKind.valueOf(preferences.selectedAgentKind) }
                .getOrDefault(AgentKind.CLAUDE_CODE),
            provider = preferences.loadProvider(vault),''',
'''            selectedAgent = runCatching { AgentKind.valueOf(preferences.selectedAgentKind) }
                .getOrDefault(AgentKind.CLAUDE_CODE),
            antigravityModel = preferences.antigravityModel,
            antigravityEffort = preferences.antigravityEffort,
            provider = preferences.loadProvider(vault),''')
replace_once(vm,
'''        viewModelScope.launch { antigravityAuthController.state.collect { auth -> _state.update { it.copy(antigravityAuth = auth) } } }
        viewModelScope.launch { bootstrap() }''',
'''        viewModelScope.launch {
            antigravityAuthController.state.collect { auth ->
                _state.update { it.copy(antigravityAuth = auth) }
                if (auth.status == AntigravityAuthStatus.SIGNED_IN && _state.value.antigravityModels.isEmpty()) {
                    refreshAntigravityModels()
                }
            }
        }
        viewModelScope.launch { bootstrap() }''')
replace_once(vm,
'''    fun logoutAntigravity() {
        viewModelScope.launch { runCatching { antigravityAuthController.logout() } }
    }

    fun getSavedApiKey''',
'''    fun logoutAntigravity() {
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
                    .mapNotNull { line -> line.split(Regex("\\\\s+"), limit = 2).firstOrNull() }
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

    fun getSavedApiKey''')

settings = "app/src/main/java/com/jarves/mh/ui/SettingsScreenModern.kt"
replace_once(settings,
'''    onLogoutAntigravity: () -> Unit,
    onSetThemeMode: (AppThemeMode) -> Unit,''',
'''    onLogoutAntigravity: () -> Unit,
    onRefreshAntigravityModels: () -> Unit,
    onSetAntigravityModel: (String) -> Unit,
    onSetAntigravityEffort: (String) -> Unit,
    onSetThemeMode: (AppThemeMode) -> Unit,''')
replace_once(settings,
'''    var antigravityCode by rememberSaveable { mutableStateOf("") }
    var showReliabilityHelp''',
'''    var antigravityCode by rememberSaveable { mutableStateOf("") }
    var showAntigravityModels by rememberSaveable { mutableStateOf(false) }
    var showReliabilityHelp''')
replace_once(settings,
'''    if (showModels) {
        ModalBottomSheet(''',
'''    if (showAntigravityModels) {
        ModalBottomSheet(
            onDismissRequest = { showAntigravityModels = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.72f).padding(horizontal = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Google AI models", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Provided by your connected Antigravity account", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onRefreshAntigravityModels, enabled = !state.antigravityModelsLoading) {
                        if (state.antigravityModelsLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, "Refresh Google models")
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (state.antigravityModels.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No models loaded. Tap refresh.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(state.antigravityModels, key = { it }) { modelId ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    onSetAntigravityModel(modelId)
                                    showAntigravityModels = false
                                }.padding(vertical = 14.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(formatAntigravityModelName(modelId), fontWeight = FontWeight.SemiBold)
                                    Text(modelId, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                SelectionDot(state.antigravityModel == modelId)
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                        }
                    }
                }
            }
        }
    }

    if (showModels) {
        ModalBottomSheet(''')
replace_once(settings,
'''                                AntigravityAuthStatus.SIGNED_IN -> {
                                    Text(state.antigravityAuth.message ?: "Google account connected", fontSize = 12.sp, color = Color(0xFF58C9A3))
                                    OutlinedButton(onClick = onLogoutAntigravity, modifier = Modifier.fillMaxWidth()) { Text("Sign out of Google") }
                                }''',
'''                                AntigravityAuthStatus.SIGNED_IN -> {
                                    Text(state.antigravityAuth.message ?: "Google account connected", fontSize = 12.sp, color = Color(0xFF58C9A3))
                                    Text("Google AI model", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    OutlinedButton(
                                        onClick = {
                                            if (state.antigravityModels.isEmpty()) onRefreshAntigravityModels()
                                            showAntigravityModels = true
                                        },
                                        enabled = !state.antigravityModelsLoading,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        if (state.antigravityModelsLoading) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                                        else Icon(Icons.Default.SmartToy, null, Modifier.size(17.dp))
                                        Spacer(Modifier.width(7.dp))
                                        Text(
                                            if (state.antigravityModel.isBlank()) "Load Google AI models"
                                            else formatAntigravityModelName(state.antigravityModel),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        listOf("low", "medium", "high").forEach { effort ->
                                            val selected = state.antigravityEffort == effort
                                            if (selected) {
                                                Button(onClick = { onSetAntigravityEffort(effort) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp)) {
                                                    Text(effort.replaceFirstChar(Char::uppercase), fontSize = 11.sp)
                                                }
                                            } else {
                                                OutlinedButton(onClick = { onSetAntigravityEffort(effort) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp)) {
                                                    Text(effort.replaceFirstChar(Char::uppercase), fontSize = 11.sp)
                                                }
                                            }
                                        }
                                    }
                                    OutlinedButton(onClick = onRefreshAntigravityModels, enabled = !state.antigravityModelsLoading, modifier = Modifier.fillMaxWidth()) {
                                        Icon(Icons.Default.Refresh, null, Modifier.size(17.dp))
                                        Spacer(Modifier.width(7.dp))
                                        Text("Refresh Google AI models")
                                    }
                                    OutlinedButton(onClick = onLogoutAntigravity, modifier = Modifier.fillMaxWidth()) { Text("Sign out of Google") }
                                }''')
settings_path = Path(settings)
settings_path.write_text(settings_path.read_text() + '''

/** Formats Antigravity model identifiers into readable Google AI model names. */
internal fun formatAntigravityModelName(id: String): String = id
    .removePrefix("models/")
    .split('-')
    .joinToString(" ") { part -> part.replaceFirstChar(Char::uppercase) }
    .replace("Gemini", "Gemini")
''')

app = "app/src/main/java/com/jarves/mh/ui/PocketDevApp.kt"
replace_once(app,
'''                    onLogoutAntigravity = viewModel::logoutAntigravity,
                    onSetThemeMode = viewModel::setThemeMode,''',
'''                    onLogoutAntigravity = viewModel::logoutAntigravity,
                    onRefreshAntigravityModels = viewModel::refreshAntigravityModels,
                    onSetAntigravityModel = viewModel::setAntigravityModel,
                    onSetAntigravityEffort = viewModel::setAntigravityEffort,
                    onSetThemeMode = viewModel::setThemeMode,''')
