package app.kettler.pad

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.UUID

/**
 * Floating speed buttons (1..14) drawn over other apps (for example Kinomap).
 * Every button is its own small window, so the gaps between buttons stay transparent and touches go through.
 * Talks to the treadmill over BLE FTMS: request control (0x00), set target speed (0x02), stop (0x08 0x01).
 */
class PadService : Service() {

    companion object {
        val FTMS: UUID = UUID.fromString("00001826-0000-1000-8000-00805f9b34fb")
        val CP: UUID = UUID.fromString("00002ad9-0000-1000-8000-00805f9b34fb")
        val DATA: UUID = UUID.fromString("00002acd-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val CHANNEL = "pad"
    }

    private class Item(
        val view: TextView,
        val rx: Int,
        val ry: Int,
        val w: Int,
        val h: Int,
        val bg: GradientDrawable,
        val lp: WindowManager.LayoutParams
    ) {
        var on = false
    }

    private class Lay(val w: Int, val h: Int) {
        val pos = ArrayList<IntArray>()      // 14 entries: x, y of buttons 1..14
        var hub = IntArray(3)                // x, y, diameter
        var stop = IntArray(4)               // x, y, w, h
        var start = IntArray(4)              // x, y, w, h
    }

    private val ui = Handler(Looper.getMainLooper())
    private var running = false
    private lateinit var prefs: SharedPreferences

    private val rebuildRunnable = Runnable { rebuildPanel() }
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "layout" || key == "size" || key == "alpha") {
            ui.removeCallbacks(rebuildRunnable)
            ui.postDelayed(rebuildRunnable, 200)
        }
    }

    // ---- Bluetooth state ----
    private var gatt: BluetoothGatt? = null
    private var cpChar: BluetoothGattCharacteristic? = null
    private var state = "OFF"            // OFF, CONNECTING, ON, READY
    private var ready = false
    private var speed = 0.0
    private var wanted = -1.0
    private var retry = 0
    private var fails = 0
    private var scanCb: ScanCallback? = null
    private val reconnectRun = Runnable { connect() }
    private val timeoutRun = Runnable {
        if (running && state == "CONNECTING") {
            fails++
            closeGatt()
            setState("OFF", "انتهت مهلة الاتصال — إعادة المحاولة")
            scheduleReconnect(1500)
        }
    }

    // ---- GATT operations must run one at a time ----
    private val ops = ArrayDeque<() -> Boolean>()
    private var busy = false
    private var token = 0

    // ---- overlay ----
    private var wm: WindowManager? = null
    private val items = ArrayList<Item>()
    private val numItems = HashMap<Int, Item>()
    private var hubItem: Item? = null
    private var originX = 0
    private var originY = 0
    private var layoutW = 0
    private var layoutH = 0
    private var expanded = false
    private var placed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_NOT_STICKY
        running = true
        prefs = getSharedPreferences("pad", Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        val n = buildNotification("جارٍ البدء...")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, n)
        }
        rebuildPanel()
        connect()
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        ui.post { rebuildPanel() }
    }

    override fun onDestroy() {
        running = false
        try {
            prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        } catch (e: Exception) {
        }
        ui.removeCallbacksAndMessages(null)
        stopScan()
        closeGatt()
        removeAllWindows()
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Kettler Pad", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Kettler Pad")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    private fun updateNote(text: String) {
        try {
            prefs.edit().putString("state", text).apply()
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(1, buildNotification(text))
        } catch (e: Exception) {
        }
    }

    private fun dp(x: Int): Int = (x * resources.displayMetrics.density).toInt()

    // =====================================================================
    //  Bluetooth
    // =====================================================================
    private fun setState(s: String, note: String? = null) {
        state = s
        val t = note ?: when (s) {
            "CONNECTING" -> "جارٍ الاتصال بالمشاية..."
            "ON" -> "متصل — جارٍ طلب التحكم"
            "READY" -> "جاهز — يمكنك تغيير السرعة"
            else -> "غير متصل بالمشاية"
        }
        updateNote(t)
        refreshUi()
    }

    private fun scheduleReconnect(ms: Long) {
        ui.removeCallbacks(reconnectRun)
        if (running) ui.postDelayed(reconnectRun, ms)
    }

    @SuppressLint("MissingPermission")
    private fun connect() {
        if (!running) return
        ui.removeCallbacks(reconnectRun)
        ui.removeCallbacks(timeoutRun)
        val addr = prefs.getString("addr", null)
        if (addr == null) {
            setState("OFF", "لم تُختر مشاية")
            return
        }
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            setState("OFF", "البلوتوث مغلق")
            scheduleReconnect(4000)
            return
        }
        closeGatt()
        // after several failures the address may have changed: look for the saved name
        if (fails >= 3 && fails % 3 == 0 && scanCb == null) {
            findByName(adapter.bluetoothLeScanner)
        }
        setState("CONNECTING")
        val dev = adapter.getRemoteDevice(addr)
        gatt = dev.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        ui.postDelayed(timeoutRun, 20000)
    }

    /** Long press on the hub: drop everything and connect again right now. */
    private fun forceReconnect() {
        if (!running) return
        fails = 0
        stopScan()
        Toast.makeText(this, "جارٍ الاتصال بالمشاية...", Toast.LENGTH_SHORT).show()
        connect()
    }

    @SuppressLint("MissingPermission")
    private fun findByName(scanner: android.bluetooth.le.BluetoothLeScanner?) {
        val want = prefs.getString("name", null) ?: return
        if (scanner == null) return
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val nm = result.scanRecord?.deviceName ?: try { result.device.name } catch (e: Exception) { null }
                if (nm != null && nm.equals(want, ignoreCase = true)) {
                    val a = result.device.address
                    ui.post {
                        stopScan()
                        if (a != prefs.getString("addr", null)) {
                            prefs.edit().putString("addr", a).apply()
                            fails = 0
                            connect()
                        }
                    }
                }
            }
        }
        scanCb = cb
        try {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
            ui.postDelayed({ stopScan() }, 8000)
        } catch (e: Exception) {
            scanCb = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        val cb = scanCb ?: return
        scanCb = null
        try {
            val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            adapter?.bluetoothLeScanner?.stopScan(cb)
        } catch (e: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        val g = gatt
        gatt = null
        cpChar = null
        ops.clear()
        busy = false
        ready = false
        try {
            g?.disconnect()
            g?.close()
        } catch (e: Exception) {
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            ui.post {
                if (g !== gatt) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    try {
                        g.discoverServices()
                    } catch (e: Exception) {
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    ui.removeCallbacks(timeoutRun)
                    closeGatt()
                    fails++
                    setState("OFF", "انقطع الاتصال (رمز " + status + ") — إعادة المحاولة")
                    scheduleReconnect(2000)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            ui.post {
                if (g !== gatt) return@post
                val svc = g.getService(FTMS)
                val c = svc?.getCharacteristic(CP)
                if (svc == null || c == null) {
                    Toast.makeText(this@PadService, "هذا الجهاز لا يدعم FTMS", Toast.LENGTH_LONG).show()
                    ui.removeCallbacks(timeoutRun)
                    closeGatt()
                    fails++
                    setState("OFF", "الجهاز المختار لا يدعم FTMS")
                    scheduleReconnect(5000)
                    return@post
                }
                ui.removeCallbacks(timeoutRun)
                fails = 0
                cpChar = c
                setState("ON")
                enableCcc(g, c, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
                val d = svc.getCharacteristic(DATA)
                if (d != null) enableCcc(g, d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                requestControl()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            ui.post { opDone() }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            ui.post { opDone() }
        }

        // Android 13 and newer call this version
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val v = value.copyOf()
            val u = characteristic.uuid
            ui.post { handleData(u, v) }
        }

        // Android 12 and older call this version
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                val raw = characteristic.value
                if (raw != null) {
                    val v = raw.copyOf()
                    val u = characteristic.uuid
                    ui.post { handleData(u, v) }
                }
            }
        }
    }

    // ---- operation queue ----
    private fun enqueue(op: () -> Boolean) {
        ops.addLast(op)
        next()
    }

    private fun next() {
        if (busy) return
        val op = ops.removeFirstOrNull() ?: return
        busy = true
        token++
        val mine = token
        var ok = false
        try {
            ok = op()
        } catch (e: Exception) {
            ok = false
        }
        if (!ok) {
            busy = false
            next()
            return
        }
        ui.postDelayed({
            if (busy && token == mine) {
                busy = false
                next()
            }
        }, 1500)
    }

    private fun opDone() {
        busy = false
        next()
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun enableCcc(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
        enqueue {
            g.setCharacteristicNotification(c, true)
            val d = c.getDescriptor(CCCD)
            if (d == null) {
                false
            } else {
                d.value = value
                g.writeDescriptor(d)
            }
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun writeCp(b: ByteArray): Boolean {
        val g = gatt ?: return false
        val c = cpChar ?: return false
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        c.value = b
        return g.writeCharacteristic(c)
    }

    private fun requestControl() {
        enqueue { writeCp(byteArrayOf(0x00)) }
    }

    private fun sendSpeed(kmh: Double) {
        val v = Math.round(kmh * 100.0).toInt()
        enqueue { writeCp(byteArrayOf(0x02, (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())) }
    }

    private fun handleData(u: UUID, v: ByteArray) {
        if (u == DATA) {
            if (v.size >= 4) {
                val flags = (v[0].toInt() and 0xFF) or ((v[1].toInt() and 0xFF) shl 8)
                if ((flags and 1) == 0) {                      // bit 0 = 0 means instantaneous speed is present
                    val raw = (v[2].toInt() and 0xFF) or ((v[3].toInt() and 0xFF) shl 8)
                    speed = raw / 100.0
                    refreshUi()
                }
            }
        } else if (u == CP) {
            if (v.size >= 3 && (v[0].toInt() and 0xFF) == 0x80) {
                val op = v[1].toInt() and 0xFF
                val res = v[2].toInt() and 0xFF
                if (op == 0x00 && res == 0x01) {
                    ready = true
                    setState("READY")
                } else if (op == 0x00) {
                    updateNote("رفضت المشاية طلب التحكم (رمز " + res + ")")
                } else if (op == 0x02 && res == 0x01) {
                    retry = 0
                } else if (op == 0x02 && res == 0x05 && retry < 2 && wanted > 0) {
                    retry++                                     // control was taken from us: ask again, then repeat the speed
                    ready = false
                    requestControl()
                    sendSpeed(wanted)
                } else if (op == 0x07 && res != 0x01) {
                    updateNote("رفضت المشاية التشغيل (رمز " + res + ")")
                } else if (op == 0x02) {
                    updateNote("رفضت المشاية السرعة (رمز " + res + ")")
                }
            }
        }
    }

    // ---- button actions ----
    private fun onTap(n: Int) {
        if (gatt == null) {
            Toast.makeText(this, "غير متصل بالمشاية", Toast.LENGTH_SHORT).show()
            return
        }
        wanted = n.toDouble()
        retry = 0
        if (!ready) requestControl()
        sendSpeed(wanted)
    }

    // one button: request control (0x00), short pause, then start/resume (0x07)
    private fun onStart() {
        if (gatt == null) {
            Toast.makeText(this, "غير متصل بالمشاية", Toast.LENGTH_SHORT).show()
            return
        }
        requestControl()
        ui.postDelayed({
            if (gatt != null) enqueue { writeCp(byteArrayOf(0x07)) }
        }, 400)
    }

    private fun onStop() {
        if (gatt == null) return
        wanted = -1.0
        if (!ready) requestControl()
        enqueue { writeCp(byteArrayOf(0x08, 0x01)) }
    }

    // =====================================================================
    //  Floating panel
    // =====================================================================
    @Suppress("DEPRECATION")
    private fun screenSize(): IntArray {
        val m = wm
        if (m != null) {
            if (Build.VERSION.SDK_INT >= 30) {
                val b = m.currentWindowMetrics.bounds
                return intArrayOf(b.width(), b.height())
            }
            val dm = DisplayMetrics()
            m.defaultDisplay.getRealMetrics(dm)
            return intArrayOf(dm.widthPixels, dm.heightPixels)
        }
        val r = resources.displayMetrics
        return intArrayOf(r.widthPixels, r.heightPixels)
    }

    private fun speedColor(n: Int, alpha: Int): Int {
        val hue = 210f * (1f - (n - 1) / 13f)          // 1 = blue ... 14 = red
        return Color.HSVToColor(alpha, floatArrayOf(hue, 0.85f, 0.9f))
    }

    private fun splitStartStop(lay: Lay, x: Int, y: Int, w: Int, h: Int, gap: Int) {
        val half = (w - gap) / 2
        lay.start = intArrayOf(x, y, half, h)
        lay.stop = intArrayOf(x + half + gap, y, half, h)
    }

    private fun computeLayout(kind: String, d: Int, gap: Int, land: Boolean): Lay {
        if (kind == "frame") {
            val cols = if (land) 5 else 4
            val rows = if (land) 4 else 5
            val step = d + gap
            val lay = Lay(cols * d + (cols - 1) * gap, rows * d + (rows - 1) * gap)
            val cells = ArrayList<IntArray>()
            for (c in 0 until cols) cells.add(intArrayOf(c, 0))
            for (r in 1 until rows) cells.add(intArrayOf(cols - 1, r))
            for (c in cols - 2 downTo 0) cells.add(intArrayOf(c, rows - 1))
            for (r in rows - 2 downTo 1) cells.add(intArrayOf(0, r))
            for (cell in cells) lay.pos.add(intArrayOf(cell[0] * step, cell[1] * step))
            val iw = (cols - 2) * d + (cols - 3) * gap
            val ih = (rows - 2) * d + (rows - 3) * gap
            if (!land) {
                lay.hub = intArrayOf(step, step, iw)
                splitStartStop(lay, step, step + iw + gap, iw, ih - iw - gap, gap)
            } else {
                lay.hub = intArrayOf(step, step, ih)
                val sw = iw - ih - gap
                val sh = Math.min(d, ih)
                splitStartStop(lay, step + ih + gap, step + (ih - sh) / 2, sw, sh, gap)
            }
            return lay
        }
        if (kind == "grid") {
            val cols = if (land) 8 else 2
            val rows = (17 + cols - 1) / cols
            val step = d + gap
            val lay = Lay(cols * d + (cols - 1) * gap, rows * d + (rows - 1) * gap)
            for (i in 0 until 14) {
                val idx = i + 1
                lay.pos.add(intArrayOf((idx % cols) * step, (idx / cols) * step))
            }
            lay.hub = intArrayOf(0, 0, d)
            lay.start = intArrayOf((15 % cols) * step, (15 / cols) * step, d, d)
            lay.stop = intArrayOf((16 % cols) * step, (16 / cols) * step, d, d)
            return lay
        }
        // ring
        val radius = (d + gap) / (2.0 * Math.sin(Math.PI / 14))
        val total = Math.ceil(2 * radius + d).toInt()
        val lay = Lay(total, total)
        val c = total / 2.0
        for (i in 0 until 14) {
            val th = -Math.PI / 2 + i * 2 * Math.PI / 14
            val cx = c + radius * Math.cos(th)
            val cy = c + radius * Math.sin(th)
            lay.pos.add(intArrayOf((cx - d / 2.0).toInt(), (cy - d / 2.0).toInt()))
        }
        val innerR = radius - d / 2.0 - gap
        val stopD = (d * 0.9).toInt()
        var hubD = (d * 1.8).toInt()
        val maxHub = (2 * innerR - gap - stopD).toInt()
        if (hubD > maxHub) hubD = maxHub
        val stack = hubD + gap + stopD
        val top = (c - stack / 2.0).toInt()
        lay.hub = intArrayOf((c - hubD / 2.0).toInt(), top, hubD)
        val stackHalf = stack / 2.0
        val chord = 2 * Math.sqrt(Math.max(0.0, innerR * innerR - stackHalf * stackHalf)) * 0.95
        val rowW = Math.max(stopD.toDouble(), Math.min(2.0 * stopD + gap, chord)).toInt()
        splitStartStop(lay, (c - rowW / 2.0).toInt(), top + hubD + gap, rowW, stopD, gap)
        return lay
    }

    private fun windowParams(w: Int, h: Int): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            w, h, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.LEFT
        return lp
    }

    private fun makeItem(rx: Int, ry: Int, w: Int, h: Int, fill: Int, textPx: Float): Item {
        val tv = TextView(this)
        tv.gravity = Gravity.CENTER
        tv.setTextColor(Color.WHITE)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx)
        tv.typeface = Typeface.DEFAULT_BOLD
        tv.includeFontPadding = false
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.RECTANGLE
        bg.cornerRadius = Math.min(w, h) / 2f
        bg.setColor(fill)
        tv.background = bg
        return Item(tv, rx, ry, w, h, bg, windowParams(w, h))
    }

    private fun addItem(x: Item) {
        val m = wm ?: return
        if (x.on) return
        x.lp.x = originX + x.rx
        x.lp.y = originY + x.ry
        try {
            m.addView(x.view, x.lp)
            x.on = true
        } catch (e: Exception) {
        }
    }

    private fun removeItem(x: Item) {
        val m = wm ?: return
        if (!x.on) return
        try {
            m.removeView(x.view)
        } catch (e: Exception) {
        }
        x.on = false
    }

    private fun removeAllWindows() {
        for (x in items) removeItem(x)
        val h = hubItem
        if (h != null) removeItem(h)
        items.clear()
        numItems.clear()
        hubItem = null
    }

    private fun rebuildPanel() {
        if (!running) return
        if (!Settings.canDrawOverlays(this)) return
        val old = hubItem
        val hubScreenX = if (old != null) originX + old.rx else -1
        val hubScreenY = if (old != null) originY + old.ry else -1
        removeAllWindows()

        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val scr = screenSize()
        val sw = scr[0]
        val sh = scr[1]
        val land = sw > sh
        val kind = prefs.getString("layout", "ring") ?: "ring"
        val gap = dp(4)
        var d = dp(prefs.getInt("size", 44))
        var lay = computeLayout(kind, d, gap, land)
        val margin = dp(8)
        var guard = 0
        while ((lay.w > sw - 2 * margin || lay.h > sh - 2 * margin) && d > dp(24) && guard < 60) {
            d = (d * 0.94f).toInt()
            lay = computeLayout(kind, d, gap, land)
            guard++
        }
        layoutW = lay.w
        layoutH = lay.h
        val alpha = (prefs.getInt("alpha", 85) * 255 / 100).coerceIn(80, 255)

        for (n in 1..14) {
            val p = lay.pos[n - 1]
            val itm = makeItem(p[0], p[1], d, d, speedColor(n, alpha), d * 0.42f)
            itm.view.text = n.toString()
            itm.view.setOnClickListener { onTap(n) }
            items.add(itm)
            numItems[n] = itm
        }
        val stopItm = makeItem(lay.stop[0], lay.stop[1], lay.stop[2], lay.stop[3], Color.argb(Math.max(alpha, 200), 200, 30, 30), Math.min(lay.stop[2], lay.stop[3]) * 0.45f)
        stopItm.view.text = "■"
        stopItm.view.setOnClickListener { onStop() }
        items.add(stopItm)
        val startItm = makeItem(lay.start[0], lay.start[1], lay.start[2], lay.start[3], Color.argb(Math.max(alpha, 200), 30, 150, 70), Math.min(lay.start[2], lay.start[3]) * 0.45f)
        startItm.view.text = "▶"
        startItm.view.setOnClickListener { onStart() }
        items.add(startItm)

        val hubD = lay.hub[2]
        val hub = makeItem(lay.hub[0], lay.hub[1], hubD, hubD, Color.argb(235, 24, 30, 36), hubD * 0.34f)
        hubItem = hub
        attachDrag(hub)

        if (hubScreenX >= 0 && placed) {
            originX = hubScreenX - hub.rx
            originY = hubScreenY - hub.ry
        } else {
            originX = sw - hubD - dp(12) - hub.rx
            originY = sh / 3 - hub.ry
            placed = true
        }
        clampOrigin()

        addItem(hub)
        if (expanded) {
            for (x in items) addItem(x)
        }
        refreshUi()
    }

    private fun clampOrigin() {
        val h = hubItem ?: return
        val s = screenSize()
        if (expanded) {
            originX = originX.coerceIn(0, Math.max(0, s[0] - layoutW))
            originY = originY.coerceIn(0, Math.max(0, s[1] - layoutH))
        } else {
            val minX = -h.rx
            val minY = -h.ry
            originX = originX.coerceIn(minX, Math.max(minX, s[0] - h.w - h.rx))
            originY = originY.coerceIn(minY, Math.max(minY, s[1] - h.h - h.ry))
        }
    }

    private fun positionAll() {
        val m = wm ?: return
        val all = ArrayList<Item>(items)
        val h = hubItem
        if (h != null) all.add(h)
        for (x in all) {
            x.lp.x = originX + x.rx
            x.lp.y = originY + x.ry
            if (x.on) {
                try {
                    m.updateViewLayout(x.view, x.lp)
                } catch (e: Exception) {
                }
            }
        }
    }

    private fun toggle() {
        expanded = !expanded
        clampOrigin()
        positionAll()
        if (expanded) {
            for (x in items) addItem(x)
        } else {
            for (x in items) removeItem(x)
        }
    }

    private fun attachDrag(hub: Item) {
        var sx = 0f
        var sy = 0f
        var ox = 0
        var oy = 0
        var moved = false
        var longFired = false
        var pendingTap = false
        val longRun = Runnable {
            if (!moved) {
                longFired = true
                hub.view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                forceReconnect()
            }
        }
        val tapRun = Runnable {
            pendingTap = false
            toggle()
        }
        hub.view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX
                    sy = e.rawY
                    ox = originX
                    oy = originY
                    moved = false
                    longFired = false
                    ui.postDelayed(longRun, 600)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx
                    val dy = e.rawY - sy
                    if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) {
                        moved = true
                        ui.removeCallbacks(longRun)
                    }
                    if (moved) {
                        originX = ox + dx.toInt()
                        originY = oy + dy.toInt()
                        clampOrigin()
                        positionAll()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    ui.removeCallbacks(longRun)
                    if (!moved && !longFired) {
                        if (pendingTap) {
                            // second tap: remove the circle completely
                            ui.removeCallbacks(tapRun)
                            pendingTap = false
                            Toast.makeText(this, "أُغلقت اللوحة", Toast.LENGTH_SHORT).show()
                            stopSelf()
                        } else {
                            pendingTap = true
                            ui.postDelayed(tapRun, 300)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    ui.removeCallbacks(longRun)
                    true
                }
                else -> false
            }
        }
    }

    private fun hubText(): CharSequence {
        val live = state == "READY" || state == "ON"
        val v = if (live) String.format(Locale.US, "%.1f", speed) else "--"
        val s = SpannableString(v + "\nkm/h")
        s.setSpan(RelativeSizeSpan(0.42f), v.length, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return s
    }

    private fun refreshUi() {
        val h = hubItem ?: return
        val live = state == "READY" || state == "ON"
        for ((n, x) in numItems) {
            val on = live && Math.abs(speed - n) < 0.5
            x.bg.setStroke(if (on) dp(3) else 0, Color.WHITE)
        }
        val ring = when (state) {
            "READY" -> Color.rgb(46, 204, 113)
            "ON" -> Color.rgb(52, 152, 219)
            "CONNECTING" -> Color.rgb(241, 196, 15)
            else -> Color.rgb(149, 165, 166)
        }
        h.bg.setStroke(dp(3), ring)
        h.view.text = hubText()
    }
}
