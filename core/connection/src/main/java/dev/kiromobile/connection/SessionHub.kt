package dev.kiromobile.connection

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.kiromobile.protocol.Snapshot
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

// One session stream per app process. UI and notifications subscribe independently.
object SessionHub {
    private val main=Handler(Looper.getMainLooper())
    private val listeners=CopyOnWriteArraySet<(Snapshot?,String?)->Unit>()
    private val commands=Executors.newSingleThreadExecutor()
    private val generation=AtomicInteger()
    @Volatile private var client: SessionConnection?=null
    @Volatile var snapshot: Snapshot?=null; private set
    @Volatile var error: String?=null; private set
    fun observe(listener: (Snapshot?,String?)->Unit) { listeners.add(listener);main.post { listener(snapshot,error) } }
    fun remove(listener: (Snapshot?,String?)->Unit) { listeners.remove(listener) }
    private fun publish() { main.post { listeners.forEach { it(snapshot,error) } } }
    @Synchronized fun connect(context: Context) {
        if(client!=null)return
        val pairing=PairingStore(context.applicationContext).read() ?: return
        val connection=BridgeClient(pairing);client=connection
        val current=generation.incrementAndGet()
        Thread({
            var revision=0L;var failures=0
            while(generation.get()==current) {
                try {
                    val updated=connection.snapshot(revision)
                    if(generation.get()!=current)break
                    snapshot=updated;error=null;revision=updated.revision;failures=0;publish()
                } catch(e: Exception) {
                    if(generation.get()!=current)break
                    error=e.message ?: "Cannot reach your PC";publish();failures++
                    try { Thread.sleep((1000L shl failures.coerceAtMost(5)).coerceAtMost(30000)) } catch(_: InterruptedException) { break }
                }
            }
        },"kiro-session-stream").apply { isDaemon=true;start() }
    }
    fun send(command: JSONObject, completed: (String?)->Unit={}) {
        val active=client
        val version=generation.get()
        commands.execute {
            val result=try {
                check(generation.get()==version && client===active) { "Connection changed. Review and submit again." }
                val connection=checkNotNull(active) { "Pair with your PC first" }
                try { connection.send(command) } catch(first: java.io.IOException) {
                    // Same request id: one network retry cannot submit the prompt twice.
                    Thread.sleep(500)
                    check(generation.get()==version && client===active) { "Connection changed. Review and submit again." }
                    connection.send(command)
                }
                null
            } catch(e: Exception) { e.message ?: "Request failed" }
            main.post { completed(result) }
        }
    }
    fun registerPush(deviceId: String, token: String) { commands.execute { try { client?.registerPush(deviceId,token) } catch(_: Exception) {} } }
    @Synchronized fun disconnect() { generation.incrementAndGet();client?.close();client=null;snapshot=null;error=null;publish() }
}
