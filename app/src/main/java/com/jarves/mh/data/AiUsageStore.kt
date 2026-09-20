package com.jarves.mh.data

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
