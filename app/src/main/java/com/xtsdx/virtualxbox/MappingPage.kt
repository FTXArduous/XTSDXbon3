package com.xtsdx.virtualxbox

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import kotlin.math.abs

/** Lets the user assign any axis or button of a connected device to any virtual output. */
class MappingPage(
    private val ctx: Context,
    private val prefs: SharedPreferences,
    private val rawOf: (Int) -> Raw?,
    private val onClose: () -> Unit,
    private val onChanged: () -> Unit,
) {
    var learningOut = -1
        private set
    var devId = -1
        private set

    private var typeKey = ""
    private var map = HashMap<Int, Mapping>()
    private var baseline: FloatArray? = null
    private var devs: List<Config.Dev> = emptyList()
    private var updating = false

    private val spinner = Spinner(ctx)
    private val status = TextView(ctx)
    private val live = TextView(ctx).apply { typeface = Typeface.MONOSPACE; textSize = 11f }
    private val sourceViews = HashMap<Int, TextView>()
    private val invertBoxes = HashMap<Int, CheckBox>()
    val view: ScrollView

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    init {
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)) }
        root.addView(Button(ctx).apply { text = "Back"; setOnClickListener { onClose() } })
        root.addView(TextView(ctx).apply { text = "Button mapping"; textSize = 20f })
        root.addView(TextView(ctx).apply {
            text = "Pick a device, tap Map next to an output, then move or press the input you want on that device. " +
                "Once a device has any mapping, only mapped inputs are sent."
        })
        root.addView(spinner)
        root.addView(status)
        root.addView(Button(ctx).apply { text = "Cancel mapping"; setOnClickListener { cancel() } })
        root.addView(live)

        for (out in Outputs.NAMES.indices) {
            if (out == 0) root.addView(header("Buttons"))
            if (out == Outputs.FIRST_DPAD) root.addView(header("D-pad"))
            if (out == Outputs.FIRST_AXIS) root.addView(header("Sticks and triggers"))
            root.addView(TextView(ctx).apply { text = Outputs.NAMES[out]; setPadding(0, dp(8), 0, 0); setTypeface(null, Typeface.BOLD) })
            val src = TextView(ctx)
            sourceViews[out] = src
            root.addView(src)
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(Button(ctx).apply { text = "Map"; setOnClickListener { startLearn(out) } })
            row.addView(Button(ctx).apply {
                text = "Clear"
                setOnClickListener { map.remove(out); save(); updateRows() }
            })
            if (out >= Outputs.FIRST_AXIS) {
                val box = CheckBox(ctx).apply {
                    text = "Invert"
                    setOnCheckedChangeListener { _, c ->
                        if (updating) return@setOnCheckedChangeListener
                        map[out]?.let { map[out] = Mapping(it.axis, it.code, it.sign, c); save(); updateRows() }
                    }
                }
                invertBoxes[out] = box
                row.addView(box)
            }
            root.addView(row)
        }
        root.addView(Button(ctx).apply {
            text = "Clear all mappings for this device"
            setOnClickListener { map.clear(); save(); updateRows() }
        })
        view = ScrollView(ctx).apply { addView(root) }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { devs.getOrNull(pos)?.let { select(it) } }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun header(t: String) = TextView(ctx).apply { text = t; textSize = 16f; setPadding(0, dp(16), 0, 0) }

    fun refresh() {
        devs = Config.devices()
        spinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
            devs.map { "${it.name}  %04X:%04X  id=${it.id}".format(it.vid, it.pid) })
        val idx = devs.indexOfFirst { it.id == devId }.takeIf { it >= 0 } ?: 0
        if (devs.isEmpty()) {
            devId = -1
            status.text = "No controllers detected. Plug one in."
            map.clear()
            updateRows()
        } else {
            spinner.setSelection(idx)
            select(devs[idx])
        }
    }

    private fun select(d: Config.Dev) {
        devId = d.id
        typeKey = Config.mapKey(d)
        map = Config.loadMap(prefs, typeKey)
        learningOut = -1
        baseline = null
        status.text = "Mapping ${d.name}"
        updateRows()
    }

    private fun save() {
        Config.saveMap(prefs, typeKey, map)
        onChanged()
    }

    private fun updateRows() {
        updating = true
        for (out in Outputs.NAMES.indices) {
            val m = map[out]
            sourceViews[out]?.text = m?.text() ?: "not mapped"
            invertBoxes[out]?.apply { isChecked = m?.invert == true; isEnabled = m != null }
        }
        updating = false
    }

    private fun startLearn(out: Int) {
        if (devId < 0) return
        learningOut = out
        baseline = rawOf(devId)?.vals?.clone()
        status.text = "Move or press the input for \"${Outputs.NAMES[out]}\" on the selected device..."
    }

    fun cancel() {
        learningOut = -1
        baseline = null
    }

    fun offerKey(code: Int) = capture(Mapping(false, code, 1, false))

    fun offerAxis(raw: Raw) {
        val b = baseline ?: run { baseline = raw.vals.clone(); return }
        for (a in raw.axes) {
            val d = raw.vals[a] - b[a]
            if (abs(d) > 0.6f) { capture(Mapping(true, a, if (d > 0) 1 else -1, false)); return }
        }
    }

    private fun capture(m: Mapping) {
        val out = learningOut
        if (out < 0) return
        val inv = m.axis && out >= Outputs.FIRST_AXIS && map[out]?.invert == true
        map[out] = Mapping(m.axis, m.code, m.sign, inv)
        learningOut = -1
        baseline = null
        status.text = "Mapped \"${Outputs.NAMES[out]}\" to ${m.text()}"
        save()
        updateRows()
    }

    /** Shows every axis and pressed button of the selected device so unknown controls can be identified. */
    fun updateLive(raw: Raw?) {
        if (raw == null) { live.text = "Move or press something on the device to see its inputs here."; return }
        val sb = StringBuilder()
        for (a in raw.axes) sb.append("%s=%.2f  ".format(android.view.MotionEvent.axisToString(a).removePrefix("AXIS_"), raw.vals[a]))
        sb.append("\nPressed: ")
        for (k in raw.keys.indices) if (raw.keys[k]) sb.append(android.view.KeyEvent.keyCodeToString(k).removePrefix("KEYCODE_")).append(' ')
        live.text = sb
    }
}
