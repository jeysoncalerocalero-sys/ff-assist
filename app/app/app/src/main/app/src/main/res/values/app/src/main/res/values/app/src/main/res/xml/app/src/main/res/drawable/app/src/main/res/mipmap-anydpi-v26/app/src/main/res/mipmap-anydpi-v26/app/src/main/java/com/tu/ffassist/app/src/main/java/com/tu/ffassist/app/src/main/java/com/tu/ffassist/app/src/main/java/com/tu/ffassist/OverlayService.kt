package com.tu.ffassist

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
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
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ServiceCompat
import kotlin.math.hypot

class OverlayService : Service() {

    companion object {
        const val ACTION_AIM = "com.tu.ffassist.AIM"
        private const val CH = "ff"
    }

    private lateinit var wm: WindowManager
    private var crosshair: CrosshairView? = null
    private var panel: LinearLayout? = null
    private val handler = Handler(Looper.getMainLooper())

    private var tvStrength: TextView? = null
    private var tvInterval: TextView? = null
    private var tvRadius: TextView? = null
    private var tvHoriz: TextView? = null
    private var bRecoil: Button? = null
    private var bAim: Button? = null
    private var bTap: Button? = null

    private var firing = false
    private var aiming = false
    private var tapMode = true
    private var processing = false
    private var lastAim = 0L
    private var driftSign = 1

    private var strength = 14
    private var intervalMs = 26L
    private var radius = 40
    private var hStrength = 3
    private val aimThrottle = 140L

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vdisplay: VirtualDisplay? = null
    private var aimThread: HandlerThread? = null
    private var aimHandler: Handler? = null

    private val recoil = object : Runnable {
        override fun run() {
            if (!firing) return
            val dm = resources.displayMetrics
            val x = dm.widthPixels / 2
            val y = (dm.heightPixels * 0.76f).toInt()
            val dx = if (hStrength > 0) driftSign * hStrength else 0
            driftSign = -driftSign
            TapService.instance?.swipe(x, y, x + dx, y + strength, intervalMs)
            handler.postDelayed(this, intervalMs)
        }
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        strength = Prefs.getInt(this, Prefs.K_STRENGTH, 14)
        intervalMs = Prefs.getLong(this, Prefs.K_INTERVAL, 26L)
        radius = Prefs.getInt(this, Prefs.K_RADIUS, 40)
        hStrength = Prefs.getInt(this, Prefs.K_HORIZ, 3)
        tapMode = Prefs.getInt(this, Prefs.K_TAP, 1) == 1

        try {
            ServiceCompat.startForeground(
                this, 1, notif("FF Assist activo"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } catch (_: Exception) {
        }

        if (!Settings.canDrawOverlays(this)) {
            toast("Falta el permiso de superposición")
            stopSelf()
            return
        }

        try {
            addCrosshair()
        } catch (e: Exception) {
            toast("Error de mira: ${e.message}")
        }
        try {
            addPanel()
        } catch (e: Exception) {
            toast("Error de panel: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_AIM) {
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra("data", Intent::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra("data")
            val code = intent.getIntExtra("code", Activity.RESULT_OK)
            if (data != null) startProjection(code, data)
        }
        return START_STICKY
    }

    private fun toast(t: String) {
        Toast.makeText(this, t, Toast.LENGTH_LONG).show()
    }

    private fun lp(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    )

    private fun addCrosshair() {
        val size = (140 * resources.displayMetrics.density).toInt()
        val view = CrosshairView(this).apply {
            color = Prefs.getInt(this@OverlayService, Prefs.K_COLOR, Color.parseColor("#FF2D2D"))
        }
        val p = lp(size, size).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels / 2 - size / 2
            y = resources.displayMetrics.heightPixels / 2 - size / 2
        }
        crosshair = view
        wm.addView(view, p)
        makeDraggable(view, p)
    }

    private fun addPanel() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#C0000000"))
            setPadding(20, 20, 20, 20)
        }

        box.addView(TextView(this).apply {
            text = "FF ASSIST"
            setTextColor(Color.WHITE)
            textSize = 12f
        })

        fun row(label: String, holder: (TextView) -> Unit, minus: () -> Unit, plus: () -> Unit) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            r.addView(Button(this).apply { text = "-"; setOnClickListener { minus() } })
            val tv = TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 11f
                setPadding(14, 0, 14, 0)
            }
            holder(tv)
            r.addView(tv)
            r.addView(Button(this).apply { text = "+"; setOnClickListener { plus() } })
            box.addView(r)
        }

