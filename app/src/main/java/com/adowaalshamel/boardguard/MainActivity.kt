package com.adowaalshamel.boardguard

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adowaalshamel.boardguard.ui.CustomNumericKeypad
import com.adowaalshamel.boardguard.ui.PinInputBoxes
import com.adowaalshamel.boardguard.ui.theme.BoardGuardTheme
import kotlinx.coroutines.launch
import androidx.core.net.toUri

/**
 * Main activity for BoardGuard configuration & administrator authentication.
 */
class MainActivity : ComponentActivity() {

    private lateinit var pinStore: PinStore
    private var isAuthenticated by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pinStore = PinStore(this)

        // Set secure flag to protect sensitive views from recent activity snapshots
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        setContent {
            BoardGuardTheme {
                val context = LocalContext.current
                val isDeviceOwner = remember { BoardGuardDeviceAdminReceiver.isDeviceOwner(context) }
                val hasExistingPin = remember(isAuthenticated) { pinStore.hasPin() }

                if (hasExistingPin && !isAuthenticated) {
                    // Show Admin PIN Authentication Screen before allowing access to settings
                    AdminAuthScreen(
                        onAuthenticated = {
                            isAuthenticated = true
                        },
                        verifyPin = { pin ->
                            pinStore.verifyPin(pin)
                        }
                    )
                } else {
                    // Show Settings Screen
                    MainSettingsScreen(
                        pinStore = pinStore,
                        isDeviceOwner = isDeviceOwner,
                        onUninstallClicked = {
                            performUninstallFlow(context)
                        }
                    )
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Reset authentication state whenever user leaves settings screen
        if (pinStore.hasPin()) {
            isAuthenticated = false
        }
    }

    private fun performUninstallFlow(context: Context) {
        try {
            // 1. Disable protection & stop service
            pinStore.setProtectionEnabled(false)
            ScreenMonitorService.stopService(context)

            // 2. Clear lock task packages
            BoardGuardDeviceAdminReceiver.clearLockTaskPackages(context)

            // 3. Attempt to clear Device Owner status
            if (BoardGuardDeviceAdminReceiver.isDeviceOwner(context)) {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                dpm?.clearDeviceOwnerApp(context.packageName)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error removing Device Owner state during uninstall", e)
            Toast.makeText(context, R.string.uninstall_device_owner_error, Toast.LENGTH_LONG).show()
        }

        // 4. Launch Android system uninstall dialog
        val uninstallIntent = Intent(Intent.ACTION_DELETE).apply {
            data = "package:${context.packageName}".toUri()
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(uninstallIntent)
    }
}

/**
 * Admin Authentication Screen required before opening settings if a PIN exists.
 */
@Composable
private fun AdminAuthScreen(
    onAuthenticated: () -> Unit,
    verifyPin: (String) -> Boolean
) {
    var enteredPin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFFF8F9FA)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .widthIn(max = 500.dp)
                    .padding(24.dp)
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(20.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp, horizontal = 24.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1E88E5)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = "🛡", fontSize = 32.sp, color = Color.White)
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
                            text = stringResource(R.string.enter_current_pin),
                            fontSize = 15.sp,
                            color = Color(0xFF757575)
                        )

                        Spacer(modifier = Modifier.height(24.dp))

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

                CustomNumericKeypad(
                    onDigitClick = { digit ->
                        if (enteredPin.length < 4) {
                            errorMessage = null
                            enteredPin += digit
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
                                onAuthenticated()
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
}

/**
 * Main Settings & Configuration Screen matching mockup specifications.
 */
@Composable
private fun MainSettingsScreen(
    pinStore: PinStore,
    isDeviceOwner: Boolean,
    onUninstallClicked: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    var passwordInput by remember { mutableStateOf("") }
    var confirmPasswordInput by remember { mutableStateOf("") }
    var isProtectionActive by remember { mutableStateOf(pinStore.isProtectionEnabled()) }
    var showUninstallDialog by remember { mutableStateOf(false) }

    val hasPin = pinStore.hasPin()
    val passwordsMatch = passwordInput.isNotEmpty() && passwordInput == confirmPasswordInput
    val isFormValid = passwordInput.length == 4 && confirmPasswordInput.length == 4 && passwordsMatch
    val showMismatchError = confirmPasswordInput.length == 4 && !passwordsMatch

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color(0xFFF4F6F9)
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    })
                }
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                // Warning if Device Owner is missing
                if (!isDeviceOwner) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3CD)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 20.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.warning_device_owner_required),
                                color = Color(0xFF856404),
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.warning_device_owner_subtext),
                                color = Color(0xFF856404),
                                fontSize = 13.sp
                            )
                        }
                    }
                }

                // Main White Centered Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(20.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Shield Icon
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1E88E5)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = "🛡", fontSize = 32.sp, color = Color.White)
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = stringResource(R.string.app_name),
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1A1C1E)
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = stringResource(R.string.subtitle),
                            fontSize = 15.sp,
                            color = Color(0xFF757575)
                        )

                        Spacer(modifier = Modifier.height(32.dp))

                        // Password Input
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.Start
                        ) {
                            Text(
                                text = stringResource(R.string.label_password),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                color = Color(0xFF333333)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            PinInputBoxes(
                                pinValue = passwordInput,
                                onPinChange = { passwordInput = it },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        // Confirm Password Input
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.Start
                        ) {
                            Text(
                                text = stringResource(R.string.label_confirm_password),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                color = Color(0xFF333333)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            PinInputBoxes(
                                pinValue = confirmPasswordInput,
                                onPinChange = { confirmPasswordInput = it },
                                isError = showMismatchError,
                                modifier = Modifier.fillMaxWidth()
                            )

                            if (showMismatchError) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "❗ ",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    Text(
                                        text = stringResource(R.string.error_passwords_mismatch),
                                        color = MaterialTheme.colorScheme.error,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(28.dp))

                        // SET PASSWORD Button
                        Button(
                            onClick = {
                                if (isFormValid) {
                                    focusManager.clearFocus()
                                    keyboardController?.hide()

                                    val isFirstPin = !pinStore.hasPin()
                                    pinStore.setPin(passwordInput)

                                    if (isFirstPin) {
                                        // Auto-activate on first PIN creation
                                        pinStore.setProtectionEnabled(true)
                                        isProtectionActive = true
                                        if (isDeviceOwner) {
                                            ScreenMonitorService.startService(context)
                                        }
                                    }

                                    passwordInput = ""
                                    confirmPasswordInput = ""

                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            context.getString(R.string.toast_password_saved)
                                        )
                                    }
                                }
                            },
                            enabled = isFormValid,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF1E88E5)
                            )
                        ) {
                            Text(
                                text = stringResource(R.string.button_set_password),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(28.dp))
                        HorizontalDivider(color = Color(0xFFEEEEEE), thickness = 1.dp)
                        Spacer(modifier = Modifier.height(24.dp))

                        // Protection Status Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(R.string.protection_status),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp,
                                    color = Color(0xFF333333)
                                )

                                Spacer(modifier = Modifier.width(12.dp))

                                // Status Dot
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isProtectionActive) Color(0xFF4CAF50) else Color(0xFFB0BEC5)
                                        )
                                )

                                Spacer(modifier = Modifier.width(6.dp))

                                Text(
                                    text = if (isProtectionActive) stringResource(R.string.status_active) else stringResource(R.string.status_inactive),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = if (isProtectionActive) Color(0xFF4CAF50) else Color(0xFF78909C)
                                )
                            }

                            Switch(
                                checked = isProtectionActive,
                                onCheckedChange = { active ->
                                    if (active) {
                                        if (pinStore.hasPin() && isDeviceOwner) {
                                            pinStore.setProtectionEnabled(true)
                                            isProtectionActive = true
                                            ScreenMonitorService.startService(context)
                                        } else if (!isDeviceOwner) {
                                            Toast.makeText(
                                                context,
                                                R.string.warning_device_owner_required,
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    } else {
                                        pinStore.setProtectionEnabled(false)
                                        isProtectionActive = false
                                        ScreenMonitorService.stopService(context)
                                    }
                                },
                                enabled = isDeviceOwner && pinStore.hasPin(),
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Color(0xFF1E88E5)
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(24.dp))
                        HorizontalDivider(color = Color(0xFFEEEEEE), thickness = 1.dp)
                        Spacer(modifier = Modifier.height(24.dp))

                        // Uninstall App Button
                        OutlinedButton(
                            onClick = { showUninstallDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFFD32F2F)
                            ),
                            border = ButtonDefaults.outlinedButtonBorder.copy(
                                brush = androidx.compose.ui.graphics.SolidColor(Color(0xFFFFCDD2))
                            )
                        ) {
                            Text(text = "🗑 ", fontSize = 16.sp)
                            Text(
                                text = stringResource(R.string.button_uninstall_app),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFD32F2F)
                            )
                        }
                    }
                }
            }
        }
    }

    // Uninstall Confirmation Dialog
    if (showUninstallDialog) {
        AlertDialog(
            onDismissRequest = { showUninstallDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.uninstall_dialog_title),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(text = stringResource(R.string.uninstall_dialog_message))
            },
            confirmButton = {
                Button(
                    onClick = {
                        showUninstallDialog = false
                        onUninstallClicked()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) {
                    Text(text = stringResource(R.string.action_uninstall), color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUninstallDialog = false }) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            }
        )
    }
}
