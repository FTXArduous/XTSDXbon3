package com.xtsdx.virtualxbox

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity(), InputManager.InputDeviceListener {

    private lateinit var prefs: SharedPreferences
    private lateinit var im: InputManager
    private lateinit var hid: Hid
    private lateinit var statusView: TextView
    private lateinit var liveView: TextView
    private lateinit var deviceBox: LinearLayout
    private lateinit var pairedBox: LinearLayout
    private lateinit var infoView: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val scaler = AxisScaler()
    private var hidStarted = false
    private lateinit var mainView: ScrollView
    private lateinit var page: MappingPage
    private var showingMapping = false
    private val rawById = HashMap<Int, Raw>()
    private var customById = HashMap<Int, Map<Int, Mapping>>()
    // Event path uses only these in-memory structures.
    private var route = HashMap<Int, Int>() // device id -> virtual pad slot
    private val stateById = HashMap<Int, DevState>()
    private val slotStates = Array(4) { ArrayList<DevState>() }

    private val tick = object : Runnable {
        override fun run() {
            statusView.text = "Bluetooth: ${hid.status}"
            val sb = StringBuilder()
            for (i in 0 until 4) {
                if (!padOn(i)) continue
                val m = Mapper.merge(slotStates[i])
                val a = m.axes
                sb.append("P${i + 1} LS %6d %6d  RS %6d %6d  LT %5d RT %5d  hat %d,%d  btn %04X\n".format(
                    a[0], a[1], a[2], a[3], a[4], a[5], m.hx, m.hy, m.buttons))
            }
            liveView.text = sb
            if (showingMapping) page.updateLive(rawById[page.devId])
            ui.postDelayed(this, 200)
        }
    }

    private val one get() = prefs.getBoolean("one", true)
    private fun padOn(i: Int) = prefs.getBoolean("pad$i", i == 0)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun label(t: String) = TextView(this).apply { text = t; setPadding(0, dp(12), 0, 0) }

    private fun btPermitted() = Build.VERSION.SDK_INT < 31 ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        im = getSystemService(Context.INPUT_SERVICE) as InputManager
        hid = Hid(applicationContext)
        scaler.deadzone = prefs.getInt("dz", 5) / 100f
        scaler.curve = 0.5f + prefs.getInt("curve", 50) / 50f

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)) }
        statusView = TextView(this)
        liveView = TextView(this).apply { typeface = android.graphics.Typeface.MONOSPACE; textSize = 11f }
        root.addView(statusView)
        root.addView(liveView)

        root.addView(Button(this).apply {
            text = "Make discoverable (pair from the other device)"
            setOnClickListener {
                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300))
            }
        })
        root.addView(Button(this).apply { text = "Disconnect"; setOnClickListener { hid.disconnect() } })
        root.addView(Button(this).apply {
            text = "Button mapping (Thrustmaster, flight sticks, any controller)"
            setOnClickListener { page.refresh(); showingMapping = true; setContentView(page.view) }
        })
        root.addView(label("Paired devices (tap to connect)"))
        pairedBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(pairedBox)

        root.addView(Switch(this).apply {
            text = "Layout: ON = Xbox One (Android standard), OFF = Xbox 360 (XInput style)"
            isChecked = one
            setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("one", c).apply(); rebuildRouting() }
        })

        root.addView(label("Virtual controllers (up to 4)"))
        for (i in 0 until 4) {
            root.addView(Switch(this).apply {
                text = "Virtual controller ${i + 1}"
                isChecked = padOn(i)
                setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("pad$i", c).apply(); rebuildRouting() }
            })
        }

        root.addView(label("Stick curve (left = fine, right = aggressive)"))
        root.addView(SeekBar(this).apply {
            max = 100; progress = prefs.getInt("curve", 50)
            setOnSeekBarChangeListener(listener { prefs.edit().putInt("curve", it).apply(); scaler.curve = 0.5f + it / 50f })
        })
        root.addView(label("Deadzone"))
        root.addView(SeekBar(this).apply {
            max = 50; progress = prefs.getInt("dz", 5)
            setOnSeekBarChangeListener(listener { prefs.edit().putInt("dz", it).apply(); scaler.deadzone = it / 100f })
        })

        root.addView(label("Physical devices (switch = use it, button = which virtual controller)"))
        deviceBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(deviceBox)
        infoView = TextView(this).apply { typeface = android.graphics.Typeface.MONOSPACE; textSize = 11f; setPadding(0, dp(12), 0, 0) }
        root.addView(infoView)

        mainView = ScrollView(this).apply { addView(root) }
        page = MappingPage(this, prefs, { rawById[it] }, { showMain() }, { rebuildRouting() })
        setContentView(mainView)

        if (btPermitted()) startHid() else requestPermissions(
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE), 1)
        refreshDevices()
        rebuildRouting()
    }

    private fun startHid() {
        if (hidStarted) return
        hidStarted = true
        hid.start()
        rebuildRouting()
        refreshPaired()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (btPermitted()) startHid() else hid.status = "Bluetooth permission denied"
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Deliver controller motion as it arrives instead of once per frame.
        if (hasFocus && Build.VERSION.SDK_INT >= 31) window.decorView.requestUnbufferedDispatch(InputDevice.SOURCE_JOYSTICK)
    }

    override fun onStart() {
        super.onStart()
        im.registerInputDeviceListener(this, null)
        ui.post(tick)
        refreshDevices()
        rebuildRouting()
        if (hidStarted) refreshPaired()
    }

    override fun onStop() {
        super.onStop()
        im.unregisterInputDeviceListener(this)
        ui.removeCallbacks(tick)
    }

    override fun onDestroy() {
        super.onDestroy()
        hid.stop()
    }

    override fun onInputDeviceAdded(id: Int) { refreshDevices(); rebuildRouting() }
    override fun onInputDeviceRemoved(id: Int) { refreshDevices(); rebuildRouting() }
    override fun onInputDeviceChanged(id: Int) {}

    private fun showMain() {
        showingMapping = false
        page.cancel()
        setContentView(mainView)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (showingMapping) showMain() else super.onBackPressed()
    }

    private fun rebuildRouting() {
        val newRoute = HashMap<Int, Int>()
        val newCustom = HashMap<Int, Map<Int, Mapping>>()
        val all = Config.devices()
        for (d in all) {
            val slot = prefs.getInt("slot.${d.key}", 0)
            if (prefs.getBoolean("on.${d.key}", false) && padOn(slot)) {
                newRoute[d.id] = slot
                val m = Config.loadMap(prefs, Config.mapKey(d))
                if (m.isNotEmpty()) newCustom[d.id] = m
            }
        }
        route = newRoute
        customById = newCustom
        rawById.keys.retainAll(all.map { it.id }.toSet())
        stateById.keys.retainAll(newRoute.keys)
        for (id in newRoute.keys) stateById.getOrPut(id) { DevState() }
        for (l in slotStates) l.clear()
        for ((id, slot) in newRoute) slotStates[slot].add(stateById.getValue(id))
        hid.configure((0 until 4).filter { padOn(it) }, one)
        for (i in 0 until 4) if (padOn(i)) hid.send(i, Mapper.merge(slotStates[i]).bytes())
    }

    private fun flush(slot: Int) = hid.send(slot, Mapper.merge(slotStates[slot]).bytes())

    private fun rawFor(d: InputDevice?, id: Int): Raw? {
        if (d == null) return null
        return rawById.getOrPut(id) { Raw(d) }
    }

    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.device?.let(Mapper::isController) == true && e.action == MotionEvent.ACTION_MOVE) {
            val raw = rawFor(e.device, e.deviceId)
            if (raw != null) {
                raw.update(e)
                if (showingMapping && page.learningOut >= 0 && e.deviceId == page.devId) {
                    page.offerAxis(raw)
                    return true
                }
                val slot = route[e.deviceId]
                val s = stateById[e.deviceId]
                if (slot != null && s != null) {
                    val custom = customById[e.deviceId]
                    if (custom != null) Mapper.applyCustom(raw, custom, scaler, s) else Mapper.applyMotion(e, s, scaler)
                    flush(slot)
                    return true
                }
            }
        }
        return super.dispatchGenericMotionEvent(e)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val raw = if (e.device?.let(Mapper::isController) == true) rawFor(e.device, e.deviceId) else null
        if (raw != null) {
            if (e.action == KeyEvent.ACTION_DOWN) raw.key(e.keyCode, true) else if (e.action == KeyEvent.ACTION_UP) raw.key(e.keyCode, false)
            if (showingMapping && page.learningOut >= 0 && e.deviceId == page.devId) {
                if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) page.offerKey(e.keyCode)
                return true
            }
            val slot = route[e.deviceId]
            val s = stateById[e.deviceId]
            if (slot != null && s != null) {
                val custom = customById[e.deviceId]
                val handled = if (custom != null) { Mapper.applyCustom(raw, custom, scaler, s); true } else Mapper.applyKey(e, s)
                if (handled) {
                    flush(slot)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(e)
    }

    private fun refreshPaired() {
        pairedBox.removeAllViews()
        if (!btPermitted()) return
        val ad = getSystemService(BluetoothManager::class.java)?.adapter ?: return
        val bonded = ad.bondedDevices.orEmpty()
        if (bonded.isEmpty()) pairedBox.addView(TextView(this).apply { text = "None yet. Use the discoverable button, then pair from the other device." })
        for (d in bonded) {
            pairedBox.addView(Button(this).apply {
                text = d.name ?: d.address
                setOnClickListener { hid.connect(d) }
            })
        }
    }

    private fun refreshDevices() {
        deviceBox.removeAllViews()
        val list = Config.devices()
        if (list.isEmpty()) deviceBox.addView(TextView(this).apply { text = "No controllers detected. Connect one by Bluetooth or USB/OTG." })
        val info = StringBuilder()
        for (d in list) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(Switch(this).apply {
                text = "${d.label}\n%04X:%04X  id=${d.id}".format(d.vid, d.pid)
                isChecked = prefs.getBoolean("on.${d.key}", false)
                setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("on.${d.key}", c).apply(); rebuildRouting() }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Button(this).apply {
                text = "P${prefs.getInt("slot.${d.key}", 0) + 1}"
                setOnClickListener {
                    val s = (prefs.getInt("slot.${d.key}", 0) + 1) % 4
                    prefs.edit().putInt("slot.${d.key}", s).apply()
                    text = "P${s + 1}"
                    rebuildRouting()
                }
            })
            deviceBox.addView(row)

            info.append("${d.label}  VID=%04X PID=%04X  descriptor=${d.src.descriptor}\n".format(d.vid, d.pid))
            for (r in d.src.motionRanges) {
                info.append("  ${MotionEvent.axisToString(r.axis)} min=${r.min} max=${r.max} flat=${r.flat}\n")
            }
        }
        infoView.text = info
    }

    private fun listener(f: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) f(p) }
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    }
}
