package com.xtsdx.virtualxbox

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/** Max analog magnitude per direction: 2 * 32256 + 1 stick positions per axis. */
const val MAX_POINTS = 32256

class AxisScaler(@Volatile var deadzone: Float = 0.05f, @Volatile var curve: Float = 1f) {
    /** Raw [-1, 1] to [-MAX_POINTS, MAX_POINTS]. */
    fun scale(raw: Float): Int {
        val a = abs(raw).coerceIn(0f, 1f)
        if (a <= deadzone) return 0
        val n = ((a - deadzone) / (1f - deadzone)).toDouble().pow(curve.toDouble())
        val v = (n * MAX_POINTS).roundToInt().coerceIn(0, MAX_POINTS)
        return if (raw < 0) -v else v
    }

    fun scaleTrigger(raw: Float): Int = scale(raw.coerceIn(0f, 1f)).coerceAtLeast(0)
}

/** Latest state of one physical device. Axes: LX, LY, RX, RY, LT, RT. */
class DevState {
    val axes = IntArray(6)
    var buttons = 0
    var hatX = 0
    var hatY = 0
    var dpad = 0 // bits: 1 up, 2 down, 4 left, 8 right
    var tl2 = false
    var tr2 = false

    fun value(i: Int): Int = when (i) {
        4 -> maxOf(axes[4], if (tl2) MAX_POINTS else 0)
        5 -> maxOf(axes[5], if (tr2) MAX_POINTS else 0)
        else -> axes[i]
    }

    fun hx() = if (hatX != 0) hatX else ((dpad shr 3) and 1) - ((dpad shr 2) and 1)
    fun hy() = if (hatY != 0) hatY else ((dpad shr 1) and 1) - (dpad and 1)
}

/** Several devices combined into one virtual pad. */
class Merged(val axes: IntArray, val buttons: Int, val hx: Int, val hy: Int) {
    /** HID report body: buttons(2) hat(1) LX LY RX RY (int16) LT RT (int16). */
    fun bytes(): ByteArray {
        val b = ByteArray(15)
        b[0] = buttons.toByte()
        b[1] = (buttons shr 8).toByte()
        b[2] = HAT[(hy + 1) * 3 + (hx + 1)].toByte()
        for (i in 0 until 6) {
            b[3 + i * 2] = axes[i].toByte()
            b[4 + i * 2] = (axes[i] shr 8).toByte()
        }
        return b
    }

    /** DualShock 4 style body: LX LY RX RY (u8), hat + 14 buttons (3 bytes), L2 R2 (u8). */
    fun ds4(): ByteArray {
        fun stick(v: Int) = (v * 127 / MAX_POINTS + 128).coerceIn(0, 255)
        fun trig(v: Int) = (v.coerceAtLeast(0) * 255 / MAX_POINTS).coerceIn(0, 255)
        fun on(bit: Int) = (buttons shr bit) and 1
        val h = HAT[(hy + 1) * 3 + (hx + 1)]
        val hat = if (h == 0) 8 else h - 1
        // Square, Cross, Circle, Triangle, L1, R1, L2, R2, Share, Options, L3, R3, PS
        val btn = on(3) or (on(0) shl 1) or (on(1) shl 2) or (on(4) shl 3) or
            (on(6) shl 4) or (on(7) shl 5) or
            ((if (axes[4] > MAX_POINTS / 4) 1 else 0) shl 6) or ((if (axes[5] > MAX_POINTS / 4) 1 else 0) shl 7) or
            (on(10) shl 8) or (on(11) shl 9) or (on(13) shl 10) or (on(14) shl 11) or (on(12) shl 12)
        val word = hat or (btn shl 4)
        return byteArrayOf(
            stick(axes[0]).toByte(), stick(axes[1]).toByte(), stick(axes[2]).toByte(), stick(axes[3]).toByte(),
            word.toByte(), (word shr 8).toByte(), (word shr 16).toByte(),
            trig(axes[4]).toByte(), trig(axes[5]).toByte(),
        )
    }

    companion object {
        // Index (y+1)*3 + (x+1): 0 = neutral, 1..8 = N, NE, E, SE, S, SW, W, NW
        private val HAT = intArrayOf(8, 1, 2, 7, 0, 3, 6, 5, 4)
    }
}

