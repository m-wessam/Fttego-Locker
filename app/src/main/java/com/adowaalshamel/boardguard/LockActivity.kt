package com.adowaalshamel.boardguard

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adowaalshamel.boardguard.ui.CustomNumericKeypad
import com.adowaalshamel.boardguard.ui.PinInputBoxes
import com.adowaalshamel.boardguard.ui.theme.BoardGuardTheme

/**
 * Full-screen secondary lock Activity.
 * Only a valid BoardGuard PIN clears the persisted lock state.
 */
class LockActivity : ComponentActivity() {

    private lateinit var pinStore: PinStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pinStore = PinStore(this)
        isLockActive = true

        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        if (!pinStore.isBoardGuardLocked()) {
            Log.w(TAG, "LockActivity opened while persisted lock state is false; closing")
            isLockActive = false
            finishAndRemoveTask()
            return
        }

        BoardGuardDeviceAdminReceiver.setupLockTaskMode(this)
        ensureLockTaskMode()

        setContent {
            BoardGuardTheme {
                LockScreenContent(
                    onUnlockSuccess = { unlockAndExit() },
                    verifyPin = { pin -> pinStore.verifyScreenPin(pin) }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        isLockActive = true
    }

    override fun onResume() {
        super.onResume()
        isLockActive = true
        ensureLockTaskMode()
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

    private fun unlockAndExit() {
        pinStore.setBoardGuardLocked(false)
        Log.i(TAG, "BOARDGUARD_UNLOCKED_BY_VALID_PIN")

        try {
            stopLockTask()
        } catch (e: Exception) {
            Log.w(TAG, "stopLockTask failed or Lock Task Mode was already stopped", e)
        }

        isLockActive = false
        finishAndRemoveTask()
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
    onUnlockSuccess: () -> Unit,
    verifyPin: (String) -> Boolean
) {
    // Intercept back button completely
    BackHandler(enabled = true) {
        // Do nothing while locked
    }

    var enteredPin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

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
                        isError = errorMessage != null,
                        useSoftKeyboard = false
                    )

                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = errorMessage!!,
                            color = MaterialTheme.colorScheme.error,
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
                    if (enteredPin.length < 4) {
                        errorMessage = null
                        val newPin = enteredPin + digit
                        enteredPin = newPin

                        // Auto-check on 4th digit option or require OK tap
                    }
                },
                onDeleteClick = {
                    if (enteredPin.isNotEmpty()) {
                        errorMessage = null
                        enteredPin = enteredPin.dropLast(1)
                    }
                },
                onOkClick = {
                    if (enteredPin.length == 4) {
                        if (verifyPin(enteredPin)) {
                            onUnlockSuccess()
                        } else {
                            errorMessage = "Incorrect password"
                            enteredPin = ""
                        }
                    }
                },
                isOkEnabled = enteredPin.length == 4
            )
        }
    }
}
