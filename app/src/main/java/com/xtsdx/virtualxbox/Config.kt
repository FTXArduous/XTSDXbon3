package com.xtsdx.virtualxbox

import android.content.Context
import android.content.SharedPreferences
import android.view.InputDevice

/** Saved settings plus the translation of them into root-helper commands. */
object Config {
    class Dev(val key: String, val vid: Int, val pid: Int, val name: String, val id: Int, val src: InputDevice)

    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    fun one(p: SharedPreferences) = p.getBoolean("one", true)
    fun padOn(p: SharedPreferences, i: Int) = p.getBoolean("pad$i", i == 0)

    fun devices(): List<Dev> {
        val counts = HashMap<String, Int>()
        return InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }
            .filter {
                (it.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                    it.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK) &&
                    !it.name.startsWith("XTSDX")
            }
            .sortedBy { it.id }
            .map {
                val base = "%04x:%04x".format(it.vendorId, it.productId)
                val n = counts.getOrDefault(base, 0)
                counts[base] = n + 1
                Dev("$base:$n", it.vendorId, it.productId, it.name, it.id, it)
            }
    }

    fun push(ctx: Context) {
        if (!Bridge.running) return
        val p = prefs(ctx)
        Bridge.send("S ${p.getInt("dz", 5) / 100f} ${0.5f + p.getInt("curve", 50) / 50f}")
        Bridge.send("BEGIN")
        for (i in 0 until 4) if (padOn(p, i)) Bridge.send("P $i ${if (one(p)) 1 else 0}")
        for (d in devices()) {
            val slot = p.getInt("slot.${d.key}", 0)
            if (p.getBoolean("on.${d.key}", false) && padOn(p, slot)) {
                Bridge.send("M $slot ${d.vid} ${d.pid} ${d.key.substringAfterLast(':')}")
            }
        }
        Bridge.send("END")
    }
}