        row("Fuerza $strength", { tvStrength = it }, {
            strength = (strength - 2).coerceAtLeast(2)
            Prefs.setInt(this, Prefs.K_STRENGTH, strength)
            refresh()
        }, {
            strength += 2
            Prefs.setInt(this, Prefs.K_STRENGTH, strength)
            refresh()
        })

        row("Vel $intervalMs", { tvInterval = it }, {
            intervalMs = (intervalMs - 4).coerceAtLeast(10)
            Prefs.setLong(this, Prefs.K_INTERVAL, intervalMs)
            refresh()
        }, {
            intervalMs += 4
            Prefs.setLong(this, Prefs.K_INTERVAL, intervalMs)
            refresh()
        })

        row("Radio $radius", { tvRadius = it }, {
            radius = (radius - 10).coerceAtLeast(10)
            Prefs.setInt(this, Prefs.K_RADIUS, radius)
            refresh()
        }, {
            radius += 10
            Prefs.setInt(this, Prefs.K_RADIUS, radius)
            refresh()
        })

        row("Horiz $hStrength", { tvHoriz = it }, {
            hStrength = (hStrength - 1).coerceAtLeast(0)
            Prefs.setInt(this, Prefs.K_HORIZ, hStrength)
            refresh()
        }, {
            hStrength = (hStrength + 1).coerceAtMost(10)
            Prefs.setInt(this, Prefs.K_HORIZ, hStrength)
            refresh()
        })

        bRecoil = Button(this).apply {
            text = if (firing) "RECOIL ON" else "RECOIL"
            setOnClickListener {
                firing = !firing
                handler.removeCallbacks(recoil)
                if (firing) handler.post(recoil)
                refresh()
            }
        }
        box.addView(bRecoil)

        bAim = Button(this).apply {
            text = if (aiming) "AIM ON" else "AIM"
            setOnClickListener { aiming = !aiming; refresh() }
        }
        box.addView(bAim)

        bTap = Button(this).apply {
            text = if (tapMode) "TAP ON" else "TAP OFF"
            setOnClickListener {
                tapMode = !tapMode
                Prefs.setInt(this@OverlayService, Prefs.K_TAP, if (tapMode) 1 else 0)
                refresh()
            }
        }
        box.addView(bTap)

        box.addView(Button(this).apply {
            text = "MIRA"
            setOnClickListener {
                val c = crosshair ?: return@setOnClickListener
                c.color = if (c.color == Color.parseColor("#FF2D2D")) Color.GREEN
                else Color.parseColor("#FF2D2D")
                Prefs.setInt(this@OverlayService, Prefs.K_COLOR, c.color)
                c.invalidate()
            }
        })

        box.addView(Button(this).apply {
            text = "CENTRAR"
            setOnClickListener {
                val c = crosshair ?: return@setOnClickListener
                val pc = c.layoutParams as WindowManager.LayoutParams
                pc.x = resources.displayMetrics.widthPixels / 2 - c.width / 2
                pc.y = resources.displayMetrics.heightPixels / 2 - c.height / 2
                wm.updateViewLayout(c, pc)
            }
        })

        box.addView(Button(this).apply {
            text = "CERRAR"
            setOnClickListener { stopSelf() }
        })

