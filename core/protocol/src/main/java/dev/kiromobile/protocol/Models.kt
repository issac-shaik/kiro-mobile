package dev.kiromobile.protocol

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Choice(val id: String, val name: String)
data class Session(val id: String, val title: String, val cwd: String, val owned: Boolean)
data class Message(val id: String, val role: String, val text: String, val streaming: Boolean)
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
    val pushConfigured: Boolean, val pushError: String?, val busy: Boolean, val demo: Boolean,
    val imageSupported: Boolean, val connectionKind: String, val activity: String?
) {
    companion object {
        fun parse(j: JSONObject): Snapshot {
            val session = j.optJSONObject("selectedSession")
            val usage = j.optJSONObject("usage")
            val push = j.optJSONObject("push")
            return Snapshot(j.optLong("revision"),j.optString("status","offline"),j.stringOrNull("error"),
                j.optJSONArray("sessions").objects().map { Session(it.optString("sessionId"),it.optString("title","Untitled session"),it.optString("cwd"),it.optBoolean("owned")) },
                session?.stringOrNull("sessionId"),session?.stringOrNull("cwd"),
                j.optJSONArray("transcript").objects().map { Message(it.optString("id"),it.optString("role"),it.optString("text"),it.optBoolean("streaming")) },
                j.optJSONArray("permissions").objects().map { p -> Permission(p.optString("id"),p.optString("title"),p.optJSONObject("toolCall")?.toString(2) ?: "",p.optJSONArray("options").objects().map { Choice(it.optString("optionId"),it.optString("name")) }) },
                j.optJSONArray("models").choices(),j.optJSONArray("reasoning").choices(),j.stringOrNull("currentModel"),j.stringOrNull("currentReasoning"),j.optString("agentPreset","default"),j.optBoolean("agentPresetNative"),
                if(usage?.optBoolean("available")==true && !usage.isNull("remaining")) usage.optDouble("remaining") else null,
                usage?.optString("plan")?.takeIf { it.isNotBlank() && it!="null" } ?: usage?.optString("reason") ?: "Usage not available",
                push?.optBoolean("configured")==true,push?.stringOrNull("error"),j.optBoolean("busy"),j.optString("source")=="demo",j.optBoolean("imageSupported"),j.optString("connectionKind","resume"),j.stringOrNull("activity"))
        }
    }
}
fun JSONArray?.objects(): List<JSONObject> = if(this==null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONArray?.choices() = objects().map { Choice(it.optString("id"),it.optString("name",it.optString("id"))) }
fun JSONObject.stringOrNull(key: String): String? = if(isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it!="null" }
fun command(type: String, vararg fields: Pair<String,Any?>): JSONObject = JSONObject().put("type",type).put("requestId",UUID.randomUUID().toString()).apply { fields.forEach { put(it.first,it.second ?: JSONObject.NULL) } }
