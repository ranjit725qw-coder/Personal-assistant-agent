from pathlib import Path
p=Path('app/src/main/java/com/jarves/mh/ui/PocketDevApp.kt')
s=p.read_text()
start=s.index('@Composable\nprivate fun AiActivityCard')
marker='@OptIn(ExperimentalMaterial3Api::class)\n@Composable\nprivate fun ProjectsScreen('
end=s.index(marker,start)
block=r'''@Composable
private fun AiActivityCard(state: AppUiState, onClick: () -> Unit) {
    val usage = state.aiUsage
    val route = if (state.selectedAgent == com.jarves.mh.model.AgentKind.ANTIGRAVITY) {
        "Antigravity · ${state.antigravityModel.ifBlank { "Google AI" }}"
    } else {
        "${state.selectedAgent.title} · ${state.provider.model.ifBlank { state.provider.kind.title }}"
    }
    val readyKeys = state.apiKeyHealth.count { it.status != "Exhausted today" }
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.07f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)),
    ) {
        Column(Modifier.padding(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Speed, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text("AI activity today", fontWeight = FontWeight.Bold)
                    Text(route, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Open usage")
            }
            Spacer(Modifier.height(8.dp))
            Text("${usage.calls} calls · ${usage.responses} responses · ${usage.failed + usage.rateLimited} issues", fontSize = 12.sp)
            if (state.apiKeyHealth.isNotEmpty()) {
                Text("${state.provider.kind.title} keys: $readyKeys of ${state.apiKeyHealth.size} ready", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiUsageScreen(state: AppUiState, onBack: () -> Unit) {
    val usage = state.aiUsage
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI usage & keys", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("TODAY", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                Spacer(Modifier.height(7.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UsageMetric("Calls", usage.calls, Modifier.weight(1f))
                    UsageMetric("Responses", usage.responses, Modifier.weight(1f))
                    UsageMetric("Issues", usage.failed + usage.rateLimited, Modifier.weight(1f))
                }
            }
            item {
                UsageSection(title = "Providers") {
                    if (usage.providers.isEmpty()) {
                        Text("No calls recorded yet. Tracking starts with the next task.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        usage.providers.forEach { provider ->
                            Text(provider.provider, fontWeight = FontWeight.SemiBold)
                            Text("${provider.calls} calls · ${provider.responses} responses · ${provider.failed} failed · ${provider.rateLimited} limited", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
            item {
                UsageSection(title = "${state.provider.kind.title} API key pool") {
                    if (state.apiKeyHealth.isEmpty()) {
                        Text("No key pool saved.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        state.apiKeyHealth.forEach { key ->
                            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(9.dp).background(
                                        if (key.status == "Exhausted today") MaterialTheme.colorScheme.error
                                        else if (key.currentOrNext) PocketGreen
                                        else MaterialTheme.colorScheme.outline,
                                        CircleShape,
                                    ),
                                )
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text("Key ${key.index} · ••••${key.suffix}", fontSize = 13.sp)
                                    Text(key.status, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
            item {
                UsageSection(title = "Recent activity") {
                    if (usage.history.isEmpty()) {
                        Text("No activity recorded today.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        usage.history.take(20).forEach { UsageHistoryRow(it) }
                    }
                }
            }
            item {
                Text("Stored locally: provider, model, status and time only. Prompts, responses and full keys are never saved.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun UsageMetric(label: String, value: Int, modifier: Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(12.dp)) {
            Text(value.toString(), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun UsageSection(title: String, content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(7.dp))
            content()
        }
    }
}

@Composable
private fun UsageHistoryRow(entry: AiUsageEntry) {
    val color = when (entry.status) {
        AiUsageStatus.SUCCESS -> PocketGreen
        AiUsageStatus.RUNNING -> PocketOrange
        AiUsageStatus.FAILED, AiUsageStatus.RATE_LIMITED -> MaterialTheme.colorScheme.error
    }
    val time = remember(entry.startedAtMillis) {
        java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(entry.startedAtMillis))
    }
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).background(color, CircleShape))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("${entry.agent} · ${entry.model}", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${entry.provider} · ${entry.status.name.lowercase()}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(time, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

'''
p.write_text(s[:start]+block+s[end:])
print('fixed dashboard UI')
