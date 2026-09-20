from pathlib import Path

def one(text, old, new):
    assert text.count(old) == 1, (old[:80], text.count(old))
    return text.replace(old, new, 1)

root=Path('.')
data=root/'app/src/main/java/com/jarves/mh/data/AiUsageStore.kt'
data.write_text('''package com.jarves.mh.data

import android.content.Context
import com.jarves.mh.model.AgentKind
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class AiUsageStatus { RUNNING, SUCCESS, FAILED, RATE_LIMITED }
data class AiUsageEntry(val id:String,val startedAtMillis:Long,val finishedAtMillis:Long?,val agent:String,val provider:String,val model:String,val status:AiUsageStatus)
data class ProviderUsageSummary(val provider:String,val calls:Int,val responses:Int,val failed:Int,val rateLimited:Int)
data class AiUsageSummary(val day:String=ApiKeyPool.today(),val calls:Int=0,val responses:Int=0,val failed:Int=0,val rateLimited:Int=0,val providers:List<ProviderUsageSummary> = emptyList(),val history:List<AiUsageEntry> = emptyList())
data class ApiKeyHealth(val index:Int,val suffix:String,val status:String,val currentOrNext:Boolean)

/** Stores metadata only: never prompts, responses, or raw keys. */
class AiUsageStore(context:Context) {
 private val prefs=context.getSharedPreferences("pocket_ai_usage",Context.MODE_PRIVATE)
 @Synchronized fun start(agent:AgentKind,provider:String,model:String):String { val id=UUID.randomUUID().toString(); save(load()+AiUsageEntry(id,System.currentTimeMillis(),null,agent.title,provider,model,AiUsageStatus.RUNNING)); return id }
 @Synchronized fun finish(id:String?,status:AiUsageStatus) { if(id==null)return; val rows=load().toMutableList(); val i=rows.indexOfLast{it.id==id}; if(i>=0){rows[i]=rows[i].copy(finishedAtMillis=System.currentTimeMillis(),status=status);save(rows)} }
 @Synchronized fun snapshot():AiUsageSummary { val today=ApiKeyPool.today(); val rows=load().filter{day(it.startedAtMillis)==today}; val groups=rows.groupBy{it.provider}.map{(p,r)->ProviderUsageSummary(p,r.size,r.count{it.status==AiUsageStatus.SUCCESS},r.count{it.status==AiUsageStatus.FAILED},r.count{it.status==AiUsageStatus.RATE_LIMITED})}.sortedByDescending{it.calls}; return AiUsageSummary(today,rows.size,rows.count{it.status==AiUsageStatus.SUCCESS},rows.count{it.status==AiUsageStatus.FAILED},rows.count{it.status==AiUsageStatus.RATE_LIMITED},groups,rows.sortedByDescending{it.startedAtMillis}.take(50)) }
 private fun load():List<AiUsageEntry>{val raw=prefs.getString("entries",null)?:return emptyList();return runCatching{val a=JSONArray(raw);(0 until a.length()).mapNotNull{i->runCatching{val o=a.getJSONObject(i);AiUsageEntry(o.getString("id"),o.getLong("started"),o.optLong("finished").takeIf{o.has("finished")},o.optString("agent","AI agent"),o.optString("provider","Unknown"),o.optString("model","Default"),runCatching{AiUsageStatus.valueOf(o.optString("status"))}.getOrDefault(AiUsageStatus.FAILED))}.getOrNull()}}.getOrDefault(emptyList())}
 private fun save(rows:List<AiUsageEntry>){val cutoff=System.currentTimeMillis()-30L*86400000L;val a=JSONArray();rows.filter{it.startedAtMillis>=cutoff}.takeLast(500).forEach{e->a.put(JSONObject().put("id",e.id).put("started",e.startedAtMillis).apply{e.finishedAtMillis?.let{put("finished",it)}}.put("agent",e.agent).put("provider",e.provider).put("model",e.model).put("status",e.status.name))};prefs.edit().putString("entries",a.toString()).apply()}
 private fun day(ms:Long)=SimpleDateFormat("yyyy-MM-dd",Locale.US).format(Date(ms))
}
''')

