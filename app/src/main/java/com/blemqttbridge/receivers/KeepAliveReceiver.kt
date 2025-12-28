package com.blemqttbridge.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.blemqttbridge.core.BaseBleService

/**
 * BroadcastReceiver that handles periodic keepalive alarms to prevent
 * BLE connections from being suspended during Android Doze mode.
 * 
 * This receiver is triggered by AlarmManager at regular intervals to:
 * 1. Wake the device briefly from Doze
 * 2. Check BLE connection status
 * 3. Trigger a small BLE operation to keep connections alive
 * 
 * The keepalive mechanism is particularly useful for mains-powered devices
 * where battery drain is not a concern, ensuring continuous BLE connectivity
 * even during extended idle periods.
 */
class KeepAliveReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "KeepAliveReceiver"
        const val ACTION_KEEPALIVE = "com.blemqttbridge.ACTION_KEEPALIVE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_KEEPALIVE) return

        Log.d(TAG, "⏰ Keepalive alarm triggered")

        // Check if service is running
        if (!BaseBleService.serviceRunning.value) {
            Log.w(TAG, "⚠️ Service not running, skipping keepalive")
            return
        }

        // Send intent to service to perform keepalive operation
        val serviceIntent = Intent(context, BaseBleService::class.java).apply {
            action = BaseBleService.ACTION_KEEPALIVE_PING
        }

        try {
            context.startService(serviceIntent)
            Log.d(TAG, "✅ Keepalive ping sent to service")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to send keepalive ping", e)
        }
    }
}
