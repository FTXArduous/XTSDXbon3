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

    companion object {
        // Index (y+1)*3 + (x+1): 0 = neutral, 1..8 = N, NE, E, SE, S, SW, W, NW
        private val HAT = intArrayOf(8, 1, 2, 7, 0, 3, 6, 5, 4)
    }
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

    fun applyMotion(e: MotionEvent, s: DevState, sc: AxisScaler) {
        val d = e.device ?: return
        fun has(a: Int) = d.getMotionRange(a) != null
        fun v(a: Int) = e.getAxisValue(a)

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
        s.axes[4] = sc.scaleTrigger(maxOf(v(MotionEvent.AXIS_LTRIGGER), v(MotionEvent.AXIS_BRAKE)))
        s.axes[5] = sc.scaleTrigger(maxOf(v(MotionEvent.AXIS_RTRIGGER), v(MotionEvent.AXIS_GAS)))
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

    fun isController(d: InputDevice) =
        d.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            d.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
}