p=root/'app/src/main/java/com/jarves/mh/ui/MainViewModel.kt';s=p.read_text()
s=one(s,'import com.jarves.mh.data.AppPreferences\n','import com.jarves.mh.data.AppPreferences\nimport com.jarves.mh.data.AiUsageStore\nimport com.jarves.mh.data.AiUsageStatus\nimport com.jarves.mh.data.AiUsageSummary\nimport com.jarves.mh.data.ApiKeyHealth\n')
s=one(s,'    val apiPingMessage: String? = null,\n    val projects: List<Project> = emptyList(),','    val apiPingMessage: String? = null,\n    val aiUsage: AiUsageSummary = AiUsageSummary(),\n    val apiKeyHealth: List<ApiKeyHealth> = emptyList(),\n    val projects: List<Project> = emptyList(),')
s=one(s,'    private val exhaustedKeys = ExhaustedKeyStore(application)\n    private val claudeRuntime','    private val exhaustedKeys = ExhaustedKeyStore(application)\n    private val usageStore = AiUsageStore(application)\n    @Volatile private var activeUsageId: String? = null\n    private val claudeRuntime')
s=one(s,'        onKeyExhausted = { profile, key -> exhaustedKeys.markExhausted(profile.kind.name, key) },','        onKeyExhausted = { profile, key -> exhaustedKeys.markExhausted(profile.kind.name, key); refreshUsageState() },')
s=one(s,'    init {\n        RuntimeSetupController.restore(application)','    init {\n        refreshUsageState()\n        RuntimeSetupController.restore(application)')
s=one(s,'        _state.update { it.copy(onboardingComplete = true, provider = saved, startupStage = StartupStage.READY) }\n        pingApi()','        _state.update { it.copy(onboardingComplete = true, provider = saved, startupStage = StartupStage.READY) }\n        refreshUsageState()\n        pingApi()')
method='''    private fun refreshUsageState() {
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

'''
s=one(s,'    fun sendPrompt(prompt: String) {\n        val project = state.value.activeProject ?: return\n',method+'    fun sendPrompt(prompt: String) {\n        val project = state.value.activeProject ?: return\n')
s=one(s,'        val requestText = prompt.trim().ifBlank { "Please review the attached files." }\n        updateActiveChatTitle(requestText)','''        val requestText = prompt.trim().ifBlank { "Please review the attached files." }
        val u = state.value
        val provider = if (u.selectedAgent == AgentKind.ANTIGRAVITY) "Google Antigravity" else u.provider.kind.title
        val model = if (u.selectedAgent == AgentKind.ANTIGRAVITY) u.antigravityModel.ifBlank { "Google AI" } else u.provider.model.ifBlank { "Default model" }
        activeUsageId = usageStore.start(u.selectedAgent, provider, model)
        refreshUsageState()
        updateActiveChatTitle(requestText)''')
finish='''        when (event) {
            is RuntimeEvent.SessionCompleted -> { usageStore.finish(activeUsageId, AiUsageStatus.SUCCESS); activeUsageId = null; refreshUsageState() }
            is RuntimeEvent.SessionFailed -> {
                val limited = event.reason.contains("429") || event.reason.contains("rate limit", true) || event.reason.contains("quota", true) || event.reason.contains("credit", true)
                usageStore.finish(activeUsageId, if (limited) AiUsageStatus.RATE_LIMITED else AiUsageStatus.FAILED)
                activeUsageId = null
                refreshUsageState()
            }
            else -> Unit
        }
'''
s=one(s,'        if (event is RuntimeEvent.FilesChanged || event is RuntimeEvent.SessionCompleted) {',finish+'        if (event is RuntimeEvent.FilesChanged || event is RuntimeEvent.SessionCompleted) {')
p.write_text(s)

