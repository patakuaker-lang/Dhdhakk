package com.elixir.tracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.nio.ByteBuffer

class CaptureService : Service() {
    // ===== AJUSTES barra de elixir =====
    private val stripTop = 0.94f
    private val stripBottom = 0.985f
    private val xFrom = 0.12f
    private val xTo = 0.95f
    private val onThreshold = 0.20f
    private val offThreshold = 0.04f
    // ===== Zona donde se buscan cartas (parte central/superior del campo) =====
    private val detectTop = 0.08f
    private val detectBottom = 0.72f
    // ================================================================

    private val h = Handler(Looper.getMainLooper())
    private var proj: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var wm: WindowManager? = null
    private var root: View? = null
    private var tv: TextView? = null
    private var tvCard: TextView? = null
    private var running = false
    private var t = 0.0
    private var el = 5.0
    private var on = 0
    private var off = 0
    private var frac = 0f
    private var lastF = 0L
    private var lastDetectRun = 0L
    private var detector: CardDetector? = null
    private var lastDetectedMsg = ""

    // Costos de elixir (Clash Royale 2026)
    private val elixirMap = mapOf(
        "Archer Queen" to 5, "Archers" to 3, "Arrows" to 3, "Baby Dragon" to 4,
        "Balloon" to 5, "Bandit" to 3, "Barbarians" to 5, "Bats" to 2,
        "Battle Healer" to 4, "Battle Ram" to 4, "Berserker" to 2, "Bomb Tower" to 4,
        "Bomber" to 2, "Boss Bandit" to 6, "Bowler" to 5, "Cannon" to 3,
        "Cannon Cart" to 5, "Cursed Hog" to 4, "Dark Prince" to 4, "Dart Goblin" to 3,
        "Electro Dragon" to 5, "Electro Giant" to 7, "Electro Spirit" to 1, "Electro Wizard" to 4,
        "Elite Barbarians" to 6, "Elixir Collector" to 6, "Elixir Golem" to 3, "Executioner" to 5,
        "Fire Spirit" to 1, "Fireball" to 4, "Firecracker" to 3, "Fisherman" to 3,
        "Flying Machine" to 4, "Furnace" to 4, "Giant" to 5, "Giant Skeleton" to 6,
        "Giant Snowball" to 2, "Goblin Barrel" to 3, "Goblin Cage" to 4, "Goblin Demolisher" to 4,
        "Goblin Drill" to 4, "Goblin Giant" to 6, "Goblin Hut" to 4, "Goblin Machine" to 5,
        "Goblins" to 2, "Goblinstein" to 5, "Golden Knight" to 4, "Golem" to 8,
        "Graveyard" to 5, "Guards" to 3, "Heal Spirit" to 1, "Hog Rider" to 4,
        "Hunter" to 4, "Ice Golem" to 2, "Ice Spirit" to 1, "Ice Wizard" to 3,
        "Inferno Dragon" to 4, "Inferno Tower" to 5, "Knight" to 3, "Lava Hound" to 7,
        "Little Prince" to 3, "Lumberjack" to 4, "Magic Archer" to 4, "Mega Knight" to 7,
        "Mega Minion" to 3, "Mighty Miner" to 4, "Miner" to 3, "Mini P.E.K.K.A" to 4,
        "Minions" to 3, "Monk" to 5, "Mortar" to 4, "Mother Witch" to 4,
        "Musketeer" to 4, "Night Witch" to 4, "P.E.K.K.A" to 7, "Phoenix" to 4,
        "Poison" to 4, "Prince" to 5, "Princess" to 3, "Rage" to 2,
        "Ram Rider" to 5, "Rascals" to 5, "Rocket" to 6, "Royal Ghost" to 3,
        "Royal Giant" to 6, "Royal Hog" to 5, "Royal Hogs" to 5, "Royal Recruits" to 7,
        "Skeleton" to 1, "Skeleton Barrel" to 3, "Skeleton Dragons" to 4, "Skeleton King" to 4,
        "Skeletons" to 1, "Sparky" to 6, "Spear Goblins" to 2, "Spirit Empress" to 6,
        "Suspicious Bush" to 2, "Tesla" to 4, "The Log" to 2, "Tombstone" to 3,
        "Tornado" to 3, "Valkyrie" to 4, "Wall Breakers" to 2, "Witch" to 5,
        "Wizard" to 5, "X-Bow" to 6, "Zappies" to 4
    )

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val code = i?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION")
        val data = i?.getParcelableExtra<Intent>("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        startFg()
        try {
            detector = CardDetector(this)
        } catch (e: Exception) {
            lastDetectedMsg = "Error modelo: ${e.message?.take(40)}"
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        proj = mpm.getMediaProjection(code, data)
        proj?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, h)
        startCapture()
        buildOverlay()
        h.post(tick)
        return START_NOT_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("el", "Elixir", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "el")
            .setContentTitle("Elixir Tracker activo")
            .setSmallIcon(android.R.drawable.ic_menu_view).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    private fun startCapture() {
        val dm = resources.displayMetrics
        // Higher resolution for detection (still downscaled a bit for speed)
        val w = (dm.widthPixels * 0.5f).toInt().coerceAtLeast(320)
        val hh = (dm.heightPixels * 0.5f).toInt().coerceAtLeast(480)
        val r = ImageReader.newInstance(w, hh, PixelFormat.RGBA_8888, 2)
        reader = r
        vd = proj!!.createVirtualDisplay(
            "cap", w, hh, dm.densityDpi / 2,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, h
        )
        r.setOnImageAvailableListener({ rd ->
            val img = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = SystemClock.elapsedRealtime()
                if (now - lastF >= 250) {
                    lastF = now
                    analyze(img)
                    if (running && now - lastDetectRun >= 500) {
                        lastDetectRun = now
                        runCardDetection(img)
                    }
                }
            } finally { img.close() }
        }, h)
    }

