package dev.kiromobile.notifications

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.kiromobile.connection.PairingStore
import dev.kiromobile.connection.SessionHub
import dev.kiromobile.protocol.Snapshot
import java.util.UUID

object Alerts {
    const val CONNECTION_ID=1
    private fun manager(context: Context)=context.getSystemService(NotificationManager::class.java)
    fun channels(context: Context) {
        manager(context).createNotificationChannel(NotificationChannel("permissions","Agent permissions",NotificationManager.IMPORTANCE_HIGH).apply { description="Kiro is waiting for your decision";lockscreenVisibility=Notification.VISIBILITY_PRIVATE })
        manager(context).createNotificationChannel(NotificationChannel("connection","PC connection",NotificationManager.IMPORTANCE_LOW))
    }
    private fun open(context: Context, permissionId: String?=null): PendingIntent {
        val intent=context.packageManager.getLaunchIntentForPackage(context.packageName)!!.apply {
            flags=Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            permissionId?.let { putExtra("permissionId",it) }
        }
        return PendingIntent.getActivity(context,permissionId?.hashCode() ?: 0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    fun connection(context: Context, text: String)=Notification.Builder(context,"connection")
        .setSmallIcon(dev.kiromobile.design.R.drawable.kiro_mark).setContentTitle("Kiro Mobile").setContentText(text)
        .setContentIntent(open(context)).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE)
        .apply { if(Build.VERSION.SDK_INT>=31)setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }.build()
    fun permission(context: Context, id: String) {
        channels(context)
        if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
        val prefs=context.getSharedPreferences("notification-dedupe",Context.MODE_PRIVATE)
        if(prefs.contains(id))return
        // Stable ids deduplicate the private stream and Firebase delivery.
        prefs.edit().putLong(id,System.currentTimeMillis()).apply()
        prefs.all.filter { (it.value as? Long ?: 0)<System.currentTimeMillis()-86400000 }.keys.forEach { prefs.edit().remove(it).apply() }
        manager(context).notify("permission:$id",2,Notification.Builder(context,"permissions").setSmallIcon(dev.kiromobile.design.R.drawable.kiro_mark)
            .setContentTitle("Kiro needs your permission").setContentText("Your agent is waiting. Tap to review the request.")
            .setContentIntent(open(context,id)).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).setCategory(Notification.CATEGORY_MESSAGE).build())
    }
    fun resolved(context: Context, id: String) { manager(context).cancel("permission:$id",2) }
}

class ConnectionService : Service() {
    private var pending=emptySet<String>()
    private val observer: (Snapshot?,String?)->Unit={state,error->
        if(state!=null && error==null) {
            val current=state.permissions.map { it.id }.toSet()
            current.forEach { Alerts.permission(this,it) }
            (pending-current).forEach { Alerts.resolved(this,it) };pending=current
        }
        val label=if(error!=null) "PC unreachable Â· reconnecting" else if(state?.status=="online") "Connected Â· permission alerts active" else "Connecting to your PC"
        getSystemService(NotificationManager::class.java).notify(Alerts.CONNECTION_ID,Alerts.connection(this,label))
    }
    override fun onCreate() { super.onCreate();Alerts.channels(this);startForeground(Alerts.CONNECTION_ID,Alerts.connection(this,"Connecting to your PC"));SessionHub.observe(observer) }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(PairingStore(this).read()==null){stopSelf();return START_NOT_STICKY}
        SessionHub.connect(this);PushSetup.register(this);return START_STICKY
    }
    override fun onDestroy() { SessionHub.remove(observer);SessionHub.disconnect();super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder?=null
}

object PushSetup {
    var configured=false;private set
    fun initialize(context: Context, appId: String, key: String, project: String, sender: String) {
        Alerts.channels(context)
        if(listOf(appId,key,project,sender).any { it.isBlank() })return
        try {
            if(FirebaseApp.getApps(context).isEmpty())FirebaseApp.initializeApp(context,FirebaseOptions.Builder().setApplicationId(appId).setApiKey(key).setProjectId(project).setGcmSenderId(sender).build())
            configured=true;FirebaseMessaging.getInstance().isAutoInitEnabled=true
        } catch(_: Exception) { configured=false }
    }
    fun register(context: Context) {
        if(!configured)return
        FirebaseMessaging.getInstance().token.addOnSuccessListener { registerToken(context,it) }
    }
    fun registerToken(context: Context, token: String) {
        val prefs=context.getSharedPreferences("push-device",Context.MODE_PRIVATE)
        val id=prefs.getString("id",null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("id",it).apply() }
        SessionHub.connect(context);SessionHub.registerPush(id,token)
    }
}
class PermissionMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) { if(PushSetup.configured)PushSetup.registerToken(this,token) }
    override fun onMessageReceived(message: RemoteMessage) {
        if(PairingStore(this).read()==null)return
        if(message.data["type"]=="permission")message.data["permissionId"]?.let { Alerts.permission(this,it) }
    }
}
