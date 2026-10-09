package dev.kiromobile.chat

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.text.TextUtils
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import dev.kiromobile.connection.PairingStore
import dev.kiromobile.connection.SessionHub
import dev.kiromobile.connection.QrPairing
import com.google.zxing.integration.android.IntentIntegrator
import dev.kiromobile.notifications.ConnectionService
import dev.kiromobile.notifications.PushSetup
import dev.kiromobile.protocol.*
import dev.kiromobile.design.KiroTheme
import dev.kiromobile.design.Glyph
import dev.kiromobile.design.ToolbarIcon
import dev.kiromobile.design.R as DesignR
import org.json.JSONArray
import java.util.Locale
import io.noties.markwon.Markwon
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.core.MarkwonTheme

// Platform widgets keep the APK small. The screen depends on the connection interface,
// not on ACP framing, PC process management, or a particular push provider.
open class ChatActivity : Activity() {
    private val bg=KiroTheme.background
    private val panel=KiroTheme.surface
    private val purple=KiroTheme.accent
    private val ink=KiroTheme.foreground
    private val muted=KiroTheme.secondary
    private val green=KiroTheme.success
    private val markdown by lazy {
        Markwon.builder(this).usePlugin(object : AbstractMarkwonPlugin() {
            override fun configureTheme(builder: MarkwonTheme.Builder) {
                builder.linkColor(purple).codeTextColor(ink).codeBackgroundColor(KiroTheme.chrome).blockMargin(dp(18))
            }
        }).build()
    }
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var credits: TextView
    private lateinit var subtitle: TextView
    private lateinit var transcript: LinearLayout
    private lateinit var transcriptScroll: ScrollView
    private lateinit var permissionBox: LinearLayout
    private lateinit var message: EditText
    private lateinit var attachmentLabel: TextView
    private lateinit var agent: Button
    private lateinit var model: Button
    private lateinit var reasoning: Button
    private lateinit var autopilot: Switch
    private lateinit var contextCircle: View
    private var syncingAutopilot=false
    private lateinit var send: ImageButton
    private lateinit var attach: ImageButton
    private lateinit var stop: ImageButton
    private lateinit var banner: TextView
    private var state: Snapshot?=null
    private var screenPaired=false
    private var lastRender=""
    private var renderedIds=emptyList<String>()
    private val messageViews=mutableMapOf<String,TextView>()
    private data class ActivityViews(val header: TextView,val status: TextView,val body: TextView,var message: Message)
    private val activityViews=mutableMapOf<String,ActivityViews>()
    private val activityExpanded=mutableMapOf<String,Boolean>()
    private var renderedSessionId: String?=null
    private val attachments=mutableListOf<Attachment>()
    private val observer: (Snapshot?,String?)->Unit={snapshot,error-> if(screenPaired)render(snapshot,error) }
    private fun dp(n: Int)=(n*resources.displayMetrics.density).toInt()
    private fun box(color: Int=panel, radius: Int=8, border: Int?=null)=GradientDrawable().apply { setColor(color);cornerRadius=dp(radius).toFloat();border?.let { setStroke(dp(1),it) } }
    private fun column()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    private fun text(value: String,size: Float=14f,color: Int=ink,bold: Boolean=false)=TextView(this).apply {
        this.text=value;textSize=size;setTextColor(color);includeFontPadding=false;if(bold)typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(),1f)
    }
    private fun button(value: String, primary: Boolean=false, action: ()->Unit)=Button(this).apply {
        text=value;isAllCaps=false;textSize=13f;setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),intArrayOf(KiroTheme.muted,ink)))
        background=RippleDrawable(ColorStateList.valueOf(0x228e47ff),box(if(primary)KiroTheme.primary else panel,8,if(primary)null else KiroTheme.border),null)
        stateListAnimator=null;minHeight=dp(48);minimumHeight=dp(48);minimumWidth=0;minWidth=0;includeFontPadding=false
        setPadding(dp(12),dp(8),dp(12),dp(8));setOnClickListener { action() }
    }
    private fun iconButton(label: String,glyph: Glyph,primary: Boolean=false,action: ()->Unit)=ImageButton(this).apply {
        contentDescription=label;tooltipText=label;setImageDrawable(ToolbarIcon(glyph,ink));imageTintList=ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),intArrayOf(KiroTheme.muted,ink))
        background=RippleDrawable(ColorStateList.valueOf(0x338e47ff),box(if(primary)KiroTheme.primary else Color.TRANSPARENT,8),null)
        setPadding(dp(13),dp(13),dp(13),dp(13));setOnClickListener { action() };layoutParams=LinearLayout.LayoutParams(dp(48),dp(48))
    }
    private fun mark(size: Int)=ImageView(this).apply { setImageResource(DesignR.drawable.kiro_mark);imageTintList=ColorStateList.valueOf(purple);layoutParams=LinearLayout.LayoutParams(dp(size),dp(size));importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    private fun wordmark()=ImageView(this).apply { setImageResource(DesignR.drawable.kiro_wordmark);contentDescription="Kiro";scaleType=ImageView.ScaleType.FIT_START;layoutParams=LinearLayout.LayoutParams(dp(64),dp(24)) }
    private fun line(parent: LinearLayout) { parent.addView(View(this).apply { setBackgroundColor(KiroTheme.border) },LinearLayout.LayoutParams(-1,dp(1))) }
    private fun chip(value: String, action: ()->Unit)=button(value,action=action).apply {
        background=RippleDrawable(ColorStateList.valueOf(0x338e47ff),box(Color.TRANSPARENT,6),null);gravity=Gravity.START or Gravity.CENTER_VERTICAL
        setSingleLine();ellipsize=TextUtils.TruncateAt.END;setPadding(dp(8),0,dp(8),0)
    }
    private fun gap(parent: LinearLayout,size: Int=12) { parent.addView(View(this),LinearLayout.LayoutParams(1,dp(size))) }
    private fun row(vararg children: View)=LinearLayout(this).apply {
        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
        children.forEachIndexed { index,view -> addView(view,LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f).apply { if(index>0)marginStart=dp(8) }) }
    }
    private fun field(hintValue: String)=EditText(this).apply {
        hint=hintValue;setTextColor(ink);setHintTextColor(KiroTheme.muted);textSize=15f;background=box(border=KiroTheme.border);setPadding(dp(14),dp(14),dp(14),dp(14))
    }
    private fun page() {
        root=column().apply { setBackgroundColor(bg) }
        setContentView(root)
        root.setOnApplyWindowInsetsListener { view,insets ->
            view.setPadding(0,insets.systemWindowInsetTop,0,insets.systemWindowInsetBottom);insets
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Release builds protect private transcripts. Debug builds allow visual QA.
        if(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE==0)window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        savedInstanceState?.getString("draft")?.let { draft=it }
        if(PairingStore(this).read()==null)setup() else chat()
    }
    private var draft=""
    override fun onSaveInstanceState(outState: Bundle) { if(screenPaired)outState.putString("draft",message.text.toString());super.onSaveInstanceState(outState) }
    override fun onStart() { super.onStart();SessionHub.observe(observer) }
    override fun onStop() { SessionHub.remove(observer);super.onStop() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent);setIntent(intent);if(screenPaired)render(SessionHub.snapshot,SessionHub.error) }
    private fun setup() {
        screenPaired=false;page()
        val scroll=ScrollView(this).apply { isFillViewport=true }
        val content=column().apply { setPadding(dp(28),dp(24),dp(28),dp(28)) }
        scroll.addView(content);root.addView(scroll)
        content.addView(wordmark());gap(content,48)
        content.addView(mark(64));gap(content,24)
        content.addView(text("Let's build.",32f,ink,true));gap(content,12)
        content.addView(text("Your Kiro workspace, wherever you are.",16f,muted));gap(content,32)
        val form=column().apply { background=box(panel,12,KiroTheme.border);setPadding(dp(20),dp(20),dp(20),dp(20)) }
        form.addView(text("Connect to your PC",18f,ink,true));gap(form,10)
        form.addView(text("Connect Tailscale on your phone, then scan your PC's QR code. Your connection is encrypted directly to your PC.",13f,muted));gap(form,24)
        form.addView(button("Scan PC QR code",true) { scanPc() },LinearLayout.LayoutParams(-1,dp(48)));gap(form,12)
        val manual=column().apply { visibility=View.GONE }
        form.addView(button("Enter details manually") { manual.visibility=if(manual.visibility==View.GONE)View.VISIBLE else View.GONE });gap(form,12)
        form.addView(manual)
        manual.addView(text("Companion address",12f,muted));gap(manual,8)
        val endpoint=field("https://your-pc.tailnet.ts.net").apply { inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI;setSingleLine() }
        manual.addView(endpoint);gap(manual,18)
        manual.addView(text("Pairing key",12f,muted));gap(manual,8)
        val key=field("Pairing key from your PC").apply { inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD;setSingleLine() }
        manual.addView(key);gap(manual,24)
        manual.addView(button("Connect to Kiro",true) {
            try { PairingStore(this).save(endpoint.text.toString(),key.text.toString());chat() } catch(e: Exception) { toast(e.message ?: "Check the pairing details") }
        },LinearLayout.LayoutParams(-1,dp(48)))
        content.addView(form);gap(content,20)
        content.addView(text("Private connection. Your Kiro account stays on your PC.",12f,muted));gap(content,12)
        content.addView(button("Connection guide") {
            AlertDialog.Builder(this).setTitle("Connect your workspace").setMessage("1. Connect Tailscale on your PC and phone to the same tailnet.\n2. Sign in with kiro-cli login and run npm run pair on your PC.\n3. Open http://127.0.0.1:8787/pair on the PC.\n4. Tap Scan PC QR code here.\n\nThe QR code works once and expires after 5 minutes. No copied keys or addresses. HTTPS is pinned to your PC certificate and carried over Tailscale. Works on Wi-Fi or mobile data while the PC and Tailscale are connected.\n\nFor local Wi-Fi only, use npm run pair:wifi.").setPositiveButton("Got it",null).show()
        });gap(content,24)
        content.addView(text("Independent mobile companion",11f,KiroTheme.muted))
    }
    private fun scanPc() {
        IntentIntegrator(this).setDesiredBarcodeFormats(IntentIntegrator.QR_CODE).setPrompt("Scan the QR code shown on your PC").setBeepEnabled(false).setOrientationLocked(false).initiateScan()
    }
    private fun pairQr(contents: String) {
        val progress=AlertDialog.Builder(this).setTitle("Connecting to your PC").setMessage("Verifying the connection…").setCancelable(false).show()
        Thread {
            try {
                val pairing=QrPairing.redeem(contents)
                // Verify the authenticated connection before replacing an existing pairing.
                dev.kiromobile.connection.BridgeClient(pairing).snapshot()
                runOnUiThread {
                    progress.dismiss()
                    if(isFinishing||isDestroyed)return@runOnUiThread
                    stopService(Intent(this,ConnectionService::class.java));SessionHub.disconnect()
                    PairingStore(this).save(pairing.endpoint,pairing.token,pairing.certSha256);attachments.clear();chat()
                }
            } catch(e: Exception) { runOnUiThread { progress.dismiss();if(!isFinishing&&!isDestroyed)AlertDialog.Builder(this).setTitle("Could not connect").setMessage(e.message ?: "Refresh the QR code on your PC and try again.").setPositiveButton("OK",null).show() } }
        }.start()
    }
    private fun chat() {
        screenPaired=true;lastRender="";renderedIds=emptyList();messageViews.clear();activityViews.clear();page()
        val header=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(16),dp(4),dp(4),dp(4));setBackgroundColor(KiroTheme.chrome) }
        header.addView(wordmark());header.addView(View(this),LinearLayout.LayoutParams(0,1,1f))
        header.addView(iconButton("Sessions",Glyph.HISTORY) { sessions() })
        header.addView(iconButton("New chat",Glyph.PLUS) { createSession() })
        val menu=iconButton("More options",Glyph.MORE) {}
        menu.setOnClickListener {
            PopupMenu(this,menu).apply {
                getMenu().add("Refresh").setOnMenuItemClickListener { issue(command("refresh"));true }
                getMenu().add("Settings").setOnMenuItemClickListener { settings();true }
                show()
            }
        }
        header.addView(menu);root.addView(header);line(root)
        val workspace=column().apply { setPadding(dp(20),dp(14),dp(20),dp(12)) }
        subtitle=text("Choose a session to continue",14f,ink,true).apply { setSingleLine();ellipsize=TextUtils.TruncateAt.MIDDLE }
        workspace.addView(subtitle);gap(workspace,8)
        status=text("○ Connecting",11f,muted)
        credits=text("— credits remaining",11f,muted).apply { gravity=Gravity.END or Gravity.CENTER_VERTICAL;minHeight=dp(32);contentDescription="Account credits remaining" }
        workspace.addView(row(status,credits));root.addView(workspace);line(root)
        transcriptScroll=ScrollView(this).apply { isFillViewport=true;clipToPadding=false;setPadding(dp(20),dp(20),dp(20),dp(12)) }
        transcript=column();transcriptScroll.addView(transcript)
        root.addView(transcriptScroll,LinearLayout.LayoutParams(-1,0,1f))
        val footer=column().apply { setPadding(dp(12),0,dp(12),dp(10)) };root.addView(footer)
        permissionBox=column();footer.addView(permissionBox)
        banner=text("",11f,muted).apply { setPadding(dp(6),dp(8),dp(6),dp(8));maxLines=2;ellipsize=TextUtils.TruncateAt.END };footer.addView(banner)
        val composer=column().apply { background=box(panel,10,KiroTheme.border);setPadding(dp(6),dp(6),dp(6),dp(4)) }
        attachmentLabel=text("",12f,purple).apply { visibility=View.GONE;setPadding(dp(8),dp(6),dp(8),dp(6));setOnClickListener { attachments.clear();refreshAttachments() } }
        composer.addView(attachmentLabel)
        message=field("Ask Kiro to build, fix, or explore…").apply {
            background=null;minHeight=dp(76);maxLines=5;minLines=2;gravity=Gravity.TOP;setPadding(dp(10),dp(12),dp(10),dp(12))
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;setText(draft)
        }
        message.setOnFocusChangeListener { _,focused -> composer.background=box(panel,10,if(focused)purple else KiroTheme.border) }
        composer.addView(message)
        agent=chip("Default ▾") { chooseAgent() }
        reasoning=chip("Reasoning ▾") { choose("reasoning",state?.reasoning ?: emptyList()) }.apply { gravity=Gravity.END or Gravity.CENTER_VERTICAL }
        composer.addView(row(agent,reasoning))
        autopilot=Switch(this).apply {
            text="Autopilot";textSize=12f;setTextColor(ink);isChecked=true;minHeight=dp(48);setPadding(dp(8),0,dp(8),0)
            thumbTintList=ColorStateList.valueOf(ink)
            trackTintList=ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(purple,muted))
            setOnCheckedChangeListener { _,enabled -> if(!syncingAutopilot){
                syncingAutopilot=true;isChecked=state?.autopilot ?: true;syncingAutopilot=false
                isEnabled=false;issue(command("select","kind" to "autopilot","value" to if(enabled)"on" else "off"))
            } }
        }
        composer.addView(autopilot,LinearLayout.LayoutParams(-2,dp(48)));line(composer)
        val tools=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        model=chip("Model ▾") { choose("model",state?.models ?: emptyList()) }
        contextCircle=object : View(this) {
            private val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style=android.graphics.Paint.Style.STROKE;strokeWidth=dp(2).toFloat();strokeCap=android.graphics.Paint.Cap.ROUND }
            override fun onDraw(canvas: android.graphics.Canvas) {
                super.onDraw(canvas)
                val radius=dp(7).toFloat();val cx=width/2f;val cy=height/2f
                paint.color=muted;canvas.drawCircle(cx,cy,radius,paint)
                state?.contextUsagePercent?.let { percent ->
                    paint.color=if(percent>=90)KiroTheme.warning else green
                    canvas.drawArc(cx-radius,cy-radius,cx+radius,cy+radius,-90f,(percent.coerceIn(0.0,100.0)*3.6).toFloat(),false,paint)
                }
            }
        }.apply { isFocusable=true;setOnClickListener {
            val detail=state?.contextUsagePercent?.let { String.format(Locale.US,"%.1f%% of the context window used",it) } ?: "Kiro has not reported context usage for this session yet."
            AlertDialog.Builder(this@ChatActivity).setTitle("Context window").setMessage(detail).setPositiveButton("OK",null).show()
        } }
        tools.addView(contextCircle,LinearLayout.LayoutParams(dp(40),dp(48)))
        tools.addView(model,LinearLayout.LayoutParams(0,dp(48),1f))
        attach=iconButton("Attach media",Glyph.ATTACH) { attachMedia() };tools.addView(attach)
        stop=iconButton("Stop",Glyph.STOP) { issue(command("cancel")) };tools.addView(stop)
        send=iconButton("Send",Glyph.SEND,true) { sendMessage() };tools.addView(send)
        composer.addView(tools);footer.addView(composer)
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),90)
        startForegroundService(Intent(this,ConnectionService::class.java));SessionHub.connect(this)
        render(SessionHub.snapshot,SessionHub.error)
    }
    private fun render(s: Snapshot?,error: String?) {
        state=s
        if(renderedSessionId!=s?.selectedId){renderedSessionId=s?.selectedId;activityExpanded.clear();lastRender="";renderedIds=emptyList();transcript.removeAllViews();messageViews.clear();activityViews.clear()}
        status.text=when { error!=null -> "○ Reconnecting";s?.status=="online" -> if(s.demo) "● Demo connection" else "● PC connected";else -> "○ Connecting" }
        status.setTextColor(if(s?.status=="online"&&error==null)green else muted)
        credits.text=s?.credits?.let { String.format(Locale.US,"%,.2f credits remaining",it) } ?: "Credits remaining unavailable"
        contextCircle.contentDescription=s?.contextUsagePercent?.let { String.format(Locale.US,"Context window: %.1f%% used",it) } ?: "Context usage unavailable"
        contextCircle.invalidate()
        syncingAutopilot=true;autopilot.isChecked=s?.autopilot ?: true;syncingAutopilot=false
        credits.setOnClickListener { AlertDialog.Builder(this).setTitle("Account credits").setMessage(s?.usageDescription ?: "Connect to load usage").setPositiveButton("OK",null).show() }
        subtitle.text=s?.sessions?.find { it.id==s.selectedId }?.title ?: s?.selectedCwd?.substringAfterLast('\\')?.substringAfterLast('/') ?: "New session"
        subtitle.contentDescription=s?.selectedCwd ?: "New session"
        val presetLabels=mapOf("default" to "Default","spec" to "Spec","quick-spec" to "Quick spec","bug-fix" to "Bug fix","plan" to "Plan")
        agent.text=(presetLabels[s?.preset] ?: "Default")+" ▾"
        model.text=(s?.models?.find { it.id==s.currentModel }?.name ?: "Model")+" ▾"
        reasoning.text=(s?.reasoning?.find { it.id==s.currentReasoning }?.name ?: "Reasoning")+" ▾"
        val ready=s?.selectedId!=null && s.status=="online" && error==null
        agent.isEnabled=ready&&s?.busy!=true;model.isEnabled=agent.isEnabled;reasoning.isEnabled=agent.isEnabled
        autopilot.isEnabled=ready&&s?.busy!=true&&s?.permissions.isNullOrEmpty()&&s?.autopilotSupported==true&&s.autopilot!=null
        attach.isEnabled=ready&&s?.imageSupported==true&&s.busy!=true
        send.isEnabled=ready&&s?.busy!=true&&s?.autopilot!=null
        send.visibility=if(s?.busy==true)View.GONE else View.VISIBLE
        stop.visibility=if(s?.busy==true)View.VISIBLE else View.GONE
        model.contentDescription="Model: ${model.text}";reasoning.contentDescription="Reasoning: ${reasoning.text}";agent.contentDescription="Agent: ${agent.text}"
        banner.text=error ?: s?.error ?: s?.pushError ?: when {
            s?.permissions?.isNotEmpty()==true -> "Permission needed · your agent is waiting"
            s?.busy==true -> s.activity ?: "Kiro is working on your PC…"
            s?.demo==true -> "Demo data · no account connected"
            s==null||s.status!="online" -> "Connecting to your PC…"
            s?.selectedId!=null && !s.presetNative && s.preset!="default" -> "${presetLabels[s.preset]} uses prompt guidance on this engine"
            PushSetup.configured&&s?.pushConfigured==true -> "Push notifications enabled"
            else -> ""
        }
        banner.visibility=if(banner.text.isNullOrBlank())View.GONE else View.VISIBLE
        val signature=s?.messages?.joinToString("|") { it.toString() } ?: "empty"
        if(signature!=lastRender) {
            lastRender=signature
            val nearBottom=transcriptScroll.getChildAt(0)?.let { it.height-transcriptScroll.height-transcriptScroll.scrollY<dp(100) } ?: true
            val messages=s?.messages ?: emptyList()
            val ids=messages.map { it.id }
            val appendOnly=renderedIds.isNotEmpty()&&ids.take(renderedIds.size)==renderedIds
            if(!appendOnly){transcript.removeAllViews();messageViews.clear();activityViews.clear();renderedIds=emptyList()}
            activityExpanded.keys.retainAll(ids.toSet())
            if(s?.messages.isNullOrEmpty()) {
                val welcome=column().apply { gravity=Gravity.CENTER;setPadding(dp(16),dp(40),dp(16),dp(30)) }
                welcome.addView(mark(48));gap(welcome,20)
                welcome.addView(text("Let's build",26f,ink,true));gap(welcome,12)
                welcome.addView(text("What do you want to work on?",14f,muted).apply { gravity=Gravity.CENTER });gap(welcome,24)
                welcome.addView(button("Choose a session") { sessions() });gap(welcome,10)
                welcome.addView(button("Start a new chat") { createSession() })
                transcript.addView(welcome)
            } else messages.forEach { m ->
                if(m.role=="thinking"||m.role=="tool") { renderActivity(m);return@forEach }
                if(m.role=="summary") {
                    val value=listOfNotNull(m.creditsUsed?.let { String.format(Locale.US,"%.3f credits used",it) },m.elapsedMs?.let { String.format(Locale.US,"%.1fs elapsed",it/1000) }).joinToString(" · ")
                    messageViews[m.id]?.let { it.text=value;return@forEach }
                    val summary=text(value,11f,muted).apply { setPadding(0,0,0,dp(16));setTextIsSelectable(true) }
                    messageViews[m.id]=summary;transcript.addView(summary);return@forEach
                }
                messageViews[m.id]?.let { view -> if(view.tag!=m.text){markdown.setMarkdown(view,m.text);view.tag=m.text};return@forEach }
                val user=m.role=="user"
                val card=column().apply {
                    if(user)background=box(panel,8)
                    setPadding(if(user)dp(14) else 0,dp(12),if(user)dp(14) else 0,dp(14))
                }
                if(!user) {
                    val author=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
                    author.addView(mark(20));author.addView(text("Kiro",13f,ink,true),LinearLayout.LayoutParams(-2,-2).apply { marginStart=dp(8) });card.addView(author);gap(card,12)
                }
                val body=text("",15f).apply { setTextIsSelectable(true);setLineSpacing(dp(4).toFloat(),1f);markdown.setMarkdown(this,m.text);tag=m.text };messageViews[m.id]=body
                card.addView(body)
                transcript.addView(card,LinearLayout.LayoutParams(-1,-2).apply { if(user)marginStart=dp(24) });gap(transcript,18)
            }
            renderedIds=ids
            if(nearBottom)transcriptScroll.post { transcriptScroll.fullScroll(View.FOCUS_DOWN) }
        }
        permissionBox.removeAllViews()
        s?.permissions?.firstOrNull()?.let { p ->
            val card=column().apply { background=box(panel,8,KiroTheme.warning);setPadding(dp(14),dp(12),dp(14),dp(12)) }
            card.addView(text("Permission required",12f,KiroTheme.warning,true));gap(card,5)
            card.addView(text(p.title,14f,ink,true));gap(card,8)
            card.addView(button("Review request") { review(p) });permissionBox.addView(card)
        }
    }
    private fun renderActivity(m: Message) {
        val existing=activityViews[m.id]
        if(existing!=null){existing.message=m;updateActivity(existing);return}
        val card=column().apply { background=box(panel,8,KiroTheme.border);setPadding(dp(12),dp(4),dp(12),dp(8)) }
        val header=text("",13f,ink,true).apply { minHeight=dp(44);gravity=Gravity.CENTER_VERTICAL;setSingleLine();ellipsize=TextUtils.TruncateAt.END;isFocusable=true }
        val status=text("",11f,muted).apply { setPadding(dp(16),0,0,dp(4)) }
        val body=text("",13f,muted).apply { setTextIsSelectable(true);setPadding(dp(4),dp(8),dp(4),dp(8));if(m.role=="tool")typeface=Typeface.MONOSPACE }
        val views=ActivityViews(header,status,body,m);activityViews[m.id]=views
        header.setOnClickListener { activityExpanded[m.id]=!isActivityExpanded(views.message);updateActivity(views) }
        card.addView(header);card.addView(status);card.addView(body)
        transcript.addView(card,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(10) })
        updateActivity(views)
    }
    private fun isActivityExpanded(m: Message)=activityExpanded[m.id] ?: (m.role=="thinking"&&m.streaming)
    private fun updateActivity(views: ActivityViews) {
        val m=views.message;val thinking=m.role=="thinking";val expanded=isActivityExpanded(m)
        val title=if(thinking)if(m.streaming)"Thinking…" else "Thinking" else m.tool?.title ?: "Tool call"
        views.header.text=(if(expanded)"▾ " else "▸ ")+title
        views.header.contentDescription=(if(expanded)"Collapse " else "Expand ")+title
        views.header.setTextColor(if(thinking)purple else ink)
        views.status.text=if(thinking)if(m.streaming)"Streaming" else "Thoughts from Kiro" else m.tool?.status ?: "Status unavailable"
        views.status.setTextColor(when(m.tool?.status){"Failed","Denied","Cancelled","Interrupted","Needs approval" -> KiroTheme.warning;"Completed" -> green;else -> muted})
        views.body.visibility=if(expanded)View.VISIBLE else View.GONE
        val body=if(thinking)m.text else m.tool?.details?.takeIf { it.isNotBlank() } ?: "No details reported yet."
        // Hidden tool output is rendered only when expanded; retain the row across streaming updates.
        if(expanded&&views.body.tag!=body){if(thinking)markdown.setMarkdown(views.body,body) else views.body.text=body;views.body.tag=body}
    }
    private fun issue(c: org.json.JSONObject, done: (() -> Unit)?=null) {
        SessionHub.send(c) { error -> if(error!=null){toast(error);render(SessionHub.snapshot,SessionHub.error)} else done?.invoke() }
    }
    private fun sessions() {
        val list=state?.sessions ?: emptyList()
        if(list.isEmpty()){toast("No sessions yet. Refresh or start a new chat.");return}
        val search=field("Search sessions")
        val view=ListView(this);val area=column();area.setPadding(dp(16),dp(8),dp(16),0);area.addView(search);area.addView(view,LinearLayout.LayoutParams(-1,dp(340)))
        var filtered=list
        fun update(query: String) { filtered=list.filter { (it.title+it.cwd).contains(query,true) };view.adapter=ArrayAdapter(this,android.R.layout.simple_list_item_1,filtered.map { it.title+"\n"+it.cwd }) }
        update("")
        search.addTextChangedListener(object: android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?,start: Int,count: Int,after: Int){}
            override fun onTextChanged(s: CharSequence?,start: Int,before: Int,count: Int){update(s.toString())}
            override fun afterTextChanged(s: android.text.Editable?){}
        })
        val dialog=AlertDialog.Builder(this).setTitle("Your sessions").setView(area).setNegativeButton("Close",null).create()
        view.setOnItemClickListener { _,_,position,_ ->
            val session=filtered[position];dialog.dismiss()
            if(session.id==state?.selectedId)return@setOnItemClickListener
            AlertDialog.Builder(this).setTitle("Continue this conversation?").setMessage("Finish or stop the active turn on your desktop first. This restores the saved conversation on the companion. Keep one client in control to avoid conflicting changes.").setNegativeButton("Cancel",null).setPositiveButton("Continue on phone") { _,_ -> issue(command("load","sessionId" to session.id,"handoffConfirmed" to true)) }.show()
        };dialog.show()
    }
    private fun createSession() {
        val cwd=field("Absolute workspace path on PC").apply { setText(state?.selectedCwd ?: "");setSingleLine() }
        AlertDialog.Builder(this).setTitle("New conversation").setMessage("The folder must be allowed in your PC companion configuration.").setView(cwd).setNegativeButton("Cancel",null).setPositiveButton("Create") { _,_ -> issue(command("create","cwd" to cwd.text.toString())) }.show()
    }
    private fun choose(kind: String, choices: List<Choice>) {
        if(choices.isEmpty()){toast("Kiro has not advertised ${if(kind=="model")"models" else "reasoning options"} for this session yet.");return}
        val current=if(kind=="model")state?.currentModel else state?.currentReasoning
        AlertDialog.Builder(this).setTitle(if(kind=="model")"Language model" else "Reasoning effort").setSingleChoiceItems(choices.map { it.name }.toTypedArray(),choices.indexOfFirst { it.id==current }) { dialog,index ->
            issue(command("select","kind" to kind,"value" to choices[index].id));dialog.dismiss()
        }.setNegativeButton("Cancel",null).show()
    }
    private fun chooseAgent() {
        val choices=listOf(Choice("default","Default"),Choice("spec","Spec"),Choice("quick-spec","Quick spec"),Choice("bug-fix","Bug fix"),Choice("plan","Plan"))
        AlertDialog.Builder(this).setTitle("Agent").setSingleChoiceItems(choices.map { it.name }.toTypedArray(),choices.indexOfFirst { it.id==state?.preset }) { dialog,index ->
            issue(command("select","kind" to "agent","value" to choices[index].id));dialog.dismiss()
        }.setNegativeButton("Cancel",null).show()
    }
    private fun review(p: Permission) {
        val scroll=ScrollView(this);val details=text(p.title+"\n\n"+p.details,13f).apply { setPadding(dp(20),dp(12),dp(20),dp(12));setTextIsSelectable(true) };scroll.addView(details)
        AlertDialog.Builder(this).setTitle("Review permission").setView(scroll).setNegativeButton("Close",null).setPositiveButton("Choose response") { _,_ ->
            AlertDialog.Builder(this).setTitle("Permission response").setItems((p.options.map { it.name }+"Cancel request").toTypedArray()) { _,index ->
                issue(command("permission","permissionId" to p.id,"optionId" to p.options.getOrNull(index)?.id))
            }.setNegativeButton("Back",null).show()
        }.show()
    }
    private fun sendMessage() {
        val value=message.text.toString()
        if(value.isBlank()&&attachments.isEmpty())return
        send.isEnabled=false
        val media=JSONArray().apply { attachments.forEach { put(it.json()) } }
        issue(command("prompt","text" to value,"attachments" to media)) { message.setText("");attachments.clear();refreshAttachments() }
    }
    private fun attachMedia() {
        if(attachments.size>=4){toast("Attach at most four images");return}
        val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="image/*";addCategory(Intent.CATEGORY_OPENABLE);putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("image/jpeg","image/png","image/webp","image/gif")) }
        startActivityForResult(intent,70)
    }
    @Deprecated("Platform activity result API keeps this module dependency-light")
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        val scanned=IntentIntegrator.parseActivityResult(requestCode,resultCode,data)
        if(scanned!=null) { scanned.contents?.let { pairQr(it) };return }
        if(requestCode!=70||resultCode!=RESULT_OK)return
        val uri=data?.data ?: return
        Thread {
            try {
                val mime=contentResolver.getType(uri) ?: throw IllegalArgumentException("Cannot identify the image")
                require(mime in listOf("image/jpeg","image/png","image/webp","image/gif")) { "Choose JPEG, PNG, WebP or GIF" }
                val bytes=contentResolver.openInputStream(uri)?.use { input ->
                    val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                    while(output.size()<=8*1024*1024) { val count=input.read(buffer);if(count<0)break;output.write(buffer,0,count) };output.toByteArray()
                } ?: throw IllegalArgumentException("Cannot read image")
                require(bytes.isNotEmpty()&&bytes.size<=8*1024*1024) { "Each image must be under 8 MB" }
                val name=fileName(uri)
                runOnUiThread { if(attachments.size<4)attachments.add(Attachment(name,mime,Base64.encodeToString(bytes,Base64.NO_WRAP)));refreshAttachments() }
            } catch(e: Exception) { runOnUiThread { toast(e.message ?: "Cannot attach image") } }
        }.start()
    }
    private fun fileName(uri: Uri): String = contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst())it.getString(0) else "Image" } ?: "Image"
    private fun refreshAttachments() { attachmentLabel.visibility=if(attachments.isEmpty())View.GONE else View.VISIBLE;attachmentLabel.text=if(attachments.isEmpty())"" else attachments.joinToString(" · ") { it.name }+"  × clear" }
    private fun settings() {
        val pairing=PairingStore(this).read()
        val firebase=if(PushSetup.configured&&state?.pushConfigured==true)"Firebase push configured" else "Firebase push is not configured. Permission notifications use the active private connection. Android may suspend delivery when the app is force-stopped or the phone sleeps."
        AlertDialog.Builder(this).setTitle("Connection & notifications").setMessage("${pairing?.endpoint}\n\n$firebase\n\nKiro authentication stays on your PC. This companion supports one controlling phone session at a time.\n\nIndependent mobile companion · Kiro Dark UI")
            .setNeutralButton("Scan PC QR code") { _,_ -> scanPc() }
            .setNeutralButton("Notification settings") { _,_ -> startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,packageName)) }
            .setNegativeButton("Close",null).setPositiveButton("Disconnect") { _,_ ->
                stopService(Intent(this,ConnectionService::class.java));SessionHub.disconnect();PairingStore(this).clear();attachments.clear();setup()
            }.show()
    }
    private fun toast(value: String) { Toast.makeText(this,value,Toast.LENGTH_LONG).show() }
}
