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
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import dev.kiromobile.connection.PairingStore
import dev.kiromobile.connection.QrPairing
import dev.kiromobile.connection.BridgeClient
import org.json.JSONObject
import androidx.test.uiautomator.UiDevice
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
        val scannerMonitor=instrumentation.addMonitor("com.journeyapps.barcodescanner.CaptureActivity",null,false)
        click("Scan PC QR code")
        assertNotNull("QR scanner did not open",instrumentation.waitForMonitorWithTimeout(scannerMonitor,10000))
        instrumentation.removeMonitor(scannerMonitor)
        UiDevice.getInstance(instrumentation).pressBack()
        waitUntil { text("Scan PC QR code")!=null }
        click("Enter details manually")
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
        onMain {
            val body=windows().filterIsInstance<TextView>().first { it.text.contains("Bold answer") }
            val rendered=body.text as Spanned
            assertFalse(rendered.toString().contains("**"))
            assertFalse(rendered.toString().contains("##"))
            assertTrue(rendered.toString().contains("2 * 3"))
            val start=rendered.toString().indexOf("Bold answer")
            assertTrue("Markdown emphasis must draw in bold",rendered.getSpans(start,start+"Bold answer".length,MetricAffectingSpan::class.java).any { span ->
                val paint=TextPaint(body.paint);span.updateDrawState(paint);paint.isFakeBoldText || paint.typeface?.isBold==true
            })
        }
        onMain { assertNotNull(text("Default ▾"));assertNotNull(text("Low ▾")) }
        onMain {
            assertTrue(views(activity.window.decorView).filterIsInstance<android.widget.Switch>().first().isChecked)
            assertNotNull(text("123.50 credits remaining"))
        }
        click("Context window: 24.0% used")
        waitUntil { text("24.0% of the context window used")!=null };click("OK")
        click("Autopilot")
        waitUntil { views(activity.window.decorView).filterIsInstance<android.widget.Switch>().firstOrNull()?.isChecked==false }
        capture("chat")
        click("Demo · Balanced ▾");item(1);waitUntil { text("Demo · Fast ▾")!=null }
        click("Low ▾");item(1);waitUntil { text("High ▾")!=null }
        click("Default ▾");waitUntil { text("Agent")!=null };capture("agents");item(4);waitUntil { text("Plan ▾")!=null }
        onMain { views(activity.window.decorView).filterIsInstance<EditText>().first().setText("Continue **from my phone** with `2 * 3`") }
        click("Send")
        onMain { activity.moveTaskToBack(true) }
        val manager=context.getSystemService(NotificationManager::class.java)
        waitUntil { manager.activeNotifications.any { it.tag?.startsWith("permission:")==true } }
        waitUntil { manager.activeNotifications.any { it.id==1 } }
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitUntil { text("Review request")!=null }
        onMain {
            val body=windows().filterIsInstance<TextView>().firstOrNull { it.text.contains("Continue from my phone with") && it.text.contains("2 * 3") }
            assertNotNull("Sent messages must render emphasis and preserve code",body)
            assertFalse(body!!.text.toString().contains("**"))
        }
        capture("permission")
        click("Review request");waitUntil { text("Choose response")!=null };click("Choose response");item(1)
        waitUntil { manager.activeNotifications.none { it.tag?.startsWith("permission:")==true } }
        waitUntil { text("0.020 credits used · 1.3s elapsed")!=null }
        waitUntil { text("Review request")==null }
        click("Autopilot")
        waitUntil { views(activity.window.decorView).filterIsInstance<android.widget.Switch>().firstOrNull()?.isChecked==true }
        onMain { views(activity.window.decorView).filterIsInstance<EditText>().first().setText("Try Autopilot") }
        click("Send")
        waitUntil { windows().filterIsInstance<TextView>().count { it.text.toString()=="0.020 credits used · 1.3s elapsed" }==2 }
        onMain { assertNull(text("Review request")) }
        capture("summary")
    }
    @Test fun qrPairingVerifiesPcIdentityAndRejectsReplays() {
        val payload=InstrumentationRegistry.getArguments().getString("qrPairing")
        org.junit.Assume.assumeNotNull(payload)
        val invalid=JSONObject(payload!!).put("certSha256","0".repeat(64)).toString()
        val unpinned=JSONObject(payload).put("certSha256",JSONObject.NULL).toString()
        try { QrPairing.redeem(unpinned);fail("Unpinned relay pairing was accepted") } catch(_: IllegalArgumentException) {}
        try { QrPairing.redeem(invalid);fail("Wrong PC certificate was accepted") } catch(_: IllegalStateException) {}
        val pairing=QrPairing.redeem(payload)
        assertEquals("d".repeat(43),pairing.token)
        assertNotNull(pairing.certSha256)
        assertEquals("online",BridgeClient(pairing).snapshot().status)
        PairingStore(context).save(pairing.endpoint,pairing.token,pairing.certSha256)
        assertEquals(pairing,PairingStore(context).read())
        try { QrPairing.redeem(payload);fail("Used invitation was accepted") } catch(_: IllegalArgumentException) {}
    }
}
