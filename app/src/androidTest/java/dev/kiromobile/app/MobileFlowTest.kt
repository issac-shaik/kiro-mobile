package dev.kiromobile.app

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import dev.kiromobile.connection.PairingStore
import org.junit.Assert.*
import org.junit.Test
import java.io.File

@android.annotation.TargetApi(29)
class MobileFlowTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private lateinit var activity: Activity
    private fun views(root: View): List<View> = listOf(root)+(if(root is ViewGroup)(0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList())
    private fun windows()=WindowInspector.getGlobalWindowViews().filter { it.windowVisibility==View.VISIBLE }.flatMap { views(it) }
    private fun text(value: String): TextView? = windows().filterIsInstance<TextView>().lastOrNull { it.text.toString()==value }
    private fun onMain(action: ()->Unit) = instrumentation.runOnMainSync(action)
    private fun click(value: String) = onMain { assertTrue("Missing UI control: $value",text(value)!=null || windows().any { it.contentDescription?.toString()==value });(text(value) ?: windows().last { it.contentDescription?.toString()==value }).performClick() }
    private fun waitUntil(timeout: Long=20000,check: ()->Boolean) {
        val deadline=System.currentTimeMillis()+timeout
        while(System.currentTimeMillis()<deadline) { var ready=false;onMain { ready=check() };if(ready)return;Thread.sleep(200) }
        var ready=false;onMain { ready=check() };assertTrue("Condition did not become true",ready)
    }
    private fun capture(name: String) = onMain {
        val root=WindowInspector.getGlobalWindowViews().lastOrNull { it.windowVisibility==View.VISIBLE } ?: activity.window.decorView
        val bitmap=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888);root.draw(Canvas(bitmap))
        File(context.getExternalFilesDir(null),"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
    private fun item(index: Int) {
        waitUntil { windows().filterIsInstance<ListView>().isNotEmpty() }
        onMain { val list=windows().filterIsInstance<ListView>().last();list.performItemClick(list.getChildAt(index),index,list.adapter.getItemId(index)) }
    }
    @Test fun pairingChatAndBackgroundPermissionNotification() {
        PairingStore(context).clear()
        activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        waitUntil { text("Connect to Kiro")!=null }
        capture("setup")
        onMain {
            val fields=views(activity.window.decorView).filterIsInstance<EditText>()
            fields[0].setText("http://10.0.2.2:8877");fields[1].setText("d".repeat(43))
        }
        click("Connect to Kiro")
        waitUntil { text("● Demo connection")!=null }
        capture("welcome")
        assertEquals("d".repeat(43),PairingStore(context).read()?.token)
        assertFalse(context.getSharedPreferences("pairing",Context.MODE_PRIVATE).getString("secret","")!!.contains("d".repeat(43)))
        click("Sessions");waitUntil { windows().filterIsInstance<ListView>().isNotEmpty() };item(0)
        click("Continue on phone")
        waitUntil { text("Explore your mobile workspace")!=null }
        onMain { assertNotNull(text("Default ▾"));assertNotNull(text("Low ▾")) }
        capture("chat")
        click("Demo · Balanced ▾");item(1);waitUntil { text("Demo · Fast ▾")!=null }
        click("Low ▾");item(1);waitUntil { text("High ▾")!=null }
        click("Default ▾");waitUntil { text("Agent")!=null };capture("agents");item(4);waitUntil { text("Plan ▾")!=null }
        onMain { views(activity.window.decorView).filterIsInstance<EditText>().first().setText("Continue from my phone") }
        click("Send")
        onMain { activity.moveTaskToBack(true) }
        val manager=context.getSystemService(NotificationManager::class.java)
        waitUntil { manager.activeNotifications.any { it.tag?.startsWith("permission:")==true } }
        waitUntil { manager.activeNotifications.any { it.id==1 } }
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitUntil { text("Review request")!=null }
        capture("permission")
        click("Review request");click("Choose response");item(1)
        waitUntil { manager.activeNotifications.none { it.tag?.startsWith("permission:")==true } }
        waitUntil { text("Review request")==null }
    }
}
