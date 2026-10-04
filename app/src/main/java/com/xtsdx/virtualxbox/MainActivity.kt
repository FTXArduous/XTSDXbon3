package com.xtsdx.virtualxbox

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.input.InputManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity(), InputManager.InputDeviceListener {

    private lateinit var prefs: SharedPreferences
    private lateinit var im: InputManager
    private lateinit var statusView: TextView
    private lateinit var startBtn: Button
    private lateinit var deviceBox: LinearLayout
    private lateinit var infoView: TextView
    private val ui = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            statusView.text = "Status: ${Bridge.status}"
            startBtn.text = if (Bridge.running) "Stop bridge" else "Start bridge (needs root)"
            ui.postDelayed(this, 1000)
        }
    }

    private val one get() = Config.one(prefs)
    private fun padOn(i: Int) = Config.padOn(prefs, i)
    private fun pushConfig() = Config.push(this)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        im = getSystemService(Context.INPUT_SERVICE) as InputManager
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)) }
        statusView = TextView(this)
        startBtn = Button(this).apply {
            setOnClickListener {
                if (Bridge.running) {
                    stopService(Intent(this@MainActivity, BridgeService::class.java))
                } else {
                    startForegroundService(Intent(this@MainActivity, BridgeService::class.java))
                }
            }
        }
        root.addView(statusView)
        root.addView(startBtn)

        root.addView(Switch(this).apply {
            text = "Start automatically on boot"
            isChecked = prefs.getBoolean("boot", false)
            setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("boot", c).apply() }
        })
        root.addView(Button(this).apply {
            text = "Allow unrestricted battery use (keeps it live)"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        })

        root.addView(Switch(this).apply {
            text = "Emulate: ON = Xbox One, OFF = Xbox 360"
            isChecked = one
            setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("one", c).apply(); pushConfig() }
        })

        root.addView(label("Virtual controllers (up to 4)"))
        for (i in 0 until 4) {
            root.addView(Switch(this).apply {
                text = "Virtual controller ${i + 1}"
                isChecked = padOn(i)
                setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("pad$i", c).apply(); pushConfig() }
            })
        }

        root.addView(label("Stick curve (left = fine, right = aggressive)"))
        root.addView(SeekBar(this).apply {
            max = 100; progress = prefs.getInt("curve", 50)
            setOnSeekBarChangeListener(listener { prefs.edit().putInt("curve", it).apply(); pushConfig() })
        })
        root.addView(label("Deadzone"))
        root.addView(SeekBar(this).apply {
            max = 50; progress = prefs.getInt("dz", 5)
            setOnSeekBarChangeListener(listener { prefs.edit().putInt("dz", it).apply(); pushConfig() })
        })

        root.addView(label("Physical devices (switch = use it, button = which virtual controller)"))
        deviceBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(deviceBox)
        infoView = TextView(this).apply { typeface = android.graphics.Typeface.MONOSPACE; textSize = 11f; setPadding(0, dp(12), 0, 0) }
        root.addView(infoView)

        setContentView(ScrollView(this).apply { addView(root) })
        refreshDevices()
    }

    override fun onStart() {
        super.onStart()
        im.registerInputDeviceListener(this, null)
        ui.post(tick)
        refreshDevices()
    }

    override fun onStop() {
        super.onStop()
        im.unregisterInputDeviceListener(this)
        ui.removeCallbacks(tick)
    }

    override fun onInputDeviceAdded(id: Int) = refreshDevices()
    override fun onInputDeviceRemoved(id: Int) = refreshDevices()
    override fun onInputDeviceChanged(id: Int) {}

    private fun label(t: String) = TextView(this).apply { text = t; setPadding(0, dp(12), 0, 0) }

    private fun refreshDevices() {
        deviceBox.removeAllViews()
        val list = Config.devices()
        if (list.isEmpty()) deviceBox.addView(TextView(this).apply { text = "No controllers detected. Plug one in via USB/OTG." })
        val info = StringBuilder()
        for (d in list) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(Switch(this).apply {
                text = "${d.name}\n%04X:%04X  id=${d.id}".format(d.vid, d.pid)
                isChecked = prefs.getBoolean("on.${d.key}", false)
                setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("on.${d.key}", c).apply(); pushConfig() }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Button(this).apply {
                text = "P${prefs.getInt("slot.${d.key}", 0) + 1}"
                setOnClickListener {
                    val s = (prefs.getInt("slot.${d.key}", 0) + 1) % 4
                    prefs.edit().putInt("slot.${d.key}", s).apply()
                    text = "P${s + 1}"
                    pushConfig()
                }
            })
            deviceBox.addView(row)

            info.append("${d.name}  VID=%04X PID=%04X  descriptor=${d.src.descriptor}\n".format(d.vid, d.pid))
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
