package com.adowaalshamel.boardguard

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log

/**
 * DeviceAdminReceiver implementation for BoardGuard Device Owner policy management.
 */
class BoardGuardDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: android.content.Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "BoardGuard Device Admin Enabled")
    }

    override fun onDisabled(context: Context, intent: android.content.Intent) {
        super.onDisabled(context, intent)
        Log.i(TAG, "BoardGuard Device Admin Disabled")
    }

    companion object {
        private const val TAG = "BoardGuardAdminReceiver"

        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context.applicationContext, BoardGuardDeviceAdminReceiver::class.java)
        }

        fun isDeviceOwner(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            return dpm?.isDeviceOwnerApp(context.packageName) == true
        }

        fun setupLockTaskMode(context: Context) {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
            val adminComponent = getComponentName(context)

            if (dpm.isDeviceOwnerApp(context.packageName)) {
                try {
                    // Allow BoardGuard to be started in Lock Task Mode
                    dpm.setLockTaskPackages(adminComponent, arrayOf(context.packageName))
                    // Disable status bar, home, recents, notifications in Lock Task Mode
                    dpm.setLockTaskFeatures(adminComponent, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to setup Lock Task Mode", e)
                }
            }
        }

        fun clearLockTaskPackages(context: Context) {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
            val adminComponent = getComponentName(context)

            if (dpm.isDeviceOwnerApp(context.packageName)) {
                try {
                    dpm.setLockTaskPackages(adminComponent, arrayOf())
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to clear Lock Task Packages", e)
                }
            }
        }
    }
}