object Outputs {
    val NAMES = listOf(
        "A", "B", "X", "Y", "LB", "RB", "Back / View", "Start / Menu", "Guide", "Left stick press", "Right stick press",
        "D-pad up", "D-pad down", "D-pad left", "D-pad right",
        "Left stick X", "Left stick Y", "Right stick X", "Right stick Y", "Left trigger", "Right trigger",
    )

    // HID report bit for outputs 0..10
    val BIT = intArrayOf(0, 1, 3, 4, 6, 7, 10, 11, 12, 13, 14)
    const val FIRST_DPAD = 11
    const val FIRST_AXIS = 15
}

/** One physical input (axis or key) assigned to one virtual output. */
class Mapping(val axis: Boolean, val code: Int, val sign: Int, val invert: Boolean) {
    fun text(): String {
        val src = if (axis) "Axis ${MotionEvent.axisToString(code).removePrefix("AXIS_")} (${if (sign < 0) "-" else "+"})"
        else KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
        return if (invert) "$src, inverted" else src
    }

    fun encode() = "${if (axis) 1 else 0},$code,$sign,${if (invert) 1 else 0}"

    companion object {
        fun decode(s: String): Mapping? {
            val f = s.split(',')
            if (f.size != 4) return null
            return Mapping(f[0] == "1", f[1].toIntOrNull() ?: return null, f[2].toIntOrNull() ?: return null, f[3] == "1")
        }
    }
}

/** Latest raw values of one physical device, independent of any mapping. */
class Raw(d: InputDevice) {
    val axes: IntArray = d.motionRanges.map { it.axis }.filter { it in 0 until 48 }.toIntArray()
    val min = FloatArray(48)
    val vals = FloatArray(48)
    val keys = BooleanArray(512)

    init { for (r in d.motionRanges) if (r.axis in 0 until 48) min[r.axis] = r.min }

    fun update(e: MotionEvent) { for (a in axes) vals[a] = e.getAxisValue(a) }
    fun key(code: Int, down: Boolean) { if (code in keys.indices) keys[code] = down }
}

object Mapper {
    // HID button bit positions, laid out so Linux/Android hosts map them to A,B,X,Y,LB,RB,Back,Start,Guide,LS,RS.
    private val KEY_BIT = mapOf(
        KeyEvent.KEYCODE_BUTTON_A to 0, KeyEvent.KEYCODE_BUTTON_B to 1,
        KeyEvent.KEYCODE_BUTTON_X to 3, KeyEvent.KEYCODE_BUTTON_Y to 4,
        KeyEvent.KEYCODE_BUTTON_L1 to 6, KeyEvent.KEYCODE_BUTTON_R1 to 7,
        KeyEvent.KEYCODE_BUTTON_SELECT to 10, KeyEvent.KEYCODE_BUTTON_START to 11,
        KeyEvent.KEYCODE_BUTTON_MODE to 12,
        KeyEvent.KEYCODE_BUTTON_THUMBL to 13, KeyEvent.KEYCODE_BUTTON_THUMBR to 14,
    )

    // BUTTON_1..BUTTON_11 on non-Xbox pads and flight sticks, in Xbox order.
    private val GENERIC = intArrayOf(0, 1, 3, 4, 6, 7, 10, 11, 13, 14, 12)

    private fun keyBit(code: Int): Int =
        KEY_BIT[code] ?: if (code in KeyEvent.KEYCODE_BUTTON_1..KeyEvent.KEYCODE_BUTTON_11) GENERIC[code - KeyEvent.KEYCODE_BUTTON_1] else -1

    /** Drives the virtual pad purely from the user's assignments for this device. */
    fun applyCustom(raw: Raw, m: Map<Int, Mapping>, sc: AxisScaler, s: DevState) {
        s.buttons = 0; s.dpad = 0; s.hatX = 0; s.hatY = 0; s.tl2 = false; s.tr2 = false
        s.axes.fill(0)
        for ((out, mp) in m) {
            val v = if (mp.axis) raw.vals[mp.code] else if (raw.keys[mp.code]) 1f else 0f
            val on = if (mp.axis) v * mp.sign > 0.5f else v > 0.5f
            if (out < Outputs.FIRST_DPAD) {
                if (on) s.buttons = s.buttons or (1 shl Outputs.BIT[out])
            } else if (out < Outputs.FIRST_AXIS) {
                if (on) s.dpad = s.dpad or (1 shl (out - Outputs.FIRST_DPAD))
            } else {
                val i = out - Outputs.FIRST_AXIS
                if (i < 4) {
                    s.axes[i] = sc.scale(if (mp.invert) -v else v)
                } else {
                    val t = if (mp.axis && raw.min[mp.code] < 0f) (v + 1f) / 2f else v
                    s.axes[i] = sc.scaleTrigger(if (mp.invert) 1f - t else t)
                }
            }
        }
    }

