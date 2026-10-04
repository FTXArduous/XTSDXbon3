package com.xtsdx.virtualxbox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/** Owns the root helper while the app is in the background: restarts it, re-sends config on hotplug. */
class BridgeService : Service(), InputManager.InputDeviceListener {
    private val handler = Handler(Looper.getMainLooper())
    private var wake: PowerManager.WakeLock? = null
    private var listening = false

    private val watchdog = object : Runnable {
        override fun run() {
            if (!Bridge.running) { Bridge.start(this@BridgeService); Config.push(this@BridgeService) }
            handler.postDelayed(this, 5000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("bridge", "Controller bridge", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "bridge")
            .setContentTitle("Virtual Xbox controller running")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)

        if (wake == null) {
            wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xtsdx:bridge").apply { acquire() }
        }
        if (!listening) {
            (getSystemService(Context.INPUT_SERVICE) as InputManager).registerInputDeviceListener(this, handler)
            listening = true
        }
        handler.removeCallbacks(watchdog)
        handler.post(watchdog)
        return START_STICKY
    }

    override fun onInputDeviceAdded(id: Int) = Config.push(this)
    override fun onInputDeviceRemoved(id: Int) = Config.push(this)
    override fun onInputDeviceChanged(id: Int) {}

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        if (listening) (getSystemService(Context.INPUT_SERVICE) as InputManager).unregisterInputDeviceListener(this)
        wake?.release()
        Bridge.stop()
    }
}
