package com.adowaalshamel.boardguard

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.adowaalshamel.boardguard.backend.LockerFailure
import com.adowaalshamel.boardguard.backend.LockerRuntime
import com.adowaalshamel.boardguard.backend.LockerUnlockResult
import com.adowaalshamel.boardguard.ui.CustomNumericKeypad
import com.adowaalshamel.boardguard.ui.PinInputBoxes
import com.adowaalshamel.boardguard.ui.theme.BoardGuardTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Full-screen secondary lock Activity.
 * Runtime validation or a synchronized disabled policy clears the persisted lock state.
 */
class LockActivity : ComponentActivity() {

    private lateinit var pinStore: PinStore
    private lateinit var runtime: LockerRuntime
    private var exiting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        runtime = LockerRuntime.get(this)
        pinStore = runtime.pinStore
        isLockActive = true

        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        if (!pinStore.isBoardGuardLocked()) {
            Log.w(TAG, "LockActivity opened while persisted lock state is false; closing")
            exitIfUnlocked("opened_without_lock")
            return
        }

        BoardGuardDeviceAdminReceiver.setupLockTaskMode(this)
        ensureLockTaskMode()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                runtime.policyRevision.collect {
                    // A disabled policy has already been persisted atomically by the runtime.
                    exitIfUnlocked("policy_updated")
                }
            }
        }

        setContent {
            BoardGuardTheme {
                LockScreenContent(
                    onUnlocked = { exitIfUnlocked("validation_completed") },
                    verifyPin = runtime::verifyPin,
                    remainingBlockSeconds = { pinStore.remainingBlockSeconds() }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        isLockActive = !exiting
    }

    override fun onResume() {
        super.onResume()
        if (exitIfUnlocked("resumed_without_lock")) return
        isLockActive = true
        ensureLockTaskMode()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!exitIfUnlocked("new_intent_without_lock")) {
            ensureLockTaskMode()
        }
    }

    override fun onStop() {
        isLockActive = false

        if (::pinStore.isInitialized && pinStore.isBoardGuardLocked()) {
            Log.w(TAG, "LOCK_ACTIVITY_STOPPED_WHILE_LOCKED")
            ensureMonitorServiceRunning()
        }

        super.onStop()
    }

    override fun onDestroy() {
        isLockActive = false

        if (::pinStore.isInitialized && pinStore.isBoardGuardLocked()) {
            Log.w(TAG, "LOCK_ACTIVITY_DESTROYED_WHILE_LOCKED")
            ensureMonitorServiceRunning()
        }

        super.onDestroy()
    }

    private fun ensureLockTaskMode() {
        if (exiting || !pinStore.isBoardGuardLocked()) return

        if (!BoardGuardDeviceAdminReceiver.isDeviceOwner(this)) {
            Log.e(TAG, "Cannot enter Lock Task Mode because app is not Device Owner")
            return
        }

        try {
            BoardGuardDeviceAdminReceiver.setupLockTaskMode(this)

            val dpm = getSystemService(DevicePolicyManager::class.java)
            val activityManager = getSystemService(ActivityManager::class.java)

            if (
                dpm.isLockTaskPermitted(packageName) &&
                activityManager.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE
            ) {
                startLockTask()
            }

            Log.i(
                TAG,
                "LOCK_TASK_STATE=${lockTaskStateName(activityManager.lockTaskModeState)}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unable to enter Lock Task Mode", e)
        }
    }

    private fun exitIfUnlocked(reason: String): Boolean {
        if (exiting) return true
        // The runtime owns the durable unlock. If a later event has already armed a
        // new lock, an older request must not close this Activity or clear that lock.
        if (pinStore.isBoardGuardLocked()) return false

        exiting = true
        Log.i(TAG, "BOARDGUARD_LOCK_CLOSED reason=$reason")

        try {
            stopLockTask()
        } catch (e: Exception) {
            Log.w(TAG, "stopLockTask failed or Lock Task Mode was already stopped", e)
        }

        isLockActive = false
        finishAndRemoveTask()
        return true
    }

    private fun ensureMonitorServiceRunning() {
        try {
            if (pinStore.hasPin() && pinStore.isProtectionEnabled()) {
                ScreenMonitorService.startService(this)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure ScreenMonitorService is running", e)
        }
    }

    private fun lockTaskStateName(state: Int): String {
        return when (state) {
            ActivityManager.LOCK_TASK_MODE_LOCKED -> "LOCKED"
            ActivityManager.LOCK_TASK_MODE_PINNED -> "PINNED"
            ActivityManager.LOCK_TASK_MODE_NONE -> "NONE"
            else -> state.toString()
        }
    }

    companion object {
        private const val TAG = "BoardGuardLock"

        @Volatile
        var isLockActive: Boolean = false
    }
}

@Composable
private fun LockScreenContent(
    onUnlocked: () -> Unit,
    verifyPin: suspend (String) -> LockerUnlockResult,
    remainingBlockSeconds: () -> Long
) {
    // Intercept back button completely
    BackHandler(enabled = true) {
        // Do nothing while locked
    }

    var enteredPin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isVerifying by remember { mutableStateOf(false) }
    var blockedSeconds by remember { mutableStateOf(remainingBlockSeconds()) }
    val scope = rememberCoroutineScope()
    val incorrectPassword = stringResource(R.string.incorrect_password)
    val canEnterPin = !isVerifying && blockedSeconds == 0L

    LaunchedEffect(Unit) {
        while (isActive) {
            blockedSeconds = remainingBlockSeconds()
            delay(1_000L)
        }
    }

    val statusMessage = when {
        isVerifying -> "Checking password..."
        blockedSeconds > 0L -> "Too many attempts. Try again in $blockedSeconds seconds."
        else -> errorMessage
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1B222C)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .widthIn(max = 500.dp)
                .padding(24.dp)
        ) {
            // Centered light card for header and PIN dots
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp, horizontal = 24.dp)
                ) {
                    Image(painterResource(R.drawable.fttego_logo), contentDescription = "")

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = stringResource(R.string.input_password_to_unlock),
                        fontSize = 15.sp,
                        color = Color(0xFF757575)
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // 4 PIN Boxes
                    PinInputBoxes(
                        pinValue = enteredPin,
                        onPinChange = {},
                        isError = errorMessage != null || blockedSeconds > 0L,
                        useSoftKeyboard = false
                    )

                    if (statusMessage != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = statusMessage,
                            color = if (isVerifying) Color(0xFF757575) else MaterialTheme.colorScheme.error,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Large 3x4 Custom Keypad
            CustomNumericKeypad(
                onDigitClick = { digit ->
                    if (!isVerifying && blockedSeconds == 0L && enteredPin.length < 4) {
                        errorMessage = null
                        enteredPin += digit
                    }
                },
                onDeleteClick = {
                    if (!isVerifying && blockedSeconds == 0L && enteredPin.isNotEmpty()) {
                        errorMessage = null
                        enteredPin = enteredPin.dropLast(1)
                    }
                },
                onOkClick = {
                    if (!isVerifying && blockedSeconds == 0L && enteredPin.length == 4) {
                        val submittedPin = enteredPin
                        isVerifying = true
                        errorMessage = null
                        scope.launch {
                            try {
                                when (val result = verifyPin(submittedPin)) {
                                    LockerUnlockResult.Unlocked, LockerUnlockResult.Disabled -> onUnlocked()
                                    LockerUnlockResult.Incorrect -> errorMessage = incorrectPassword
                                    is LockerUnlockResult.Blocked -> {
                                        blockedSeconds = maxOf(result.retryAfterSeconds, remainingBlockSeconds())
                                    }
                                    is LockerUnlockResult.Unavailable -> {
                                        errorMessage = verificationFailureMessage(result.reason)
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                // Do not close the lock on an unexpected network/storage failure.
                                errorMessage = "Unable to verify password. Please try again."
                            } finally {
                                enteredPin = ""
                                isVerifying = false
                            }
                        }
                    }
                },
                isOkEnabled = canEnterPin && enteredPin.length == 4,
                modifier = Modifier.alpha(if (canEnterPin) 1f else 0.5f)
            )
        }
    }
}

private fun verificationFailureMessage(reason: LockerFailure): String = when (reason) {
    LockerFailure.NETWORK -> "Unable to verify password offline. Please reconnect and try again."
    LockerFailure.SERVICE_UNAVAILABLE -> "Password verification is temporarily unavailable. Please try again."
    LockerFailure.RATE_LIMITED -> "Please wait before trying again."
    LockerFailure.AUTHORIZATION, LockerFailure.NOT_REGISTERED ->
        "Screen registration needs attention. Please contact the administrator."
    LockerFailure.INVALID_CLOCK -> "Check the screen date and time, then try again."
    LockerFailure.STORAGE -> "Unable to save the unlock. Please try again."
    LockerFailure.INVALID_RESPONSE, LockerFailure.UNKNOWN -> "Unable to verify password. Please try again."
}
