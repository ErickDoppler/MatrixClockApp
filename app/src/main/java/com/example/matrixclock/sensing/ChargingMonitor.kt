package com.example.matrixclock.sensing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * Reports whether the device is on external power.
 *
 * Everything expensive in this app (screen-on, camera, microphone) is gated on this, so the clock
 * is a dock feature and costs nothing on battery.
 */
class ChargingMonitor(
    private val context: Context,
    private val onChanged: (charging: Boolean) -> Unit
) {

    var isCharging = false
        private set

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = update(readCharging(intent))
    }

    fun start() {
        if (registered) return
        registered = true
        // A sticky ACTION_BATTERY_CHANGED gives the current state immediately on register.
        val sticky = context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        update(sticky?.let(::readCharging) ?: false)
    }

    fun stop() {
        if (!registered) return
        registered = false
        context.unregisterReceiver(receiver)
    }

    private fun readCharging(intent: Intent): Boolean {
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        if (status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        ) {
            return true
        }
        // A dock can supply power without the battery reporting "charging" once it is full.
        return intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    private fun update(charging: Boolean) {
        if (charging == isCharging) return
        isCharging = charging
        onChanged(charging)
    }
}
