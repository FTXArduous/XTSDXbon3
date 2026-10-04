package com.xtsdx.virtualxbox

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.system.exitProcess

/** Runs as root via app_process: reads physical evdev nodes and drives uinput Xbox pads. */
object RootServer {
    private const val EV_SYN = 0
    private const val EV_KEY = 1
    private const val EV_ABS = 3

    // Output axis order: LX, LY, RX, RY, LT, RT, HatX, HatY
    private val ABS_OUT = intArrayOf(0, 1, 3, 4, 2, 5, 0x10, 0x11)
    private val BTN_OUT = intArrayOf(0x130, 0x131, 0x133, 0x134, 0x136, 0x137, 0x13a, 0x13b, 0x13c, 0x13d, 0x13e)

    private val scaler = AxisScaler()

    private class Want(val slot: Int, val vid: Int, val pid: Int, val n: Int)
    private class Dev(val path: String, val name: String, val vid: Int, val pid: Int)

    private class Pad(val slot: Int, val one: Boolean) {
        val sources = CopyOnWriteArrayList<Source>()
        private val lastAxes = IntArray(8)
        private var lastBtn = 0
        val fd: Int

        init {
            val p = if (one) ControllerProfile.XBOX_ONE else ControllerProfile.XBOX_360
            fd = Native.uinputCreate("XTSDX Virtual ${p.label} #${slot + 1}", p.vendorId, p.productId, MAX_POINTS)
        }

        @Synchronized
        fun update() {
            var changed = false
            for (i in 0 until 8) {
                var best = 0
                for (s in sources) {
                    val v = s.value(i)
                    if (abs(v) > abs(best)) best = v
                }
                if (best != lastAxes[i]) {
                    Native.uinputEmit(fd, EV_ABS, ABS_OUT[i], best)
                    lastAxes[i] = best
                    changed = true
                }
            }
            var mask = 0
            for (s in sources) mask = mask or s.buttons
            val diff = mask xor lastBtn
            if (diff != 0) {
                for (b in BTN_OUT.indices) {
                    if (((diff shr b) and 1) != 0) Native.uinputEmit(fd, EV_KEY, BTN_OUT[b], (mask shr b) and 1)
                }
                lastBtn = mask
                changed = true
            }
            if (changed) Native.uinputEmit(fd, EV_SYN, 0, 0)
        }

        fun close() {
            sources.forEach { it.shutdown() }
            Native.uinputClose(fd)
        }
    }

    private class Source(val key: String, val slot: Int, val path: String, val pad: Pad) : Thread("src-$key") {
        private class Axis(val idx: Int, val kind: Int, val min: Int, val max: Int) // kind 0 stick, 1 trigger, 2 hat

        @Volatile private var running = true
        @Volatile var buttons = 0
        private val axes = IntArray(8)
        private val map = HashMap<Int, Axis>()
        private var dpad = 0
        private var tl2 = false
        private var tr2 = false
        private val fd = Native.evOpen(path, true)
        val ok get() = fd >= 0

        init {
            if (fd >= 0) buildMap()
            isDaemon = true
            priority = MAX_PRIORITY
        }

        // Rebuilds full state from the kernel after the event queue overflowed.
        private fun resync() {
            for (c in 0x120..0x12a) onKey(c, if (Native.evKey(fd, c)) 1 else 0)
            for (c in BTN_OUT) onKey(c, if (Native.evKey(fd, c)) 1 else 0)
            for (c in intArrayOf(0x138, 0x139, 0x220, 0x221, 0x222, 0x223)) onKey(c, if (Native.evKey(fd, c)) 1 else 0)
            val t = IntArray(4)
            for ((c, _) in map) if (Native.evAbs(fd, c, t)) onAbs(c, t[3])
        }

        private fun buildMap() {
            val t = IntArray(4)
            val used = HashSet<Int>()
            fun has(c: Int) = Native.evAbs(fd, c, t)
            fun put(c: Int, idx: Int, kind: Int): Boolean {
                if (idx in used || !has(c)) return false
                map[c] = Axis(idx, kind, t[0], t[1])
                used.add(idx)
                return true
            }
            put(0, 0, 0); put(1, 1, 0)
            if (has(3) && has(4)) {
                put(3, 2, 0); put(4, 3, 0); put(2, 4, 1); put(5, 5, 1)
            } else if (has(2) && has(5)) {
                put(2, 2, 0); put(5, 3, 0)
            } else {
                for (c in intArrayOf(5, 7, 2)) if (put(c, 2, 0)) break
                for (c in intArrayOf(6, 8)) if (put(c, 3, 0)) break
            }
            put(0x0a, 4, 1); put(0x09, 5, 1)
            put(0x10, 6, 2); put(0x11, 7, 2)
        }

        fun value(i: Int): Int = when (i) {
            4 -> maxOf(axes[4], if (tl2) MAX_POINTS else 0)
            5 -> maxOf(axes[5], if (tr2) MAX_POINTS else 0)
            6 -> if (axes[6] != 0) axes[6] else ((dpad shr 3) and 1) - ((dpad shr 2) and 1)
            7 -> if (axes[7] != 0) axes[7] else ((dpad shr 1) and 1) - (dpad and 1)
            else -> axes[i]
        }

