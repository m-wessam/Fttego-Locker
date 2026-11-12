package com.adowaalshamel.boardguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Restarts Fttego Locker monitoring after boot when protection is enabled.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        Log.i(TAG, "Locker restart event: $action")

        val pinStore = PinStore(context)

        // Only a real boot begins a new lock cycle. App updates must keep the
        // existing lock state and immediately restore the monitoring service.
        if (action == Intent.ACTION_BOOT_COMPLETED) {
            pinStore.setBoardGuardLocked(false)
        }

        if (
            pinStore.hasPin() &&
            pinStore.isProtectionEnabled() &&
            BoardGuardDeviceAdminReceiver.isDeviceOwner(context)
        ) {
            Log.i(TAG, "Protection enabled, starting post-boot lock flow")
            if (action == Intent.ACTION_BOOT_COMPLETED) {
                ScreenMonitorService.startServiceAfterBoot(context)
            } else {
                try {
                    ScreenMonitorService.startService(context)
                } catch (e: Exception) {
                    Log.e(TAG, "Unable to restart monitoring after app update", e)
                }
            }
        }
    }

    companion object {
        private const val TAG = "BoardGuardBootReceiver"
    }
}
