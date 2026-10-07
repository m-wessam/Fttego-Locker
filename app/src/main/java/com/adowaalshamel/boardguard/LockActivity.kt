package com.adowaalshamel.boardguard

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
 * Full-screen secondary lock Activity running inside Android Lock Task Mode.
 * Requires the correct 4-digit BoardGuard PIN to dismiss.
 */
class LockActivity : ComponentActivity() {

    private lateinit var pinStore: PinStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        isLockActive = true
        pinStore = PinStore(this)

        // Prevent screenshots / recents preview
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        // Ensure Lock Task Mode is configured & started if Device Owner
        if (BoardGuardDeviceAdminReceiver.isDeviceOwner(this)) {
            BoardGuardDeviceAdminReceiver.setupLockTaskMode(this)
            try {
                startLockTask()
            } catch (e: Exception) {
                // Defensive fallback if lock task fails
            }
        }

        setContent {
            BoardGuardTheme {
                LockScreenContent(
                    onUnlockSuccess = {
                        unlockAndExit()
                    },
                    verifyPin = { pin ->
                        pinStore.verifyPin(pin)
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isLockActive = true
        if (BoardGuardDeviceAdminReceiver.isDeviceOwner(this)) {
            try {
                startLockTask()
            } catch (_: Exception) {
            }
        }
    }

    override fun onDestroy() {
        isLockActive = false
        super.onDestroy()
    }

    private fun unlockAndExit() {
        try {
            stopLockTask()
        } catch (_: Exception) {
        }
        isLockActive = false
        finish()
    }

    companion object {
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
                    // Shield Icon
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E88E5)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "🛡",
                            fontSize = 32.sp,
                            color = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = stringResource(R.string.app_name),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1A1C1E)
                    )

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