        private fun onAbs(code: Int, v: Int) {
            val a = map[code] ?: return
            val span = (a.max - a.min).coerceAtLeast(1)
            axes[a.idx] = when (a.kind) {
                0 -> scaler.scale((v - a.min) * 2f / span - 1f)
                1 -> scaler.scaleTrigger((v - a.min).toFloat() / span)
                else -> v.coerceIn(-1, 1)
            }
        }

        private fun onKey(code: Int, v: Int) {
            val down = v != 0
            when (code) {
                0x138 -> tl2 = down
                0x139 -> tr2 = down
                0x220 -> dpad = if (down) dpad or 1 else dpad and 1.inv()
                0x221 -> dpad = if (down) dpad or 2 else dpad and 2.inv()
                0x222 -> dpad = if (down) dpad or 4 else dpad and 4.inv()
                0x223 -> dpad = if (down) dpad or 8 else dpad and 8.inv()
            }
            var idx = BTN_OUT.indexOf(code)
            if (idx < 0 && code in 0x120..0x12a) idx = code - 0x120
            if (idx >= 0) buttons = if (down) buttons or (1 shl idx) else buttons and (1 shl idx).inv()
        }

        override fun run() {
            val buf = IntArray(3)
            var dropped = false
            try {
                while (running) {
                    val r = Native.evRead(fd, buf)
                    if (r < 0) break
                    if (r == 0) continue
                    when (buf[0]) {
                        EV_ABS -> if (!dropped) onAbs(buf[1], buf[2])
                        EV_KEY -> if (!dropped) onKey(buf[1], buf[2])
                        EV_SYN -> if (buf[1] == 3) {
                            dropped = true
                        } else {
                            if (dropped) { resync(); dropped = false }
                            pad.update()
                        }
                    }
                }
            } finally {
                running = false
                Native.evClose(fd)
                pad.sources.remove(this)
                pad.update()
            }
        }

        fun shutdown() {
            running = false
            if (isAlive) try { join(1000) } catch (_: InterruptedException) {}
        }
    }

    private val pads = HashMap<Int, Pad>()
    private val sources = HashMap<String, Source>()
    private var wantPads: Map<Int, Boolean> = emptyMap()
    private var wantSrc: Map<String, Want> = emptyMap()
    private var lastReport = ""

    private fun scan(): List<Dev> = Native.evScan().mapNotNull { row ->
        val f = row.split('\t')
        if (f.size < 4 || f[1].startsWith("XTSDX")) null
        else Dev(f[0], f[1], f[2].toInt(), f[3].toInt())
    }.sortedBy { it.path.filter(Char::isDigit).toIntOrNull() ?: 0 }

    @Synchronized
    private fun reconcile() {
        for ((slot, pad) in pads.toMap()) {
            if (wantPads[slot] != pad.one) { pad.close(); pads.remove(slot) }
        }
        sources.entries.removeAll { (k, s) ->
            val w = wantSrc[k]
            val drop = w == null || w.slot != s.slot || !s.isAlive || pads[s.slot] !== s.pad
            if (drop) s.shutdown()
            drop
        }
        for ((slot, one) in wantPads) {
            if (slot in pads) continue
            val p = Pad(slot, one)
            if (p.fd >= 0) pads[slot] = p else println("ERR cannot create uinput device (code ${p.fd}); is root granted?")
        }
        val missing = wantSrc.filterKeys { it !in sources }
        if (missing.isNotEmpty()) {
            val devs = scan()
            val inUse = sources.values.map { it.path }.toSet()
            for ((k, w) in missing) {
                val pad = pads[w.slot] ?: continue
                val path = devs.filter { it.vid == w.vid && it.pid == w.pid }.getOrNull(w.n)?.path ?: continue
                if (path in inUse) continue
                val s = Source(k, w.slot, path, pad)
                if (!s.ok) continue
                pad.sources.add(s)
                s.start()
                sources[k] = s
            }
        }
        val report = "STATUS virtual pads: ${pads.size}, physical devices bridged: ${sources.size}"
        if (report != lastReport) { lastReport = report; println(report); System.out.flush() }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        System.load(args[0])
        Thread {
            while (true) {
                Thread.sleep(2000)
                try { reconcile() } catch (e: Exception) { println("ERR ${e.message}") }
            }
        }.apply { isDaemon = true; start() }

        val tp = HashMap<Int, Boolean>()
        val ts = HashMap<String, Want>()
        val input = BufferedReader(InputStreamReader(System.`in`))
        while (true) {
            val line = input.readLine() ?: break
            try {
                val t = line.trim().split(' ')
                when (t[0]) {
                    "BEGIN" -> { tp.clear(); ts.clear() }
                    "P" -> tp[t[1].toInt()] = t[2] == "1"
                    "M" -> {
                        val w = Want(t[1].toInt(), t[2].toInt(), t[3].toInt(), t[4].toInt())
                        ts["${w.vid}:${w.pid}:${w.n}"] = w
                    }
                    "END" -> synchronized(this) { wantPads = HashMap(tp); wantSrc = HashMap(ts); reconcile() }
                    "S" -> { scaler.deadzone = t[1].toFloat(); scaler.curve = t[2].toFloat() }
                    "QUIT" -> break
                }
            } catch (e: Exception) {
                println("ERR bad command '$line': ${e.message}")
            }
        }
        synchronized(this) { wantPads = emptyMap(); wantSrc = emptyMap(); reconcile() }
        exitProcess(0)
    }
}
