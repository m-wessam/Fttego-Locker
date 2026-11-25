package com.adowaalshamel.boardguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.IOException

/**
 * Restarts monitoring and backend synchronization, including before the first PIN.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        Log.i(TAG, "Locker restart event: $action")

        if (!BoardGuardDeviceAdminReceiver.isDeviceOwner(context)) return

        val pinStore = PinStore(context)

        // Only a real boot begins a new lock cycle. App updates must keep the
        // existing lock state and immediately restore the monitoring service.
        if (action == Intent.ACTION_BOOT_COMPLETED) {
            try {
                pinStore.setBoardGuardLocked(false)
            } catch (_: IOException) {
                Log.e(TAG, "Unable to persist the boot session; keeping cached protection")
            }
        }

        try {
            if (action == Intent.ACTION_BOOT_COMPLETED) {
                ScreenMonitorService.startServiceAfterBoot(context)
            } else {
                ScreenMonitorService.startService(context)
            }
        } catch (_: RuntimeException) {
            Log.e(TAG, "Unable to restart screen monitoring")
        }
    }

    companion object {
        private const val TAG = "BoardGuardBootReceiver"
    }
}
