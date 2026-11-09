package com.example.autoclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar

class ClickService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ClickService? = null
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager

    private var bar: View? = null
    private var target: View? = null
    private var targetLp: WindowManager.LayoutParams? = null
    private var playBtn: TextView? = null
    private var statusTv: TextView? = null

    @Volatile private var running = false
    @Volatile private var runId = 0
    private var worker: Thread? = null

    private val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        stop()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stop()
        hideOverlay()
        instance = null
        return super.onUnbind(intent)
    }

    // ---------- Overlay ----------

    private fun tv(txt: String, size: Float = 22f, color: Int = Color.WHITE) = TextView(this).apply {
        text = txt
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(8), dp(12), dp(8))
    }

    private fun attachDrag(handle: View, v: View, lp: WindowManager.LayoutParams) {
        var sx = 0f
        var sy = 0f
        var ox = 0
        var oy = 0
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = ox + (e.rawX - sx).toInt()
                    lp.y = oy + (e.rawY - sy).toInt()
                    wm.updateViewLayout(v, lp)
                }
            }
            true
        }
    }

    fun showOverlay() {
        if (bar != null) return
        val type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        val dm = resources.displayMetrics

        // target marker
        val size = dp(52)
        val t = tv("✛", 24f).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x88FF1744.toInt())
                setStroke(dp(2), Color.WHITE)
            }
        }
        val tlp = WindowManager.LayoutParams(size, size, type, baseFlags, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dm.widthPixels / 2 - size / 2
            y = dm.heightPixels / 2 - size / 2
        }
        attachDrag(t, t, tlp)
        wm.addView(t, tlp)
        target = t
        targetLp = tlp

        // control bar
        val handle = tv("⠿", 22f, 0xFF9999AA.toInt())
        val play = tv("▶", 22f, 0xFF4CAF50.toInt())
        val status = tv("آماده", 14f, 0xFFCCCCDD.toInt())
        val close = tv("✕", 20f, 0xFFFF5252.toInt())
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            keepScreenOn = true
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(0xEE181820.toInt())
                setStroke(dp(1), 0xFF444455.toInt())
            }
            addView(handle)
            addView(play)
            addView(status)
            addView(close)
        }
        val blp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type, baseFlags, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(90)
        }
        attachDrag(handle, b, blp)
        play.setOnClickListener { if (running) stop() else start() }
        close.setOnClickListener { stop(); hideOverlay() }
        wm.addView(b, blp)
        bar = b
        playBtn = play
        statusTv = status
    }

    fun hideOverlay() {
        try { bar?.let { wm.removeViewImmediate(it) } } catch (_: Exception) {}
        try { target?.let { wm.removeViewImmediate(it) } } catch (_: Exception) {}
        bar = null
        target = null
        targetLp = null
        playBtn = null
        statusTv = null
    }

    private fun setTargetTouchable(touchable: Boolean) {
        val t = target ?: return
        val lp = targetLp ?: return
        lp.flags = if (touchable) baseFlags else baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try { wm.updateViewLayout(t, lp) } catch (_: Exception) {}
    }

    private fun status(s: String) {
        ui.post { statusTv?.text = s }
    }

    private fun refreshPlay() {
        playBtn?.apply {
            text = if (running) "■" else "▶"
            setTextColor(if (running) 0xFFFF5252.toInt() else 0xFF4CAF50.toInt())
        }
    }

    // ---------- Run control ----------

    private fun start() {
        if (running) return
        val t = target ?: return
        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        val interval = p.getLong("interval", 100L).coerceAtLeast(1L)
        val count = p.getInt("count", 0)
        val pre = p.getLong("pre", 0L)
        val useNtp = p.getBoolean("ntp_use", false)
        val off0 = if (useNtp) p.getLong("ntp_off", 0L) else 0L

        // fireAt: زمان هدف بر اساس «زمان واقعی» (ساعت گوشی + اختلاف NTP)
        var fireAt = 0L
        if (p.getBoolean("sched", false)) {
            val nowTrue = System.currentTimeMillis() + off0
            val c = Calendar.getInstance().apply {
                timeInMillis = nowTrue
                set(Calendar.HOUR_OF_DAY, p.getInt("h", 0))
                set(Calendar.MINUTE, p.getInt("m", 0))
                set(Calendar.SECOND, p.getInt("s", 0))
                set(Calendar.MILLISECOND, p.getInt("ms", 0))
            }
            if (c.timeInMillis <= nowTrue) c.add(Calendar.DAY_OF_YEAR, 1)
            fireAt = c.timeInMillis - pre
        }

        val loc = IntArray(2)
        t.getLocationOnScreen(loc)
        val x = loc[0] + t.width / 2f
        val y = loc[1] + t.height / 2f

        setTargetTouchable(false)
        running = true
        val id = ++runId
        refreshPlay()
        worker = Thread { loop(id, x, y, interval, count, fireAt, useNtp, off0) }.apply {
            isDaemon = true
            start()
        }
    }

    private fun stop(msg: String = "آماده") {
        running = false
        runId++
        worker?.interrupt()
        worker = null
        setTargetTouchable(true)
        refreshPlay()
        statusTv?.text = msg
    }

    private fun alive(id: Int) = running && runId == id

    private fun loop(
        id: Int, x: Float, y: Float, interval: Long, count: Int,
        fireAt: Long, useNtp: Boolean, off0: Long
    ) {
        try {
            var off = off0
            if (fireAt > 0) {
                // اگر هنوز بیش از ۱۰ ثانیه مانده، یک بار ساعت را دوباره با NTP هماهنگ کن
                if (useNtp && fireAt - (System.currentTimeMillis() + off) > 10_000L) {
                    status("همگام‌سازی ساعت…")
                    val n = Ntp.sync(3)
                    if (n != null && alive(id)) {
                        off = n
                        getSharedPreferences("cfg", MODE_PRIVATE).edit()
                            .putLong("ntp_off", n)
                            .putLong("ntp_time", System.currentTimeMillis())
                            .apply()
                    }
                }
                while (alive(id)) {
                    val rem = fireAt - (System.currentTimeMillis() + off)
                    if (rem <= 0) break
                    if (rem > 60) {
                        status("منتظر… ${rem / 1000} ثانیه")
                        Thread.sleep(minOf(rem - 50, 250L))
                    } else {
                        Thread.yield()
                    }
                }
            }
            val t0 = System.nanoTime()
            var i = 0
            while (alive(id) && (count == 0 || i < count)) {
                val due = t0 + i * interval * 1_000_000L
                while (alive(id)) {
                    val d = due - System.nanoTime()
                    if (d <= 0) break
                    if (d > 2_000_000L) Thread.sleep(d / 1_000_000L - 1) else Thread.yield()
                }
                if (!alive(id)) break
                tap(x, y)
                i++
                if (i % 5 == 0 || i == count) status("$i / ${if (count == 0) "∞" else count.toString()}")
            }
        } catch (_: InterruptedException) {
        }
        ui.post { if (runId == id) stop("پایان ✓") }
    }

    private fun tap(x: Float, y: Float) {
        try {
            val path = Path().apply { moveTo(x, y) }
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 1))
                .build()
            dispatchGesture(g, null, null)
        } catch (_: Exception) {
        }
    }
}