    fun applyMotion(e: MotionEvent, s: DevState, sc: AxisScaler) {
        val d = e.device ?: return
        fun has(a: Int) = d.getMotionRange(a) != null
        fun v(a: Int) = e.getAxisValue(a)
        fun trigger(axis: Int): Float {
            val range = d.getMotionRange(axis) ?: return 0f
            val span = range.max - range.min
            return if (span <= 0f) 0f else ((v(axis) - range.min) / span).coerceIn(0f, 1f)
        }

        s.axes[0] = sc.scale(v(MotionEvent.AXIS_X))
        s.axes[1] = sc.scale(v(MotionEvent.AXIS_Y))
        val rx: Float
        val ry: Float
        if (has(MotionEvent.AXIS_Z) && has(MotionEvent.AXIS_RZ)) {
            rx = v(MotionEvent.AXIS_Z); ry = v(MotionEvent.AXIS_RZ)
        } else if (has(MotionEvent.AXIS_RX) && has(MotionEvent.AXIS_RY)) {
            rx = v(MotionEvent.AXIS_RX); ry = v(MotionEvent.AXIS_RY)
        } else {
            // Flight sticks: twist/rudder and throttle act as the right stick.
            rx = v(MotionEvent.AXIS_RZ).takeIf { it != 0f } ?: v(MotionEvent.AXIS_RUDDER)
            ry = v(MotionEvent.AXIS_THROTTLE)
        }
        s.axes[2] = sc.scale(rx)
        s.axes[3] = sc.scale(ry)
        s.axes[4] = sc.scaleTrigger(maxOf(trigger(MotionEvent.AXIS_LTRIGGER), trigger(MotionEvent.AXIS_BRAKE)))
        s.axes[5] = sc.scaleTrigger(maxOf(trigger(MotionEvent.AXIS_RTRIGGER), trigger(MotionEvent.AXIS_GAS)))
        s.hatX = v(MotionEvent.AXIS_HAT_X).roundToInt().coerceIn(-1, 1)
        s.hatY = v(MotionEvent.AXIS_HAT_Y).roundToInt().coerceIn(-1, 1)
    }

    /** Returns true if the key was a controller input. */
    fun applyKey(e: KeyEvent, s: DevState): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        val dpadBit = when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> 1
            KeyEvent.KEYCODE_DPAD_DOWN -> 2
            KeyEvent.KEYCODE_DPAD_LEFT -> 4
            KeyEvent.KEYCODE_DPAD_RIGHT -> 8
            else -> 0
        }
        if (dpadBit != 0) { s.dpad = if (down) s.dpad or dpadBit else s.dpad and dpadBit.inv(); return true }
        when (e.keyCode) {
            KeyEvent.KEYCODE_BUTTON_L2 -> { s.tl2 = down; return true }
            KeyEvent.KEYCODE_BUTTON_R2 -> { s.tr2 = down; return true }
        }
        val bit = keyBit(e.keyCode)
        if (bit < 0) return false
        s.buttons = if (down) s.buttons or (1 shl bit) else s.buttons and (1 shl bit).inv()
        return true
    }

    fun merge(states: List<DevState>): Merged {
        val ax = IntArray(6)
        var buttons = 0
        var hx = 0
        var hy = 0
        for (s in states) {
            for (i in 0 until 6) {
                val v = s.value(i)
                if (abs(v) > abs(ax[i])) ax[i] = v
            }
            buttons = buttons or s.buttons
            if (s.hx() != 0) hx = s.hx()
            if (s.hy() != 0) hy = s.hy()
        }
        return Merged(ax, buttons, hx, hy)
    }

    fun isController(d: InputDevice) = isControllerSource(d.sources)

    fun isControllerSource(source: Int) =
        source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
}
