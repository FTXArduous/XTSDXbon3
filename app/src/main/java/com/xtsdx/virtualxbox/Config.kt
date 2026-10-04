package com.xtsdx.virtualxbox

import android.content.Context
import android.content.SharedPreferences
import android.view.InputDevice

/** Saved settings and the list of attached controllers. */
object Config {
    class Dev(val key: String, val vid: Int, val pid: Int, val name: String, val id: Int, val src: InputDevice)

    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun devices(): List<Dev> {
        val counts = HashMap<String, Int>()
        return InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }
            .filter { Mapper.isController(it) }
            .sortedBy { it.id }
            .map {
                val base = "%04x:%04x".format(it.vendorId, it.productId)
                val n = counts.getOrDefault(base, 0)
                counts[base] = n + 1
                Dev("$base:$n", it.vendorId, it.productId, it.name, it.id, it)
            }
    }
}