        val p = lp(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 160
        }
        panel = box
        wm.addView(box, p)
        makeDraggable(box, p)
    }

    private fun refresh() {
        tvStrength?.text = "Fuerza $strength"
        tvInterval?.text = "Vel $intervalMs"
        tvRadius?.text = "Radio $radius"
        tvHoriz?.text = "Horiz $hStrength"
        bRecoil?.text = if (firing) "RECOIL ON" else "RECOIL"
        bAim?.text = if (aiming) "AIM ON" else "AIM"
        bTap?.text = if (tapMode) "TAP ON" else "TAP OFF"
    }

    private fun makeDraggable(v: View, p: WindowManager.LayoutParams) {
        var dx = 0
        var dy = 0
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    dx = p.x - e.rawX.toInt()
                    dy = p.y - e.rawY.toInt()
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    p.x = e.rawX.toInt() + dx
                    p.y = e.rawY.toInt() + dy
                    wm.updateViewLayout(v, p)
                    true
                }
                else -> false
            }
        }
    }

    private fun startProjection(code: Int, data: Intent) {
        if (projection != null) return
        try {
            ServiceCompat.startForeground(
                this, 2, notif("Aim listo"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val proj = mpm.getMediaProjection(code, data) ?: return
            projection = proj
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { aiming = false }
            }, Handler(Looper.getMainLooper()))

            val dm = resources.displayMetrics
            val cw = (dm.widthPixels / 2).coerceAtLeast(240)
            val ch = (dm.heightPixels / 2).coerceAtLeast(240)

            val t = HandlerThread("aim").also { it.start() }
            aimThread = t
            val h = Handler(t.looper)
            aimHandler = h

            val r = ImageReader.newInstance(cw, ch, PixelFormat.RGBA_8888, 2)
            reader = r
            vdisplay = proj.createVirtualDisplay(
                "aim", cw, ch, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface, null, h
            )

            r.setOnImageAvailableListener({ ir ->
                val img = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
                if (processing) { img.close(); return@setOnImageAvailableListener }
                processing = true
                h.post { analyze(img) }
            }, h)
        } catch (e: Exception) {
            toast("Error de captura: ${e.message}")
        }
    }

    private fun analyze(image: Image) {
        try {
            if (!aiming) return
            val w = image.width
            val hgt = image.height
            val plane = image.planes[0]
            val ps = plane.pixelStride
            val rs = plane.rowStride
            val pad = rs - ps * w
            val raw = Bitmap.createBitmap(w + pad / ps, hgt, Bitmap.Config.ARGB_8888)
            raw.copyPixelsFromBuffer(plane.buffer)
            val bmp = if (pad == 0) raw else Bitmap.createBitmap(raw, 0, 0, w, hgt)

            val x0 = (w * 0.28f).toInt()
            val x1 = (w * 0.72f).toInt()
            val y0 = (hgt * 0.10f).toInt()
            val y1 = (hgt * 0.84f).toInt()
            val rw = x1 - x0
            val rh = y1 - y0
            if (rw <= 0 || rh <= 0) return

            val px = IntArray(rw * rh)
            bmp.getPixels(px, 0, rw, x0, y0, rw, rh)

            var minY = rh
            var count = 0
            var i = 0
            for (y in 0 until rh) {
                for (x in 0 until rw) {
                    val c = px[i++]
                    val rr = (c shr 16) and 0xFF
                    val gg = (c shr 8) and 0xFF
                    val bb = c and 0xFF
                    if (rr > 110 && rr - gg > 45 && rr - bb > 45) {
                        count++
                        if (y < minY) minY = y
                    }
                }
            }
            if (count < 40 || minY == rh) return

            val band = (rh * 0.18f).toInt().coerceAtLeast(6)
            val w0 = (rw / 6f).coerceAtLeast(1f)
            val centerCol = rw / 2f

            var sw = 0.0
            var sxw = 0.0
            var syw = 0.0
            i = 0
            for (y in 0 until rh) {
                for (x in 0 until rw) {
                    val c = px[i++]
                    if (y > minY + band) continue
                    val rr = (c shr 16) and 0xFF
                    val gg = (c shr 8) and 0xFF
                    val bb = c and 0xFF
                    if (rr > 110 && rr - gg > 45 && rr - bb > 45) {
                        val dcol = (x - centerCol) / w0
                        val wgt = 1.0 / (1.0 + dcol * dcol)
                        sw += wgt
                        sxw += x * wgt
                        syw += y * wgt
                    }
                }
            }
            if (sw <= 0.0) return

            val cx = x0 + (sxw / sw).toInt()
            val cy = y0 + (syw / sw).toInt()

            val dm = resources.displayMetrics
            val scaleX = dm.widthPixels.toFloat() / w
            val scaleY = dm.heightPixels.toFloat() / hgt

            val now = SystemClock.uptimeMillis()
            if (now - lastAim < aimThrottle) return

            val dx = cx - w / 2f
            val dy = cy - hgt / 2f
            val dist = hypot(dx, dy)

            val cxScreen = dm.widthPixels / 2f
            val cyScreen = dm.heightPixels / 2f

            if (dist < radius) {
                if (tapMode) TapService.instance?.tap((cx * scaleX).toInt(), (cy * scaleY).toInt())
            } else {
                TapService.instance?.swipe(
                    cxScreen.toInt(), cyScreen.toInt(),
                    (cxScreen + dx * scaleX).toInt(), (cyScreen + dy * scaleY).toInt(),
                    45
                )
            }
            lastAim = now
        } catch (_: Exception) {
        } finally {
            image.close()
            processing = false
        }
    }

    private fun notif(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CH, "FF Assist", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, CH)
            .setContentTitle(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { reader?.close() }
        runCatching { vdisplay?.release() }
        runCatching { projection?.stop() }
        runCatching { aimThread?.quitSafely() }
        crosshair?.let { runCatching { wm.removeView(it) } }
        panel?.let { runCatching { wm.removeView(it) } }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
