package com.xtsdx.virtualxbox

import android.content.Context
import android.content.SharedPreferences
import android.view.InputDevice

/** Saved settings and the list of attached controllers. */
object Config {
    class Dev(val key: String, val vid: Int, val pid: Int, val name: String, val id: Int, val src: InputDevice)

    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    // Mappings are shared by every unit of the same model.
    fun mapKey(d: Dev) = "%04x:%04x".format(d.vid, d.pid)

    fun loadMap(p: SharedPreferences, key: String): HashMap<Int, Mapping> {
        val m = HashMap<Int, Mapping>()
        for (e in (p.getString("map.$key", "") ?: "").split(';')) {
            val i = e.indexOf(':')
            if (i <= 0) continue
            val out = e.substring(0, i).toIntOrNull() ?: continue
            m[out] = Mapping.decode(e.substring(i + 1)) ?: continue
        }
        return m
    }

    fun saveMap(p: SharedPreferences, key: String, m: Map<Int, Mapping>) {
        p.edit().putString("map.$key", m.entries.joinToString(";") { "${it.key}:${it.value.encode()}" }).apply()
    }

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
