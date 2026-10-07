package com.adowaalshamel.boardguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground Service that monitors screen unlock events (ACTION_USER_PRESENT)
 * and launches LockActivity when secondary protection is active.
 */
class ScreenMonitorService : Service() {

    private var isReceiverRegistered = false
    private lateinit var pinStore: PinStore

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_USER_PRESENT -> {
                    Log.d(TAG, "ACTION_USER_PRESENT received")
                    handleUserPresent(context)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "ACTION_SCREEN_OFF received")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        pinStore = PinStore(this)
        registerUnlockReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundServiceNotification()

        // Verify protection state; if disabled, stop service
        if (!pinStore.isProtectionEnabled() || !pinStore.hasPin()) {
            Log.i(TAG, "Protection disabled or PIN missing, stopping ScreenMonitorService")
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    override fun onDestroy() {
        unregisterUnlockReceiver()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerUnlockReceiver() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            registerReceiver(unlockReceiver, filter, RECEIVER_NOT_EXPORTED)
            isReceiverRegistered = true
            Log.i(TAG, "Registered unlock receiver")
        }
    }

    private fun unregisterUnlockReceiver() {
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(unlockReceiver)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering receiver", e)
            }
            isReceiverRegistered = false
            Log.i(TAG, "Unregistered unlock receiver")
        }
    }

    private fun handleUserPresent(context: Context) {
        if (!pinStore.hasPin() || !pinStore.isProtectionEnabled()) {
            Log.d(TAG, "User present, but protection not active.")
            return
        }

        if (!BoardGuardDeviceAdminReceiver.isDeviceOwner(context)) {
            Log.w(TAG, "User present, but app is not Device Owner.")
            return
        }

        if (LockActivity.isLockActive) {
            Log.d(TAG, "LockActivity already active.")
            return
        }

        Log.i(TAG, "Launching LockActivity after system unlock.")
        BoardGuardDeviceAdminReceiver.setupLockTaskMode(context)

        val lockIntent = Intent(context, LockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        context.startActivity(lockIntent)
    }

    private fun startForegroundServiceNotification() {
        createNotificationChannel()

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.protection_active_notification))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val name = getString(R.string.app_name)
        val channel = NotificationChannel(
            CHANNEL_ID,
            name,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "BoardGuard Service Notification"
        }

        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "ScreenMonitorService"
        private const val CHANNEL_ID = "boardguard_service_channel"
        private const val NOTIFICATION_ID = 1001

        fun startService(context: Context) {
            val intent = Intent(context, ScreenMonitorService::class.java)
            context.startForegroundService(intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, ScreenMonitorService::class.java)
            context.stopService(intent)
        }
    }
}
