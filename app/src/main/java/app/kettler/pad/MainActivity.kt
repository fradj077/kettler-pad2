package app.kettler.pad

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.UUID

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var devices: LinearLayout
    private val seen = HashSet<String>()
    private var scanning = false
    private var scanCb: ScanCallback? = null
    private val ftms: UUID = UUID.fromString("00001826-0000-1000-8000-00805f9b34fb")
    private val env: UUID = UUID.fromString("0000181a-0000-1000-8000-00805f9b34fb")
    private var forTemp = false

    private val stateListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "state") ui.post { refreshStatus() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad, pad, pad)
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL

        val title = TextView(this)
        title.text = "Kettler Pad — لوحة السرعة العائمة"
        title.textSize = 20f
        root.addView(title)

        status = TextView(this)
        status.textSize = 15f
        status.setPadding(0, pad, 0, pad)
        root.addView(status)

        root.addView(makeButton("1) السماح بالعرض فوق التطبيقات") {
            if (hasOverlay()) {
                toast("الإذن ممنوح")
            } else {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
            }
        })
        root.addView(makeButton("2) السماح بالبلوتوث والإشعارات") {
            val m = missing()
            if (m.isEmpty()) toast("الأذونات ممنوحة") else requestPermissions(m.toTypedArray(), 1)
        })
        root.addView(makeButton("3) البحث عن المشاية واختيارها") { startScan(false) })
        root.addView(makeButton("3ب) اختيار حساس الحرارة (ESP32)") { startScan(true) })

        devices = LinearLayout(this)
        devices.orientation = LinearLayout.VERTICAL
        root.addView(devices)

        root.addView(makeButton("4) تشغيل اللوحة العائمة") { startPanel() })
        root.addView(makeButton("إيقاف اللوحة") {
            stopService(Intent(this, PadService::class.java))
            toast("أُوقفت اللوحة")
        })

        addSettings(root, pad)

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        prefs().registerOnSharedPreferenceChangeListener(stateListener)
        refreshStatus()
    }

    override fun onPause() {
        prefs().unregisterOnSharedPreferenceChangeListener(stateListener)
        super.onPause()
    }

    override fun onDestroy() {
        stopScan()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }

    // ---- settings: layout, size, opacity (the floating panel rebuilds itself live) ----
    private fun addSettings(root: LinearLayout, pad: Int) {
        val head = TextView(this)
        head.text = "إعدادات اللوحة"
        head.textSize = 18f
        head.setPadding(0, pad, 0, 0)
        root.addView(head)

        val kinds = listOf("ring", "frame", "grid")
        val names = listOf("حلقة (14 زراً حول دائرة)", "إطار مربع (14 زراً على الحافة)", "شبكة")
        val ids = HashMap<Int, String>()
        val group = RadioGroup(this)
        val current = prefs().getString("layout", "ring")
        for (i in kinds.indices) {
            val rb = RadioButton(this)
            rb.id = View.generateViewId()
            rb.text = names[i]
            ids[rb.id] = kinds[i]
            group.addView(rb)
            if (current == kinds[i]) group.check(rb.id)
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            val k = ids[checkedId]
            if (k != null) prefs().edit().putString("layout", k).apply()
        }
        root.addView(group)

        val sizeLabel = TextView(this)
        root.addView(sizeLabel)
        root.addView(makeSeek(sizeLabel, 28, 80, "size", 44) { v -> "حجم الأزرار: " + v })

        val alphaLabel = TextView(this)
        root.addView(alphaLabel)
        root.addView(makeSeek(alphaLabel, 40, 100, "alpha", 85) { v -> "عتمة الأزرار: " + v + "%" })

        val th = TextView(this)
        th.text = "حماية حرارة المحرك"
        th.textSize = 18f
        th.setPadding(0, pad, 0, 0)
        root.addView(th)
        val tl = TextView(this)
        tl.text = "درجة الخطر بالمئوية (0 = إيقاف الحماية):"
        root.addView(tl)
        val et = EditText(this)
        et.inputType = InputType.TYPE_CLASS_NUMBER
        et.setText(prefs().getInt("limit", 70).toString())
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val v = s.toString().toIntOrNull()
                if (v != null && v in 0..100) prefs().edit().putInt("limit", v).apply()
            }
        })
        root.addView(et)
        val tip = TextView(this)
        tip.text = "عند بلوغها: تتوقف المشاية تلقائيًا ويعمل اهتزاز وصوت إنذار، وتُمنع الأوامر حتى تنخفض الحرارة 3 درجات. المس الدائرة العائمة لإسكات الصوت."
        tip.textSize = 13f
        root.addView(tip)
    }

    private fun makeSeek(label: TextView, min: Int, max: Int, key: String, def: Int, text: (Int) -> String): SeekBar {
        val s = SeekBar(this)
        val start = prefs().getInt(key, def)
        s.max = max - min
        s.progress = start - min
        label.text = text(start)
        s.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = progress + min
                label.text = text(v)
                if (fromUser) prefs().edit().putInt(key, v).apply()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        return s
    }

    private fun makeButton(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.setOnClickListener { onClick() }
        return b
    }

    private fun toast(t: String) {
        Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
    }

    private fun prefs(): SharedPreferences = getSharedPreferences("pad", Context.MODE_PRIVATE)

    private fun hasOverlay(): Boolean = Settings.canDrawOverlays(this)

    private fun needed(): List<String> {
        val l = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            l.add(Manifest.permission.BLUETOOTH_SCAN)
            l.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            l.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            l.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return l
    }

    private fun missing(): List<String> =
        needed().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    private fun refreshStatus() {
        val sb = StringBuilder()
        sb.append(if (hasOverlay()) "✔ العرض فوق التطبيقات\n" else "✘ العرض فوق التطبيقات\n")
        sb.append(if (missing().isEmpty()) "✔ الأذونات\n" else "✘ الأذونات\n")
        val name = prefs().getString("name", null)
        if (name != null) sb.append("✔ المشاية: ").append(name).append("\n") else sb.append("✘ المشاية: لم تُختر\n")
        val tn = prefs().getString("tname", null)
        if (tn != null) sb.append("✔ حساس الحرارة: ").append(tn).append("\n") else sb.append("— حساس الحرارة: غير مختار (اختياري)\n")
        val st = prefs().getString("state", null)
        if (st != null) sb.append("الحالة: ").append(st)
        status.text = sb.toString()
    }

    private fun startScan(temp: Boolean) {
        if (missing().isNotEmpty()) {
            toast("امنح الأذونات أولاً (الزر 2)")
            return
        }
        val mgr = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = mgr.adapter
        if (adapter == null || !adapter.isEnabled) {
            toast("فعّل البلوتوث أولاً")
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            toast("تعذر بدء البحث")
            return
        }
        if (scanning) return
        forTemp = temp
        devices.removeAllViews()
        seen.clear()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val addr = result.device.address
                if (!seen.add(addr)) return
                val rec = result.scanRecord
                val name = rec?.deviceName ?: ""
                var isFtms = false
                val uuids = rec?.serviceUuids
                if (uuids != null) {
                    for (u in uuids) {
                        if (u.uuid == (if (forTemp) env else ftms)) isFtms = true
                    }
                }
                addDevice(addr, name, isFtms)
            }
        }
        scanCb = cb
        scanning = true
        toast("جارٍ البحث 10 ثوانٍ...")
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        ui.postDelayed({ stopScan() }, 10000)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            val mgr = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val cb = scanCb
            if (cb != null) mgr.adapter.bluetoothLeScanner.stopScan(cb)
        } catch (e: Exception) {
        }
    }

    private fun addDevice(addr: String, name: String, isFtms: Boolean) {
        val shown = if (name.isEmpty()) "(بلا اسم)" else name
        val b = Button(this)
        b.text = (if (isFtms) "★ " else "") + shown + "\n" + addr
        b.setOnClickListener {
            val nm = if (name.isEmpty()) addr else name
            if (forTemp) {
                prefs().edit().putString("taddr", addr).putString("tname", nm).apply()
            } else {
                prefs().edit().putString("addr", addr).putString("name", nm).apply()
            }
            stopScan()
            refreshStatus()
            toast(if (forTemp) "اختير حساس الحرارة" else "اختيرت المشاية")
        }
        devices.addView(b, if (isFtms) 0 else devices.childCount)
    }

    private fun startPanel() {
        if (!hasOverlay()) {
            toast("امنح إذن العرض فوق التطبيقات (الزر 1)")
            return
        }
        if (missing().isNotEmpty()) {
            toast("امنح الأذونات أولاً (الزر 2)")
            return
        }
        if (prefs().getString("addr", null) == null) {
            toast("اختر المشاية أولاً (الزر 3)")
            return
        }
        val i = Intent(this, PadService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        toast("اللوحة تعمل. افتح Kinomap")
    }
}
