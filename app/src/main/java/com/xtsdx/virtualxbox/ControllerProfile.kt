package com.xtsdx.virtualxbox

import android.view.KeyEvent
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/** Max analog magnitude per direction: gives 2 * 32256 + 1 discrete stick positions per axis. */
const val MAX_POINTS = 32256

enum class ControllerProfile(
    val label: String,
    val vendorId: Int,
    val productId: Int,
    private val names: Map<Int, String>,
) {
    XBOX_ONE(
        "Xbox One", 0x045E, 0x02EA,
        mapOf(
            KeyEvent.KEYCODE_BUTTON_A to "A", KeyEvent.KEYCODE_BUTTON_B to "B",
            KeyEvent.KEYCODE_BUTTON_X to "X", KeyEvent.KEYCODE_BUTTON_Y to "Y",
            KeyEvent.KEYCODE_BUTTON_L1 to "LB", KeyEvent.KEYCODE_BUTTON_R1 to "RB",
            KeyEvent.KEYCODE_BUTTON_THUMBL to "LS", KeyEvent.KEYCODE_BUTTON_THUMBR to "RS",
            KeyEvent.KEYCODE_BUTTON_START to "Menu", KeyEvent.KEYCODE_BUTTON_SELECT to "View",
            KeyEvent.KEYCODE_BUTTON_MODE to "Xbox",
        ),
    ),
    XBOX_360(
        "Xbox 360", 0x045E, 0x028E,
        mapOf(
            KeyEvent.KEYCODE_BUTTON_A to "A", KeyEvent.KEYCODE_BUTTON_B to "B",
            KeyEvent.KEYCODE_BUTTON_X to "X", KeyEvent.KEYCODE_BUTTON_Y to "Y",
            KeyEvent.KEYCODE_BUTTON_L1 to "LB", KeyEvent.KEYCODE_BUTTON_R1 to "RB",
            KeyEvent.KEYCODE_BUTTON_THUMBL to "LS", KeyEvent.KEYCODE_BUTTON_THUMBR to "RS",
            KeyEvent.KEYCODE_BUTTON_START to "Start", KeyEvent.KEYCODE_BUTTON_SELECT to "Back",
            KeyEvent.KEYCODE_BUTTON_MODE to "Guide",
        ),
    );

    fun buttonName(keyCode: Int): String? = names[keyCode]
}

/** Converts a raw axis value in [-1, 1] to an integer in [-MAX_POINTS, MAX_POINTS]. */
class AxisScaler(var deadzone: Float = 0.05f, var curve: Float = 1f) {
    fun scale(raw: Float): Int {
        val a = abs(raw).coerceIn(0f, 1f)
        if (a <= deadzone) return 0
        val n = ((a - deadzone) / (1f - deadzone)).toDouble().pow(curve.toDouble())
        val v = (n * MAX_POINTS).roundToInt().coerceIn(0, MAX_POINTS)
        return if (raw < 0) -v else v
    }

    /** Triggers: 0..MAX_POINTS. */
    fun scaleTrigger(raw: Float): Int = scale(raw.coerceIn(0f, 1f)).coerceAtLeast(0)
}
