package dev.kiromobile.connection

import dev.kiromobile.protocol.Snapshot
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

interface SessionConnection {
    fun snapshot(after: Long = 0): Snapshot
    fun send(command: JSONObject)
    fun registerPush(deviceId: String, token: String): Boolean
    fun close()
}
class BridgeClient(private val pairing: Pairing): SessionConnection {
    @Volatile private var polling: HttpURLConnection? = null
    private fun request(path: String, body: JSONObject?=null): JSONObject {
        val connection=URL(pairing.endpoint+path).openConnection() as HttpURLConnection
        connection.connectTimeout=10000;connection.readTimeout=30000
        connection.instanceFollowRedirects=false
        connection.setRequestProperty("Authorization","Bearer ${pairing.token}")
        connection.setRequestProperty("Accept","application/json")
        if(body==null) polling=connection
        try {
            if(body!=null) {
                connection.requestMethod="POST";connection.doOutput=true
                connection.setRequestProperty("Content-Type","application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code=connection.responseCode
            val stream=if(code in 200..299) connection.inputStream else connection.errorStream
            val text=stream?.bufferedReader()?.use { it.readText() } ?: "{}"
            val result=JSONObject(text)
            if(code !in 200..299) throw IllegalStateException(result.optString("error","Companion returned HTTP $code"))
            return result
        } finally { connection.disconnect(); if(body==null) polling=null }
    }
    override fun snapshot(after: Long)=Snapshot.parse(request("/v1/state?after=$after"))
    override fun send(command: JSONObject) { request("/v1/command",command) }
    override fun registerPush(deviceId: String, token: String)=request("/v1/push/register",JSONObject().put("deviceId",deviceId).put("token",token)).optBoolean("configured")
    override fun close() { polling?.disconnect() }
}
