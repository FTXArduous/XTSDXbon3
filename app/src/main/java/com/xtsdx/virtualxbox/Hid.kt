package com.xtsdx.virtualxbox

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/** Presents up to 4 virtual gamepads to a paired Bluetooth host. */
@SuppressLint("MissingPermission")
class Hid(private val ctx: Context) {
    @Volatile var status = "Starting..."
    private var adapter: BluetoothAdapter? = null
    private var proxy: BluetoothHidDevice? = null
    @Volatile private var host: BluetoothDevice? = null
    private var registered = false
    private var pending = false
    private var slots: List<Int> = emptyList()
    private var one = true
    private var profile = 0
    private val last = arrayOfNulls<ByteArray>(4)
    private val exec = Executors.newSingleThreadExecutor()

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            this@Hid.registered = registered
            if (!registered && pending) {
                pending = false
                proxy?.let { doRegister(it) }
            } else {
                status = if (registered) "Ready. Pair or connect from the other device." else "Idle"
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    last.fill(null)
                    status = "Connected to ${device.name ?: device.address}"
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (host?.address == device.address) host = null
                    status = "Disconnected"
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            proxy?.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ)
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            proxy?.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ)
        }
    }

    fun start() {
        val ad = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        if (ad == null) { status = "No Bluetooth adapter"; return }
        adapter = ad
        ad.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, p: BluetoothProfile) {
                if (profile == BluetoothProfile.HID_DEVICE) { proxy = p as BluetoothHidDevice; apply() }
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.HID_DEVICE) { proxy = null; registered = false; host = null }
            }
        }, BluetoothProfile.HID_DEVICE)
    }

    fun stop() {
        proxy?.let { p ->
            if (registered) p.unregisterApp()
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, p)
        }
        proxy = null
    }

    fun configure(newSlots: List<Int>, newOne: Boolean, newProfile: Int) {
        if (newSlots == slots && newOne == one && newProfile == profile) return
        slots = newSlots
        one = newOne
        profile = newProfile
        last.fill(null)
        apply()
    }

    private fun apply() {
        val p = proxy ?: return
        if (registered) {
            pending = slots.isNotEmpty()
            p.unregisterApp()
        } else if (slots.isNotEmpty()) {
            doRegister(p)
        }
    }

    private fun doRegister(p: BluetoothHidDevice) {
        val ps = profile == PS
        val sdp = BluetoothHidDeviceAppSdpSettings(
            NAMES[profile], "Gamepad", if (ps) "Sony Interactive Entertainment" else "XTSDX",
            BluetoothHidDevice.SUBCLASS2_GAMEPAD, descriptor(slots, one, ps),
        )
        // 11.25 ms is the lowest latency request the HID QoS record accepts in practice.
        val qos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT, 800, 9, 0, 11250,
            BluetoothHidDeviceAppQosSettings.MAX,
        )
        if (!p.registerApp(sdp, null, qos, exec, callback)) status = "Bluetooth HID registration failed"
    }

    fun send(slot: Int, m: Merged) {
        val h = proxy ?: return
        val d = host ?: return
        val report = if (profile == PS) m.ds4() else m.bytes()
        if (last[slot]?.contentEquals(report) == true) return
        last[slot] = report
        h.sendReport(d, slot + 1, report)
    }

    fun connect(d: BluetoothDevice) { proxy?.connect(d) }
    fun disconnect() { host?.let { proxy?.disconnect(it) } }

    companion object {
        const val PS = 2
        val LABELS = listOf("Xbox controller", "Generic USB gamepad", "PlayStation controller")
        // Names the PC sees for the HID service (and the phone name when "advertise" is on).
        val NAMES = listOf("Virtual Xbox Controller", "Generic USB Gamepad", "Wireless Controller")

        /** One Game Pad collection per slot, report ID = slot + 1, 16-bit axes of +-32256 (or DualShock 4 layout). */
        fun descriptor(slots: List<Int>, one: Boolean, ps: Boolean = false): ByteArray {
            val b = ByteArrayOutputStream()
            fun w(vararg v: Int) = v.forEach { b.write(it) }
            for (s in slots) {
                if (ps) {
                    w(0x05, 0x01, 0x09, 0x05, 0xA1, 0x01, 0x85, s + 1)
                    // LX LY RX RY, 8-bit
                    w(0x09, 0x30, 0x09, 0x31, 0x09, 0x32, 0x09, 0x35, 0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08, 0x95, 0x04, 0x81, 0x02)
                    // Hat switch (0-7, 8 = neutral)
                    w(0x09, 0x39, 0x15, 0x00, 0x25, 0x07, 0x35, 0x00, 0x46, 0x3B, 0x01, 0x65, 0x14, 0x75, 0x04, 0x95, 0x01, 0x81, 0x42)
                    // 14 buttons plus 6 bits of padding
                    w(0x65, 0x00, 0x05, 0x09, 0x19, 0x01, 0x29, 0x0E, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x0E, 0x81, 0x02)
                    w(0x75, 0x06, 0x95, 0x01, 0x81, 0x01)
                    // L2 / R2 analog
                    w(0x05, 0x01, 0x09, 0x33, 0x09, 0x34, 0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08, 0x95, 0x02, 0x81, 0x02)
                    w(0xC0)
                    continue
                }
                w(0x05, 0x01, 0x09, 0x05, 0xA1, 0x01, 0x85, s + 1)
                // 16 buttons
                w(0x05, 0x09, 0x19, 0x01, 0x29, 0x10, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x10, 0x81, 0x02)
                // Hat switch plus 4 bits of padding
                w(0x05, 0x01, 0x09, 0x39, 0x15, 0x01, 0x25, 0x08, 0x35, 0x00, 0x46, 0x3B, 0x01, 0x65, 0x14)
                w(0x75, 0x04, 0x95, 0x01, 0x81, 0x42)
                w(0x65, 0x00, 0x35, 0x00, 0x45, 0x00, 0x75, 0x04, 0x95, 0x01, 0x81, 0x03)
                // Sticks: One layout = X Y Z Rz (Android standard), 360 layout = X Y Rx Ry (XInput style)
                w(0x05, 0x01, 0x09, 0x30, 0x09, 0x31)
                if (one) w(0x09, 0x32, 0x09, 0x35) else w(0x09, 0x33, 0x09, 0x34)
                w(0x16, 0x00, 0x82, 0x26, 0x00, 0x7E, 0x75, 0x10, 0x95, 0x04, 0x81, 0x02)
                // Triggers: One layout = Brake/Accelerator, 360 layout = Z/Rz
                if (one) w(0x05, 0x02, 0x09, 0xC5, 0x09, 0xC4) else w(0x05, 0x01, 0x09, 0x32, 0x09, 0x35)
                w(0x15, 0x00, 0x26, 0x00, 0x7E, 0x75, 0x10, 0x95, 0x02, 0x81, 0x02)
                w(0xC0)
            }
            return b.toByteArray()
        }
    }
}
