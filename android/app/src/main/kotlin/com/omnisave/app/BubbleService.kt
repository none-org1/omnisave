package com.omnisave.app

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.media.MediaRecorder
import android.os.*
import android.provider.Settings
import android.text.TextUtils
import android.view.*
import android.widget.*
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlin.math.abs

/* The floating bubble: foreground service drawing a 28dp circle over any app.
   Tap → save sheet (clipboard-probed URL auto-captured, reference text, ≤30s memo).
   Success = green tick drawn on the bubble, then it fades. Budget: no loops, lazy sheet. */
class BubbleService : Service() {

    private lateinit var wm: WindowManager
    private var bubble: BubbleView? = null
    private var sheet: LinearLayout? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var recorder: MediaRecorder? = null
    private var memoFile: File? = null
    private var memoTimer: CountDownTimer? = null
    private var status: TextView? = null

    companion object {
        const val EXTRA_TOGGLE = "toggle"
        var active = false; private set
        fun intent(ctx: Context, toggle: Boolean) = Intent(ctx, BubbleService::class.java).putExtra(EXTRA_TOGGLE, toggle)
    }

    private val NOTIF_ID = 41

    override fun onBind(i: Intent?) = null

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        if (i?.getBooleanExtra(EXTRA_TOGGLE, false) == true && active) { stop(); return START_NOT_STICKY }
        if (!Settings.canDrawOverlays(this)) { stop(); return START_NOT_STICKY }
        if (!active) {
            val ch = NotificationChannel("bubble", "Omnisave bubble", NotificationManager.IMPORTANCE_MIN)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
            val n = Notification.Builder(this, "bubble")
                .setSmallIcon(android.R.drawable.ic_menu_save)
                .setContentTitle("Omnisave bubble is on")
                .setContentText("Tap the blue bubble on any screen to save.")
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= 34)
                ServiceCompat.startForeground(this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIF_ID, n)
            wm = getSystemService(WINDOW_SERVICE) as WindowManager
            showBubble()
            active = true
        }
        return START_STICKY
    }

    private fun overlayParams(x: Int, y: Int, w: Int, h: Int, focusable: Boolean): WindowManager.LayoutParams =
        @SuppressLint("WrongConstant") WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; this.x = x; this.y = y }

    /* ---------- bubble ---------- */
    private fun showBubble() {
        val v = BubbleView(this)
        val p = overlayParams(px(300), px(600), px(28), px(28), false)
        var down = null as Pair<Float, Float>?
        var moved = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { down = e.rawX to e.rawY; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    if (down != null && (abs(e.rawX - down!!.first) > px(6) || abs(e.rawY - down!!.second) > px(6))) {
                        moved = true
                        p.x = (e.rawX - px(14)).toInt().coerceIn(0, resources.displayMetrics.widthPixels - px(28))
                        p.y = (e.rawY - px(14)).toInt().coerceIn(0, resources.displayMetrics.heightPixels - px(28))
                        wm.updateViewLayout(v, p)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!moved) toggleSheet(); true }
                else -> false
            }
        }
        wm.addView(v, p)
        bubble = v
    }

    /* ---------- save sheet ---------- */
    private fun toggleSheet() {
        if (sheet != null) { closeSheet(); return }
        val dp = this
        val clip = clipUrl() // auto-capture: clipboard link (opt-out toggle lives in Settings)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), px(14), px(14), px(12))
            background = round(Color.argb(225, 250, 250, 252), px(16)) // frosted substitute: translucent light glass
            elevation = px(14).toFloat()
        }
        val title = TextView(dp).apply {
            text = clip?.let { c -> "Saving · " + Addr.shortHost(c.url) } ?: "Copy a link first"
            textSize = 12f; setTextColor(Color.parseColor("#8E8E93")); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        val input = EditText(dp).apply { hint = "Reference — why save this?"; textSize = 14f; setSingleLine(false); setMinLines(1); maxLines = 3 }
        val row = LinearLayout(dp).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val mic = Button(dp).apply { text = "Memo"; textSize = 12f; isAllCaps = false }
        val save = Button(dp).apply { text = "Save"; textSize = 14f; isAllCaps = false }
        row.addView(mic, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.32f))
        row.addView(save, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.68f).also { it.marginStart = px(8) })
        val stat = TextView(dp).apply { textSize = 11f; setTextColor(Color.parseColor("#8E8E93")) }
        col.addView(title); col.addView(View(dp).also { it.minimumHeight = px(8) })
        col.addView(input)
        col.addView(View(dp).also { it.minimumHeight = px(10) })
        col.addView(row); col.addView(stat)

        val p = overlayParams(px(60), px(640), (resources.displayMetrics.widthPixels - px(40)), LinearLayout.LayoutParams.WRAP_CONTENT, true).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }

        mic.setOnClickListener { toggleMic(mic, stat) }
        save.setOnClickListener {
            if (recorder != null) stopMic()
            val c = clipUrl()
            if (c == null) { stat.text = "No link found in clipboard."; stat.setTextColor(Color.parseColor("#FF3B30")); return@setOnClickListener }
            save.isEnabled = false; stat.setTextColor(Color.parseColor("#8E8E93")); stat.text = "Saving…"
            scope.launch {
                try {
                    val (plat, type, isShort) = Repo.parse(c.url)
                    val item = Repo.save(
                        this@BubbleService, AppDatabase.get(this@BubbleService).dao(),
                        plat, type, c.url, c.title,
                        input.text.toString().trim(), memoFile?.absolutePath, c.videoId, isShort, c.videoT
                    )
                    runCatching { memoFile?.renameTo(File(cacheDir, "memo-" + item.id + ".m4a")) }
                    tick(); closeSheet()
                } catch (e: Exception) {
                    stat.text = "✕ " + (e.message ?: "failed").take(90); stat.setTextColor(Color.parseColor("#FF3B30")); save.isEnabled = true
                }
            }
        }
        wm.addView(col, p)
        sheet = col
        input.requestFocus()
    }

    data class Clip(val url: String, val title: String, val videoId: String?, val videoT: Int?)

    /** Auto-capture: primary clipboard link → platform parse. No UsageAccess, no polling — read only on tap. */
    private fun clipUrl(): Clip? {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim() ?: return null
        val url = Regex("https?://\\S+").find(text)?.value
            ?: (if (Regex("^[\\w-]{11}$").matches(text)) "https://youtu.be/$text" else null)
            ?: return null
        val parsed = Repo.canonicalize(url)
        val vid = if (parsed.contains("youtu")) {
            val m = Regex("[?&]v=([\\w-]{6,})|youtu\\.be/([\\w-]{6,})").find(parsed)?.groupValues
            m?.let { it[1].ifBlank { it[2] } }
        } else "/shorts/([\\w-]{6,})".toRegex().find(parsed)?.groupValues?.get(1)
        return Clip(parsed, text.take(120), vid, null)
    }

    /* ---------- memo (≤30s, compressed mono AAC at record time) ---------- */
    private fun toggleMic(mic: Button, stat: TextView) {
        if (recorder != null) { stopMic(); stat.text = "Memo ready — saved with the item"; return }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            stat.text = "Mic permission off — text only"; stat.setTextColor(Color.parseColor("#FF3B30")); return
        }
        try {
            val f = File(cacheDir, "memo-pending.m4a")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(24_000); r.setAudioSamplingRate(16_000); r.setAudioChannels(1)
            r.setOutputFile(f.absolutePath)
            r.setMaxDuration(30_000)
            r.prepare(); r.start()
            recorder = r; memoFile = f
            stat.text = "Rec 0s / 30s"; mic.text = "Stop"
            memoTimer = object : CountDownTimer(30_000, 500) {
                override fun onTick(ms: Long) { stat.text = "Rec " + ((30_000 - ms) / 1000) + "s / 30s" }
                override fun onFinish() { stopMic(); stat.text = "Memo ready (30s cap)" }
            }.start()
        } catch (e: Exception) {
            stat.text = "Recording failed — text only"; stat.setTextColor(Color.parseColor("#FF3B30")); stopMic()
        }
    }
    private fun stopMic() {
        try { recorder?.stop() } catch (_: Exception) {}
        recorder?.release(); recorder = null; memoTimer?.cancel(); memoTimer = null
    }

    /* ---------- success tick on the bubble ---------- */
    private fun tick() {
        bubble?.ok = true; bubble?.invalidate()
        scope.launch { delay(1100); bubble?.ok = false; bubble?.invalidate() }
    }

    private fun closeSheet() { sheet?.let { wm.removeView(it) }; sheet = null; stopMic(); memoFile?.delete() }

    private fun stop() {
        closeSheet()
        bubble?.let { wm.removeView(it) }
        bubble = null
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        active = false
    }

    override fun onDestroy() { stop(); super.onDestroy() }
    override fun onTaskRemoved(root: Intent?) { stop(); super.onTaskRemoved(root) }

    private fun px(d: Int) = (d * resources.displayMetrics.density).toInt()
    private fun round(color: Int, r: Int): GradientDrawable = GradientDrawable().also { it.setColor(color); it.cornerRadius = r.toFloat() }

    /* ---------- drawn bubble / tick (vector-quality, no emoji) ---------- */
    inner class BubbleView(c: Context) : View(c) {
        var ok = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            val s = width.toFloat()
            paint.color = if (ok) Color.parseColor("#34C759") else Color.argb(235, 10, 132, 255)
            canvas.drawCircle(s / 2, s / 2, s / 2 - 1, paint)
            paint.color = Color.WHITE
            if (ok) { // checkmark
                paint.style = Paint.Style.STROKE; paint.strokeWidth = s * 0.11f; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
                val p = Path().apply {
                    moveTo(s * 0.26f, s * 0.52f); lineTo(s * 0.44f, s * 0.70f); lineTo(s * 0.76f, s * 0.34f)
                }
                canvas.drawPath(p, paint); paint.style = Paint.Style.FILL
            } else { // bookmark
                val p = Path().apply {
                    moveTo(s * 0.30f, s * 0.16f); lineTo(s * 0.70f, s * 0.16f)
                    lineTo(s * 0.70f, s * 0.78f); lineTo(s * 0.50f, s * 0.64f); lineTo(s * 0.30f, s * 0.78f); close()
                }
                canvas.drawPath(p, paint)
            }
        }
    }
}

/* tiny helpers used by the sheet */ object Addr { fun shortHost(u: String) = try { java.net.URI(u).host.removePrefix("www.") } catch (e: Exception) { u } }
