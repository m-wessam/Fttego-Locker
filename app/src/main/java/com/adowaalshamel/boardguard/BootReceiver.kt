package com.adowaalshamel.boardguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Listens for system BOOT_COMPLETED to restart ScreenMonitorService
 * if protection is active.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "ACTION_BOOT_COMPLETED received")
            val pinStore = PinStore(context)
            if (pinStore.hasPin() && pinStore.isProtectionEnabled()) {
                Log.i(TAG, "Protection enabled, starting ScreenMonitorService post-boot")
                ScreenMonitorService.startService(context)
            }
        }
    }

    companion object {
        private const val TAG = "BoardGuardBootReceiver"
    }
}
