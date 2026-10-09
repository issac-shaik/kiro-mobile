package dev.kiromobile.protocol

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Choice(val id: String, val name: String)
data class Session(val id: String, val title: String, val cwd: String, val owned: Boolean)
data class ToolActivity(val title: String, val status: String, val details: String)
data class Message(val id: String, val role: String, val text: String, val streaming: Boolean, val creditsUsed: Double?=null, val elapsedMs: Double?=null, val tool: ToolActivity?=null)
data class Permission(val id: String, val title: String, val details: String, val options: List<Choice>)
data class Attachment(val name: String, val mimeType: String, val data: String) {
    fun json() = JSONObject().put("name", name).put("mimeType", mimeType).put("data", data)
}
data class Snapshot(
    val revision: Long, val status: String, val error: String?, val sessions: List<Session>,
    val selectedId: String?, val selectedCwd: String?, val messages: List<Message>,
    val permissions: List<Permission>, val models: List<Choice>, val reasoning: List<Choice>,
    val currentModel: String?, val currentReasoning: String?, val preset: String,
    val presetNative: Boolean, val credits: Double?, val usageDescription: String,
    val pushConfigured: Boolean, val pushError: String?, val busy: Boolean, val sessionRunning: Boolean, val demo: Boolean,
    val imageSupported: Boolean, val connectionKind: String, val activity: String?,
    val autopilot: Boolean?, val autopilotSupported: Boolean, val contextUsagePercent: Double?
) {
    companion object {
        fun parse(j: JSONObject): Snapshot {
            val session = j.optJSONObject("selectedSession")
            val usage = j.optJSONObject("usage")
            val push = j.optJSONObject("push")
            return Snapshot(j.optLong("revision"),j.optString("status","offline"),j.stringOrNull("error"),
                j.optJSONArray("sessions").objects().map { Session(it.optString("sessionId"),it.optString("title","Untitled session"),it.optString("cwd"),it.optBoolean("owned")) },
                session?.stringOrNull("sessionId"),session?.stringOrNull("cwd"),
                j.optJSONArray("transcript").objects().map { Message(it.optString("id"),it.optString("role"),it.optString("text"),it.optBoolean("streaming"),it.optJSONObject("summary")?.numberOrNull("creditsUsed"),it.optJSONObject("summary")?.numberOrNull("elapsedMs"),it.optJSONObject("tool")?.let { tool ->
                    val status=when {
                        tool.optBoolean("waitingForPermission") -> "Needs approval"
                        tool.optString("status")=="failed" && tool.optString("failureReason")=="denied" -> "Denied"
                        tool.optString("status")=="failed" && tool.optString("failureReason")=="cancelled" -> "Cancelled"
                        else -> when(tool.optString("status")) { "pending" -> "Pending";"in_progress" -> "Running";"completed" -> "Completed";"failed" -> "Failed";"interrupted" -> "Interrupted";else -> "Status unavailable" }
                    }
                    val input=tool.stringOrNull("input");val output=tool.stringOrNull("output");val content=tool.stringOrNull("content");val locations=tool.stringOrNull("locations")
                    ToolActivity(tool.optString("title","Tool call"),status,listOfNotNull(locations?.let { "Files\n$it" },input?.let { "Input\n$it" },content?.let { "Result\n$it" },output?.takeIf { it!=content }?.let { "Output\n$it" }).joinToString("\n\n"))
                }) },
                j.optJSONArray("permissions").objects().map { p -> Permission(p.optString("id"),p.optString("title"),p.optJSONObject("toolCall")?.toString(2) ?: "",p.optJSONArray("options").objects().map { Choice(it.optString("optionId"),it.optString("name")) }) },
                j.optJSONArray("models").choices(),j.optJSONArray("reasoning").choices(),j.stringOrNull("currentModel"),j.stringOrNull("currentReasoning"),j.optString("agentPreset","default"),j.optBoolean("agentPresetNative"),
                if(usage?.optBoolean("available")==true && !usage.isNull("remaining")) usage.optDouble("remaining") else null,
                usage?.optString("plan")?.takeIf { it.isNotBlank() && it!="null" } ?: usage?.optString("reason") ?: "Usage not available",
                push?.optBoolean("configured")==true,push?.stringOrNull("error"),j.optBoolean("busy"),j.optBoolean("sessionRunning"),j.optString("source")=="demo",j.optBoolean("imageSupported"),j.optString("connectionKind","resume"),j.stringOrNull("activity"),
                if(j.isNull("autopilot"))null else j.optBoolean("autopilot"),j.optBoolean("autopilotSupported"),j.numberOrNull("contextUsagePercent"))
        }
    }
}
fun JSONArray?.objects(): List<JSONObject> = if(this==null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONArray?.choices() = objects().map { Choice(it.optString("id"),it.optString("name",it.optString("id"))) }
fun JSONObject.stringOrNull(key: String): String? = if(isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it!="null" }
fun JSONObject.numberOrNull(key: String): Double? = if(isNull(key))null else optDouble(key).takeIf { it.isFinite() && it>=0 }
fun command(type: String, vararg fields: Pair<String,Any?>): JSONObject = JSONObject().put("type",type).put("requestId",UUID.randomUUID().toString()).apply { fields.forEach { put(it.first,it.second ?: JSONObject.NULL) } }
