package com.xtsdx.virtualxbox

import android.content.Context
import java.io.BufferedWriter
import java.util.concurrent.Executors

/** Owns the root helper process and its command pipe. */
object Bridge {
    @Volatile var status = "Stopped"
    private var proc: Process? = null
    private var writer: BufferedWriter? = null
    private val io = Executors.newSingleThreadExecutor()

    val running get() = proc?.isAlive == true

    @Synchronized
    fun start(ctx: Context) {
        if (running) return
        val ai = ctx.applicationInfo
        val cmd = "exec app_process -Djava.class.path=${ai.sourceDir} /system/bin " +
            "com.xtsdx.virtualxbox.RootServer ${ai.nativeLibraryDir}/libvxbridge.so"
        try {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            proc = p
            writer = p.outputStream.bufferedWriter()
            status = "Starting (grant root if asked)..."
            Thread {
                p.inputStream.bufferedReader().forEachLine { status = it.removePrefix("STATUS ") }
                status = "Stopped (root helper exited)"
            }.apply { isDaemon = true; start() }
        } catch (e: Exception) {
            status = "Root (su) not available: ${e.message}"
        }
    }

    fun send(line: String) {
        io.execute {
            try {
                val w = writer ?: return@execute
                synchronized(w) { w.write(line); w.newLine(); w.flush() }
            } catch (_: Exception) {
            }
        }
    }

    fun stop() {
        val p = proc ?: return
        send("QUIT")
        io.execute { Thread.sleep(500); p.destroy() }
        proc = null
    }
}