p=root/'app/src/main/java/com/jarves/mh/ui/PocketDevApp.kt';s=p.read_text()
s=one(s,'import com.jarves.mh.data.ApiKeyPool\n','import com.jarves.mh.data.ApiKeyPool\nimport com.jarves.mh.data.AiUsageEntry\nimport com.jarves.mh.data.AiUsageStatus\n')
s=one(s,'    var screen by rememberSaveable { mutableStateOf(RootScreen.PROJECTS) }\n    val keyboardVisible','    var screen by rememberSaveable { mutableStateOf(RootScreen.PROJECTS) }\n    var usageVisible by rememberSaveable { mutableStateOf(false) }\n    BackHandler(enabled = usageVisible) { usageVisible = false }\n    val keyboardVisible')
s=one(s,'            if (!keyboardVisible) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {\n                RootScreen.entries.forEach','            if (!keyboardVisible && !usageVisible) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {\n                RootScreen.entries.forEach')
s=one(s,'        Box(Modifier.fillMaxSize().padding(padding)) {\n            when (screen) {','        Box(Modifier.fillMaxSize().padding(padding)) {\n            if (usageVisible) AiUsageScreen(state) { usageVisible = false } else when (screen) {')
s=one(s,'                    onInstallUpdate = viewModel::installAppUpdate,\n                )','                    onInstallUpdate = viewModel::installAppUpdate,\n                    onUsage = { usageVisible = true },\n                )')
s=one(s,'    onInstallUpdate: () -> Unit,\n) {','    onInstallUpdate: () -> Unit,\n    onUsage: () -> Unit,\n) {')
s=one(s,'                Text("Chat, review changes, and preview your project.", color = MaterialTheme.colorScheme.onSurfaceVariant)\n                Spacer(Modifier.height(12.dp))\n                ApiStatusChip','                Text("Chat, review changes, and preview your project.", color = MaterialTheme.colorScheme.onSurfaceVariant)\n                Spacer(Modifier.height(12.dp))\n                AiActivityCard(state, onUsage)\n                Spacer(Modifier.height(10.dp))\n                ApiStatusChip')
marker='@OptIn(ExperimentalMaterial3Api::class)\n@Composable\nprivate fun ProjectsScreen('
ui='''@Composable
private fun AiActivityCard(state: AppUiState, onClick: () -> Unit) {
 val u=state.aiUsage; val route=if(state.selectedAgent==com.jarves.mh.model.AgentKind.ANTIGRAVITY) "Antigravity · ${state.antigravityModel.ifBlank{"Google AI"}}" else "${state.selectedAgent.title} · ${state.provider.model.ifBlank{state.provider.kind.title}}"; val ready=state.apiKeyHealth.count{it.status!="Exhausted today"}
 Surface(Modifier.fillMaxWidth().clickable(onClick=onClick),shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.primary.copy(alpha=.07f),border=BorderStroke(1.dp,MaterialTheme.colorScheme.primary.copy(alpha=.24f))){Column(Modifier.padding(15.dp)){Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Speed,null,tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.width(9.dp));Column(Modifier.weight(1f)){Text("AI activity today",fontWeight=FontWeight.Bold);Text(route,fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)};Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight,"Open usage")};Spacer(Modifier.height(8.dp));Text("${u.calls} calls · ${u.responses} responses · ${u.failed+u.rateLimited} issues",fontSize=12.sp);if(state.apiKeyHealth.isNotEmpty())Text("${state.provider.kind.title} keys: $ready of ${state.apiKeyHealth.size} ready",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiUsageScreen(state:AppUiState,onBack:()->Unit){val u=state.aiUsage;Scaffold(topBar={TopAppBar(title={Text("AI usage & keys",fontWeight=FontWeight.Bold)},navigationIcon={IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Filled.ArrowBack,"Back")}})}){pad->LazyColumn(Modifier.fillMaxSize().padding(pad),contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){item{Text("TODAY",color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.Bold,fontSize=10.sp);Spacer(Modifier.height(7.dp));Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){UsageMetric("Calls",u.calls,Modifier.weight(1f));UsageMetric("Responses",u.responses,Modifier.weight(1f));UsageMetric("Issues",u.failed+u.rateLimited,Modifier.weight(1f))}};item{UsageSection("Providers"){if(u.providers.isEmpty())Text("No calls recorded yet. Tracking starts with the next task.",color=MaterialTheme.colorScheme.onSurfaceVariant) else u.providers.forEach{Text(it.provider,fontWeight=FontWeight.SemiBold);Text("${it.calls} calls · ${it.responses} responses · ${it.failed} failed · ${it.rateLimited} limited",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant);Spacer(Modifier.height(8.dp))}}};item{UsageSection("${state.provider.kind.title} API key pool"){if(state.apiKeyHealth.isEmpty())Text("No key pool saved.",color=MaterialTheme.colorScheme.onSurfaceVariant) else state.apiKeyHealth.forEach{k->Row(Modifier.padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(9.dp).background(if(k.status=="Exhausted today")MaterialTheme.colorScheme.error else if(k.currentOrNext)PocketGreen else MaterialTheme.colorScheme.outline,CircleShape));Spacer(Modifier.width(10.dp));Column{Text("Key ${k.index} · ••••${k.suffix}",fontSize=13.sp);Text(k.status,fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}}};item{UsageSection("Recent activity"){if(u.history.isEmpty())Text("No activity recorded today.",color=MaterialTheme.colorScheme.onSurfaceVariant) else u.history.take(20).forEach{UsageHistoryRow(it)}}};item{Text("Stored locally: provider, model, status and time only. Prompts, responses and full keys are never saved.",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}}
@Composable private fun UsageMetric(label:String,value:Int,modifier:Modifier){Surface(modifier,shape=RoundedCornerShape(14.dp),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)){Column(Modifier.padding(12.dp)){Text(value.toString(),fontSize=22.sp,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary);Text(label,fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
@Composable private fun UsageSection(title:String,content:@Composable()->Unit){Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)){Column(Modifier.padding(14.dp)){Text(title,fontWeight=FontWeight.Bold);Spacer(Modifier.height(7.dp));content()}}}
@Composable private fun UsageHistoryRow(e:AiUsageEntry){val color=when(e.status){AiUsageStatus.SUCCESS->PocketGreen;AiUsageStatus.RUNNING->PocketOrange;else->MaterialTheme.colorScheme.error};val time=remember(e.startedAtMillis){java.text.SimpleDateFormat("h:mm a",java.util.Locale.getDefault()).format(java.util.Date(e.startedAtMillis))};Row(Modifier.padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(9.dp).background(color,CircleShape));Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){Text("${e.agent} · ${e.model}",fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis);Text("${e.provider} · ${e.status.name.lowercase()}",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)};Text(time,fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}}

'''
assert marker in s;s=s.replace(marker,ui+marker,1);p.write_text(s)
print('implemented')