    private fun imageToBitmap(img: Image): Bitmap {
        val p = img.planes[0]
        val buf = p.buffer
        val rs = p.rowStride
        val ps = p.pixelStride
        val w = img.width
        val hh = img.height
        val bitmap = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * hh)
        for (y in 0 until hh) {
            for (x in 0 until w) {
                val o = y * rs + x * ps
                val r = buf.get(o).toInt() and 255
                val g = buf.get(o + 1).toInt() and 255
                val b = buf.get(o + 2).toInt() and 255
                pixels[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, hh)
        return bitmap
    }

    private fun runCardDetection(img: Image) {
        val det = detector ?: return
        try {
            val full = imageToBitmap(img)
            val top = (full.height * detectTop).toInt()
            val bottom = (full.height * detectBottom).toInt()
            val crop = Bitmap.createBitmap(full, 0, top, full.width, (bottom - top).coerceAtLeast(1))
            full.recycle()
            val result = det.detect(crop, elixirMap)
            crop.recycle()
            if (result != null) {
                el = maxOf(0.0, el - result.cost)
                lastDetectedMsg = "${result.name} -${result.cost}  (${(result.confidence * 100).toInt()}%)"
                h.post { render() }
            }
        } catch (_: Exception) { }
    }

    private fun analyze(img: Image) {
        val p = img.planes[0]
        val buf = p.buffer
        val rs = p.rowStride
        val ps = p.pixelStride
        val w = img.width
        val hh = img.height
        val y0 = (hh * stripTop).toInt()
        val y1 = (hh * stripBottom).toInt()
        val x0 = (w * xFrom).toInt()
        val x1 = (w * xTo).toInt()
        var tot = 0
        var pink = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            val o = y * rs + x * ps
            val r = buf.get(o).toInt() and 255
            val g = buf.get(o + 1).toInt() and 255
            val b = buf.get(o + 2).toInt() and 255
            tot++
            if (r > 170 && b > 150 && g < 120 && r - g > 80 && b - g > 60) pink++
        }
        frac = if (tot > 0) pink.toFloat() / tot else 0f
        if (!running) {
            if (frac >= onThreshold) { if (++on >= 3) startMatch() } else on = 0
        } else {
            if (frac <= offThreshold) { if (++off >= 12) stopMatch() } else off = 0
        }
    }

    private fun startMatch() {
        running = true; t = 0.0; el = 5.0; on = 0; off = 0
        lastDetectedMsg = "Detectando cartas…"
    }
    private fun stopMatch() {
        running = false; on = 0; off = 0
        lastDetectedMsg = ""
    }
    private fun mult() = if (t < 120) 1 else if (t < 240) 2 else 3

    private val tick = object : Runnable {
        override fun run() {
            if (running) { t += 0.1; el = minOf(10.0, el + 0.1 * mult() / 2.8) }
            render()
            h.postDelayed(this, 100)
        }
    }

    private fun render() {
        val s = if (running) {
            val mm = (t / 60).toInt()
            val ss = String.format("%02d", t.toInt() % 60)
            "Rival: ${el.toInt()}   $mm:$ss   x${mult()}"
        } else {
            "Esperando partida… ${(frac * 100).toInt()}% (tocá para iniciar)"
        }
        tv?.text = s
        tvCard?.text = if (lastDetectedMsg.isNotEmpty()) lastDetectedMsg else "Botones + detección IA"
    }

    private fun buildOverlay() {
        wm = getSystemService(WindowManager::class.java)
        val d = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xCC000000.toInt())
            setPadding(12, 8, 12, 8)
        }
        tv = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 15f
            setOnClickListener { if (running) stopMatch() else startMatch() }
        }
        box.addView(tv)
        tvCard = TextView(this).apply {
            setTextColor(0xFFAAAAAA.toInt()); textSize = 12f
            text = "Botones + detección IA"
        }
        box.addView(tvCard)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (c in 1..7) {
            row.addView(Button(this).apply {
                text = "$c"; textSize = 12f; minWidth = 0; minimumWidth = 0; setPadding(0, 0, 0, 0)
                setOnClickListener { el = maxOf(0.0, el - c); lastDetectedMsg = "Manual -$c" }
            }, LinearLayout.LayoutParams((38 * d).toInt(), (38 * d).toInt()))
        }
        box.addView(row)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 280 }
        wm?.addView(box, lp)
        root = box
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        try { root?.let { wm?.removeView(it) } } catch (_: Exception) {}
        detector?.close()
        vd?.release(); reader?.close(); proj?.stop()
        super.onDestroy()
    }
}
