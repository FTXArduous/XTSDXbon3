package com.xtsdx.virtualxbox

import android.content.Context
import android.content.SharedPreferences
import android.view.InputDevice

/** Saved settings and the list of attached controllers. */
object Config {
    // key is per physical unit, so identical devices keep separate settings and mappings.
    class Dev(val key: String, val vid: Int, val pid: Int, val name: String, val id: Int, val src: InputDevice, val label: String)

    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun mapKey(d: Dev) = d.key

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
        p.edit().putString("map.$key", encodeMap(m)).apply()
    }

    fun commitMap(p: SharedPreferences, key: String, m: Map<Int, Mapping>): Boolean =
        p.edit().putString("map.$key", encodeMap(m)).commit()

    private fun encodeMap(m: Map<Int, Mapping>) =
        m.entries.joinToString(";") { "${it.key}:${it.value.encode()}" }

    fun devices(): List<Dev> {
        val sorted = InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }
            .filter { Mapper.isController(it) }
            .sortedBy { it.id }
        val total = sorted.groupingBy { it.name }.eachCount()
        val seenName = HashMap<String, Int>()
        val seenKey = HashMap<String, Int>()
        return sorted.map {
            val num = (seenName[it.name] ?: 0) + 1
            seenName[it.name] = num
            val dup = (seenKey[it.descriptor] ?: 0) + 1
            seenKey[it.descriptor] = dup
            val key = if (dup == 1) it.descriptor else "${it.descriptor}#$dup"
            val label = if ((total[it.name] ?: 1) > 1) "${it.name} ($num)" else it.name
            Dev(key, it.vendorId, it.productId, it.name, it.id, it, label)
        }
    }
}
