from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text()
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected one match, found {count}: {old[:100]!r}")
    file.write_text(text.replace(old, new, 1))

main = "app/src/main/java/com/jarves/mh/ui/MainViewModel.kt"
settings = "app/src/main/java/com/jarves/mh/ui/SettingsScreenModern.kt"
pocket = "app/src/main/java/com/jarves/mh/ui/PocketDevApp.kt"

replace_once(main,
'''    val antigravityModels: List<String> = emptyList(),
    val antigravityModelsLoading: Boolean = false,
    val provider: ProviderProfile = ProviderProfile(ProviderKind.ANTHROPIC),''',
'''    val antigravityModels: List<String> = emptyList(),
    val antigravityModelsLoading: Boolean = false,
    val antigravityModelTestStatus: ApiPingStatus = ApiPingStatus.IDLE,
    val antigravityModelTestMessage: String? = null,
    val provider: ProviderProfile = ProviderProfile(ProviderKind.ANTHROPIC),''')

replace_once(main,
'''                antigravityEffort = modelEffort ?: it.antigravityEffort,
                toastMessage = "Google AI model selected: $model",
''',
'''                antigravityEffort = modelEffort ?: it.antigravityEffort,
                antigravityModelTestStatus = ApiPingStatus.IDLE,
                antigravityModelTestMessage = null,
                toastMessage = "Google AI model selected: $model",
''')

replace_once(main,
'''                antigravityEffort = effort,
                antigravityModel = matchingModel ?: it.antigravityModel,
            )
        }
    }

    fun refreshAntigravityModels()''',
'''                antigravityEffort = effort,
                antigravityModel = matchingModel ?: it.antigravityModel,
                antigravityModelTestStatus = ApiPingStatus.IDLE,
                antigravityModelTestMessage = null,
            )
        }
    }

    fun refreshAntigravityModels()''')

replace_once(main,
'''    fun getSavedApiKey(kind: ProviderKind): String = vault.get(kind.name).orEmpty()
''',
'''    fun testAntigravityModel() {
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

    fun getSavedApiKey(kind: ProviderKind): String = vault.get(kind.name).orEmpty()
''')

replace_once(settings,
'''    onRefreshAntigravityModels: () -> Unit,
    onSetAntigravityModel: (String) -> Unit,''',
'''    onRefreshAntigravityModels: () -> Unit,
    onTestAntigravityModel: () -> Unit,
    onSetAntigravityModel: (String) -> Unit,''')

replace_once(settings,
'''                                    OutlinedButton(onClick = onRefreshAntigravityModels, enabled = !state.antigravityModelsLoading, modifier = Modifier.fillMaxWidth()) {
                                        Icon(Icons.Default.Refresh, null, Modifier.size(17.dp))
                                        Spacer(Modifier.width(7.dp))
                                        Text("Refresh Google AI models")
                                    }
                                    OutlinedButton(onClick = onLogoutAntigravity, modifier = Modifier.fillMaxWidth()) { Text("Sign out of Google") }
''',
'''                                    OutlinedButton(onClick = onRefreshAntigravityModels, enabled = !state.antigravityModelsLoading, modifier = Modifier.fillMaxWidth()) {
                                        Icon(Icons.Default.Refresh, null, Modifier.size(17.dp))
                                        Spacer(Modifier.width(7.dp))
                                        Text("Refresh Google AI models")
                                    }
                                    Button(
                                        onClick = onTestAntigravityModel,
                                        enabled = state.antigravityModel.isNotBlank() &&
                                            !state.antigravityModelsLoading &&
                                            state.antigravityModelTestStatus != ApiPingStatus.PINGING,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        if (state.antigravityModelTestStatus == ApiPingStatus.PINGING) {
                                            CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                                            Spacer(Modifier.width(7.dp))
                                        }
                                        Text(if (state.antigravityModelTestStatus == ApiPingStatus.PINGING) "Testing selected model…" else "Test selected model")
                                    }
                                    state.antigravityModelTestMessage?.let { message ->
                                        Text(
                                            message,
                                            fontSize = 11.sp,
                                            color = when (state.antigravityModelTestStatus) {
                                                ApiPingStatus.OK -> Color(0xFF58C9A3)
                                                ApiPingStatus.FAILED -> MaterialTheme.colorScheme.error
                                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        )
                                    }
                                    OutlinedButton(onClick = onLogoutAntigravity, modifier = Modifier.fillMaxWidth()) { Text("Sign out of Google") }
''')

replace_once(pocket,
'''                    onRefreshAntigravityModels = viewModel::refreshAntigravityModels,
                    onSetAntigravityModel = viewModel::setAntigravityModel,''',
'''                    onRefreshAntigravityModels = viewModel::refreshAntigravityModels,
                    onTestAntigravityModel = viewModel::testAntigravityModel,
                    onSetAntigravityModel = viewModel::setAntigravityModel,''')

print("Applied Antigravity selected-model test UI and runtime wiring")
