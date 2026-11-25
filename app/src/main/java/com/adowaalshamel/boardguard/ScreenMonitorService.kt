package com.adowaalshamel.boardguard

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.adowaalshamel.boardguard.backend.LockerRuntime
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that monitors Riotouch lock events and Android unlock events.
 * The persisted BoardGuard lock state is the security source of truth; LockActivity
 * being alive is only a UI/runtime detail.
 */
class ScreenMonitorService : Service() {

    private var isReceiverRegistered = false
    private lateinit var pinStore: PinStore
    private lateinit var runtime: LockerRuntime
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var syncJob: Job? = null
    private var serviceRunning = false
    private var networkCallbackRegistered = false
    private var nextSyncElapsed = 0L
    private val policyPoll = Runnable { requestPolicySync("poll") }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            mainHandler.post { requestPolicySync("network_available") }
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                mainHandler.post { requestPolicySync("network_validated") }
            }
        }
    }

    @Volatile
    private var pendingSystemUnlock = false

    @Volatile
    private var relaunchScheduled = false

    private var postBootRunnable: Runnable? = null
    private var postBootStartElapsed = 0L

    // An early vendor CLOSE is only considered when this service observed the OPEN.
    // This is an experimental fast path; CHILD_UNLOCK remains the confirmed fallback.
    private var childLockOpenObserved = false

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_DAZZLE_CHILD_LOCK_OPEN -> {
                    // Track this particular child-lock session before accepting an early CLOSE.
                    childLockOpenObserved = true
                    cancelPostBootFallback("dazzle_child_lock_open")
                    Log.i(TAG, "DAZZLE_CHILD_LOCK_OPEN")
                }

                ACTION_DAZZLE_CHILD_LOCK_CLOSE -> {
                    val closeBelongsToObservedSession = childLockOpenObserved
                    childLockOpenObserved = false
                    Log.i(TAG, "DAZZLE_CHILD_LOCK_CLOSE observedOpen=$closeBelongsToObservedSession")

                    // Experimental: on this Riotouch build CLOSE precedes UNLOCK by ~1.5 s.
                    // A wrong PIN did not emit CLOSE in the reported test. It is NOT proof
                    // that CLOSE always means successful authentication (e.g. cancellation).
                    if (ENABLE_EARLY_CHILD_LOCK_CLOSE && closeBelongsToObservedSession) {
                        cancelPostBootFallback("dazzle_child_lock_close")
                        Log.i(TAG, "DAZZLE_CHILD_CLOSE_FAST_PATH")
                        armBoardGuardAndLaunch(context, "dazzle_child_lock_close")
                    }
                }

                ACTION_DAZZLE_CHILD_UNLOCK -> {
                    childLockOpenObserved = false
                    cancelPostBootFallback("dazzle_child_unlock")
                    Log.i(TAG, "DAZZLE_CHILD_UNLOCK")
                    // Fallback if CLOSE was missed, or if the early Activity was stopped.
                    armBoardGuardAndLaunch(context, "dazzle_child_unlock")
                }

                ACTION_SCREEM_COMMENT_STARTED -> {
                    // UFreeze is the other Riotouch primary-lock implementation.
                    // Keep the existing flow: wait until it closes successfully.
                    cancelPostBootFallback("ufreeze_started")
                    Log.i(TAG, "UFREEZE_STARTED")
                }

                ACTION_SCREEM_COMMENT_CLOSED, ACTION_UFREEZE_EXIT -> {
                    cancelPostBootFallback("ufreeze_unlock")
                    Log.i(TAG, "UFREEZE_CLOSED")
                    armBoardGuardAndLaunch(context, "ufreeze_unlock")
                }

                Intent.ACTION_SCREEN_OFF -> {
                    pendingSystemUnlock = true
                    mainHandler.removeCallbacks(policyPoll)
                    Log.d(TAG, "SCREEN_OFF -> waiting for next system unlock")
                }

                Intent.ACTION_SCREEN_ON -> {
                    requestPolicySync("screen_on")
                    val keyguardManager =
                        context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                    val isLocked = keyguardManager?.isKeyguardLocked == true

                    Log.d(
                        TAG,
                        "SCREEN_ON -> isKeyguardLocked=$isLocked, pending=$pendingSystemUnlock"
                    )

                    if (isLocked) {
                        pendingSystemUnlock = true
                    }
                }

                Intent.ACTION_USER_PRESENT -> {
                    requestPolicySync("user_present")
                    cancelPostBootFallback("android_user_present")
                    Log.d(TAG, "USER_PRESENT -> pendingSystemUnlock=$pendingSystemUnlock")
                    handleUserPresent(context)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        runtime = LockerRuntime.get(this)
        pinStore = runtime.pinStore

        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguardManager?.isKeyguardLocked == true) {
            pendingSystemUnlock = true
            Log.i(TAG, "Service started while Android keyguard is locked")
        }

        registerUnlockReceiver()
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundServiceNotification()

        if (!BoardGuardDeviceAdminReceiver.isDeviceOwner(this)) {
            Log.i(TAG, "Monitoring requires Device Owner")
            serviceRunning = false
            stopSelf()
            return START_NOT_STICKY
        }

        // Stay alive before the first PIN and while protection is disabled so that
        // enrollment, the first password and later policy changes can reach the screen.
        serviceRunning = true
        requestPolicySync("service_start")

        if (pinStore.isBoardGuardLocked()) {
            scheduleRelaunch("service_start", 400L)
        }

        if (intent?.action == ACTION_START_AFTER_BOOT) {
            Log.i(TAG, "BOOT_TRIGGER_RECEIVED")
            schedulePostBootFallback()
        }

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)

        if (pinStore.isBoardGuardLocked()) {
            Log.w(TAG, "LOCK_TASK_REMOVED_WHILE_LOCKED")
            LockActivity.isLockActive = false
            scheduleRelaunch("task_removed", 250L)
        }
    }

    override fun onDestroy() {
        serviceRunning = false
        serviceScope.cancel()
        unregisterNetworkCallback()
        unregisterUnlockReceiver()
        cancelPostBootFallback("service_destroyed")
        mainHandler.removeCallbacksAndMessages(null)
        relaunchScheduled = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerNetworkCallback() {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        try {
            manager.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
        } catch (_: RuntimeException) {
            // Periodic and wake-up synchronization remain available.
            Log.w(TAG, "Unable to observe network changes")
        }
    }

    private fun unregisterNetworkCallback() {
        if (!networkCallbackRegistered) return
        try {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(networkCallback)
        } catch (_: RuntimeException) {
            Log.w(TAG, "Unable to unregister network observer")
        }
        networkCallbackRegistered = false
    }

    private fun requestPolicySync(reason: String) {
        if (!serviceRunning || syncJob?.isActive == true) return
        mainHandler.removeCallbacks(policyPoll)
        if (SystemClock.elapsedRealtime() < nextSyncElapsed) {
            scheduleNextPolicySync()
            return
        }

        syncJob = serviceScope.launch {
            try {
                val result = runtime.synchronize()
                val retrySeconds = result.retryAfterSeconds ?: 0L
                // Avoid overflow for a malformed, exceptionally large Retry-After.
                val retryMillis = retrySeconds.coerceIn(0L, Long.MAX_VALUE / 2000L) * 1000L
                nextSyncElapsed = SystemClock.elapsedRealtime() + retryMillis
                // The store remains authoritative even when synchronization fails:
                // a failed write may have switched it to its protective locked state.
                // A valid first PIN arms the lock; later PIN changes preserve its session.
                if (pinStore.isBoardGuardLocked() && pinStore.isProtectionEnabled()) {
                    if (!LockActivity.isLockActive) launchLockActivity(this@ScreenMonitorService)
                }
                if (!result.synchronized) {
                    Log.d(TAG, "Policy synchronization deferred: ${result.failure}, trigger=$reason")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Never log an HTTP response, password, signed activation or token.
                Log.w(TAG, "Unable to synchronize screen policy")
            } finally {
                scheduleNextPolicySync()
            }
        }
    }

    private fun scheduleNextPolicySync() {
        mainHandler.removeCallbacks(policyPoll)
        if (!serviceRunning) return
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        if (!powerManager.isInteractive) return
        val delayMillis = maxOf(POLICY_POLL_INTERVAL_MS, nextSyncElapsed - SystemClock.elapsedRealtime())
        mainHandler.postDelayed(policyPoll, delayMillis)
    }

    private fun registerUnlockReceiver() {
        if (isReceiverRegistered) return

        val filter = IntentFilter().apply {
            addAction(ACTION_DAZZLE_CHILD_LOCK_OPEN)
            addAction(ACTION_DAZZLE_CHILD_LOCK_CLOSE)
            addAction(ACTION_DAZZLE_CHILD_UNLOCK)
            addAction(ACTION_SCREEM_COMMENT_STARTED)
            addAction(ACTION_SCREEM_COMMENT_CLOSED)
            addAction(ACTION_UFREEZE_EXIT)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }

        ContextCompat.registerReceiver(
            this,
            unlockReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )

        isReceiverRegistered = true
        Log.i(TAG, "Registered unlock receiver")
    }

    private fun unregisterUnlockReceiver() {
        if (!isReceiverRegistered) return

        try {
            unregisterReceiver(unlockReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }

        isReceiverRegistered = false
    }

    /**
     * BOOT_COMPLETED means the board has started a new device session.
     *
     * Existing behavior is preserved when a Riotouch primary lock exists:
     * its OPEN broadcast cancels this fallback, and its normal UNLOCK broadcast
     * launches Fttego Locker afterward.
     *
     * If no primary lock is enabled, no vendor unlock broadcast will ever arrive,
     * so after Android becomes interactive we launch Fttego Locker ourselves.
     */
    private fun schedulePostBootFallback() {
        cancelPostBootFallback("reschedule")
        postBootStartElapsed = SystemClock.elapsedRealtime()

        val runnable = object : Runnable {
            override fun run() {
                if (!pinStore.hasPin() || !pinStore.isProtectionEnabled()) {
                    postBootRunnable = null
                    return
                }

                if (!BoardGuardDeviceAdminReceiver.isDeviceOwner(this@ScreenMonitorService)) {
                    Log.w(TAG, "Post-boot lock skipped because app is not Device Owner")
                    postBootRunnable = null
                    return
                }

                val keyguardManager =
                    getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager

                // If Android itself is credential-locked, keep the old USER_PRESENT flow.
                if (keyguardManager?.isDeviceLocked == true) {
                    pendingSystemUnlock = true
                    Log.i(TAG, "BOOT_WAITING_FOR_ANDROID_KEYGUARD")
                    postBootRunnable = null
                    return
                }

                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

                if (!powerManager.isInteractive) {
                    val elapsed = SystemClock.elapsedRealtime() - postBootStartElapsed

                    if (elapsed < BOOT_INTERACTIVE_TIMEOUT_MS) {
                        Log.d(TAG, "BOOT_WAITING_FOR_INTERACTIVE")
                        mainHandler.postDelayed(this, BOOT_INTERACTIVE_RECHECK_MS)
                        return
                    }

                    Log.w(TAG, "Boot interactive wait timed out; continuing with locker fallback")
                }

                postBootRunnable = null
                Log.i(TAG, "BOOT_NO_PRIMARY_LOCK -> launching Fttego Locker")
                armBoardGuardAndLaunch(this@ScreenMonitorService, "boot_without_primary_lock")
            }
        }

        postBootRunnable = runnable

        // Give Riotouch's primary-lock services a short chance to announce themselves.
        // If one opens, ACTION_DAZZLE_CHILD_LOCK_OPEN or UFREEZE_STARTED cancels this.
        mainHandler.postDelayed(runnable, BOOT_PRIMARY_LOCK_GRACE_MS)
    }

    private fun cancelPostBootFallback(reason: String) {
        val runnable = postBootRunnable ?: return
        mainHandler.removeCallbacks(runnable)
        postBootRunnable = null
        Log.d(TAG, "POST_BOOT_FALLBACK_CANCELLED reason=$reason")
    }

    private fun handleUserPresent(context: Context) {
        if (!pendingSystemUnlock) {
            Log.d(TAG, "USER_PRESENT ignored because no Android keyguard unlock was pending")
            return
        }

        pendingSystemUnlock = false
        armBoardGuardAndLaunch(context, "android_user_present")
    }

    private fun armBoardGuardAndLaunch(context: Context, reason: String) {
        if (!pinStore.hasPin() || !pinStore.isProtectionEnabled()) {
            Log.d(TAG, "Ignoring $reason because protection is inactive or PIN is missing")
            return
        }

        if (!BoardGuardDeviceAdminReceiver.isDeviceOwner(context)) {
            Log.w(TAG, "Ignoring $reason because BoardGuard is not Device Owner")
            return
        }

        try {
            // Every real primary/system unlock starts a new lock cycle. A late HTTP
            // verification from the preceding cycle must not clear this one.
            pinStore.setBoardGuardLocked(true)
            Log.i(TAG, "BOARDGUARD_LOCK_ARMED reason=$reason")
        } catch (_: IOException) {
            // PinStore fails closed in memory if its durable write fails.
            Log.e(TAG, "Unable to persist the new lock session; keeping screen locked")
        }

        if (LockActivity.isLockActive) {
            Log.d(TAG, "LockActivity already active")
            return
        }

        launchLockActivity(context)
    }

    private fun scheduleRelaunch(reason: String, delayMs: Long) {
        if (relaunchScheduled) return

        relaunchScheduled = true
        mainHandler.postDelayed({
            relaunchScheduled = false

            if (!pinStore.isBoardGuardLocked()) return@postDelayed
            if (!pinStore.hasPin() || !pinStore.isProtectionEnabled()) return@postDelayed
            if (!BoardGuardDeviceAdminReceiver.isDeviceOwner(this)) return@postDelayed
            if (LockActivity.isLockActive) return@postDelayed

            Log.w(TAG, "RELAUNCHING_BOARDGUARD reason=$reason")
            launchLockActivity(this)
        }, delayMs)
    }

    private fun launchLockActivity(context: Context) {
        if (!pinStore.isBoardGuardLocked()) {
            Log.d(TAG, "launchLockActivity skipped because persisted lock state is false")
            return
        }

        BoardGuardDeviceAdminReceiver.setupLockTaskMode(context)

        val lockIntent = Intent(context, LockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }

        try {
            LockActivity.isLockActive = true
            Log.i(TAG, "LAUNCHING_BOARDGUARD")

            // Keep the existing launch path unchanged.
            context.startActivity(lockIntent)
        } catch (e: Exception) {
            LockActivity.isLockActive = false
            Log.e(TAG, "Failed to launch LockActivity", e)
            scheduleRelaunch("launch_failure", 500L)
        }
    }

    private fun startForegroundServiceNotification() {
        createNotificationChannel()

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.locker_monitor_notification))
            .setSmallIcon(R.mipmap.ic_launcher_round)
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
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.app_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "BoardGuard protection service"
        }

        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "ScreenMonitorService"
        private const val CHANNEL_ID = "boardguard_service_channel"
        private const val NOTIFICATION_ID = 1001
        private const val POLICY_POLL_INTERVAL_MS = 10_000L

        private const val ACTION_START_AFTER_BOOT =
            "com.adowaalshamel.boardguard.action.START_AFTER_BOOT"

        // Short grace window so Riotouch Child Lock / UFreeze can announce itself.
        private const val BOOT_PRIMARY_LOCK_GRACE_MS = 3500L
        private const val BOOT_INTERACTIVE_RECHECK_MS = 500L
        private const val BOOT_INTERACTIVE_TIMEOUT_MS = 30000L

        // Set to false to revert to the original, unlock-only behavior.
        private const val ENABLE_EARLY_CHILD_LOCK_CLOSE = true

        private const val ACTION_DAZZLE_CHILD_LOCK_OPEN = "com.dazzle.childlock.open"
        private const val ACTION_DAZZLE_CHILD_LOCK_CLOSE = "com.dazzle.childlock.close"
        private const val ACTION_DAZZLE_CHILD_UNLOCK = "childlock_unlock"

        private const val ACTION_SCREEM_COMMENT_STARTED = "screem_comment_started"
        private const val ACTION_SCREEM_COMMENT_CLOSED = "screem_comment_closed"
        private const val ACTION_UFREEZE_EXIT = "com.riotouch.ufreeze.exit"

        fun startService(context: Context) {
            val intent = Intent(context, ScreenMonitorService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun startServiceAfterBoot(context: Context) {
            val intent = Intent(context, ScreenMonitorService::class.java).apply {
                action = ACTION_START_AFTER_BOOT
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, ScreenMonitorService::class.java)
            context.stopService(intent)
        }
    }
}
