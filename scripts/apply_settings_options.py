from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text()
    if new in text:
        print(f"already updated: {path}")
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"expected one match in {path}, found {count}: {old[:80]!r}")
    file.write_text(text.replace(old, new, 1))
    print(f"updated: {path}")

models = "app/src/main/java/com/jarves/mh/model/Models.kt"
replace_once(
    models,
    'enum class ProviderProtocol { CLAUDE_LOGIN, ANTHROPIC, ANTHROPIC_GATEWAY, OPENROUTER, OPENAI_RESPONSES, OPENAI_CHAT }\n\nenum class ProviderKind(',
    '''enum class ProviderProtocol { CLAUDE_LOGIN, ANTHROPIC, ANTHROPIC_GATEWAY, OPENROUTER, OPENAI_RESPONSES, OPENAI_CHAT }

enum class AgentKind(val title: String, val subtitle: String) {
    CLAUDE_CODE("Claude Code", "Anthropic coding agent"),
    DEEPSEEK_HARNESS("DeepSeek Harness", "Open-source coding harness"),
    ANTIGRAVITY("Antigravity", "Google account coding agent"),
}

enum class ProviderKind(''',
)
replace_once(
    models,
    '    KIMI("Kimi", "Anthropic-compatible endpoint", ProviderProtocol.ANTHROPIC_GATEWAY, "https://api.moonshot.ai/anthropic", "kimi-k2.6", true),\n    CUSTOM(',
    '    KIMI("Kimi", "Anthropic-compatible endpoint", ProviderProtocol.ANTHROPIC_GATEWAY, "https://api.moonshot.ai/anthropic", "kimi-k2.6", true),\n    NVIDIA_NIM("NVIDIA NIM", "OpenAI-compatible NVIDIA inference", ProviderProtocol.OPENAI_CHAT, "https://integrate.api.nvidia.com/v1", "qwen/qwen2.5-coder-32b-instruct"),\n    CUSTOM(',
)

dsh = "app/src/main/java/com/jarves/mh/runtime/DeepSeekHarnessProtocol.kt"
replace_once(
    dsh,
    '            ProviderKind.KIMI -> customRoute("mh-kimi", model, baseUrl)\n            ProviderKind.CUSTOM -> customRoute("mh-custom", model, baseUrl)',
    '''            ProviderKind.KIMI -> customRoute("mh-kimi", model, baseUrl)
            ProviderKind.NVIDIA_NIM -> DeepSeekHarnessRoute(
                name = "nvidia-nim",
                keyEnvironmentVariable = FALLBACK_KEY_ENV,
                model = model,
                custom = DeepSeekHarnessCustomRoute(DshApiProtocol.OPENAI_COMPLETIONS, baseUrl),
            )
            ProviderKind.CUSTOM -> customRoute("mh-custom", model, baseUrl)''',
)

prefs = "app/src/main/java/com/jarves/mh/data/AppPreferences.kt"
replace_once(prefs, 'import android.content.Context\n', 'import android.content.Context\nimport com.jarves.mh.model.AgentKind\n')
replace_once(
    prefs,
    '''    var legacySeededCredentialRemoved: Boolean
''',
    '''    var selectedAgentKind: String
        get() = preferences.getString("selected_agent_kind", AgentKind.CLAUDE_CODE.name) ?: AgentKind.CLAUDE_CODE.name
        set(value) { preferences.edit().putString("selected_agent_kind", value).apply() }

    var legacySeededCredentialRemoved: Boolean
''',
)

vm = "app/src/main/java/com/jarves/mh/ui/MainViewModel.kt"
replace_once(vm, 'import com.jarves.mh.data.AppPreferences\n', 'import com.jarves.mh.data.AppPreferences\nimport com.jarves.mh.model.AgentKind\n')
replace_once(
    vm,
    '    val backgroundSetupComplete: Boolean = false,\n    val provider: ProviderProfile',
    '    val backgroundSetupComplete: Boolean = false,\n    val selectedAgent: AgentKind = AgentKind.CLAUDE_CODE,\n    val provider: ProviderProfile',
)
replace_once(
    vm,
    '            backgroundSetupComplete = preferences.backgroundSetupComplete,\n            provider = preferences.loadProvider(vault),',
    '''            backgroundSetupComplete = preferences.backgroundSetupComplete,
            selectedAgent = runCatching { AgentKind.valueOf(preferences.selectedAgentKind) }
                .getOrDefault(AgentKind.CLAUDE_CODE),
            provider = preferences.loadProvider(vault),''',
)
replace_once(
    vm,
    '''    fun getSavedApiKey(kind: ProviderKind): String = vault.get(kind.name).orEmpty()
''',
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

    fun getSavedApiKey(kind: ProviderKind): String = vault.get(kind.name).orEmpty()
''',
)

settings = "app/src/main/java/com/jarves/mh/ui/SettingsScreenModern.kt"
replace_once(settings, 'import com.jarves.mh.data.ApiKeyPool\n', 'import com.jarves.mh.data.ApiKeyPool\nimport com.jarves.mh.model.AgentKind\n')
replace_once(
    settings,
    'private enum class SettingsSection { CONNECTION, APPEARANCE, TOOLS, RUNTIME, APP_UPDATE, UPDATE_CHANNEL }',
    'private enum class SettingsSection { AGENT, CONNECTION, APPEARANCE, TOOLS, RUNTIME, APP_UPDATE, UPDATE_CHANNEL }',
)
replace_once(
    settings,
    '    onValidateProvider: suspend (ProviderProfile, String, List<DiscoveredModel>) -> ConnectionValidation,\n    onSetThemeMode:',
    '    onValidateProvider: suspend (ProviderProfile, String, List<DiscoveredModel>) -> ConnectionValidation,\n    onSelectAgent: (AgentKind) -> Unit,\n    onSetThemeMode:',
)
anchor = '''
            item {
                SettingsAccordion(
                    title = "AI connection",
'''
agent_section = '''
            item {
                SettingsAccordion(
                    title = "Coding agent",
                    subtitle = state.selectedAgent.title,
                    icon = Icons.Default.SmartToy,
                    expanded = expanded == SettingsSection.AGENT,
                    onClick = { toggle(SettingsSection.AGENT) },
                ) {
                    Text(
                        "Choose the coding agent. Installation and account status are shown for each option.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AgentKind.entries.forEachIndexed { index, agent ->
                        val statusText = when (agent) {
                            AgentKind.CLAUDE_CODE -> "Ready"
                            AgentKind.DEEPSEEK_HARNESS -> "Routing foundation installed"
                            AgentKind.ANTIGRAVITY -> "Protocol foundation installed"
                        }
                        Row(
                            Modifier.fillMaxWidth().clickable { onSelectAgent(agent) }.padding(vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(agent.title, fontWeight = FontWeight.SemiBold)
                                Text(agent.subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(statusText, fontSize = 10.sp, color = if (agent == AgentKind.CLAUDE_CODE) Color(0xFF58C9A3) else PocketOrange)
                            }
                            SelectionDot(state.selectedAgent == agent)
                        }
                        if (index != AgentKind.entries.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
            }

            item {
                SettingsAccordion(
                    title = "AI connection",
'''
replace_once(settings, anchor, agent_section)

pocket = "app/src/main/java/com/jarves/mh/ui/PocketDevApp.kt"
replace_once(
    pocket,
    '                    onValidateProvider = viewModel::validateProvider,\n                    onSetThemeMode = viewModel::setThemeMode,',
    '                    onValidateProvider = viewModel::validateProvider,\n                    onSelectAgent = viewModel::selectAgent,\n                    onSetThemeMode = viewModel::setThemeMode,',
)
