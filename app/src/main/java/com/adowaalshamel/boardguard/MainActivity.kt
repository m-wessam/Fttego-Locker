package com.adowaalshamel.boardguard

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.adowaalshamel.boardguard.ui.CustomNumericKeypad
import com.adowaalshamel.boardguard.ui.PinInputBoxes
import com.adowaalshamel.boardguard.ui.theme.BoardGuardTheme
import com.adowaalshamel.boardguard.update.AppUpdateController
import com.adowaalshamel.boardguard.update.UpdateUiState
import kotlinx.coroutines.launch
import java.time.Year

/**
 * Main activity for BoardGuard configuration & administrator authentication.
 */
class MainActivity : ComponentActivity() {

    private lateinit var pinStore: PinStore
    private var isAuthenticated by mutableStateOf(false)
    private var showPinEntry by mutableStateOf(false)
    private var hasPinState by mutableStateOf(false)
    private var homeOpenCount by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pinStore = PinStore(this)
        hasPinState = pinStore.hasPin()

        // Set secure flag to protect sensitive views from recent activity snapshots
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        setContent {
            BoardGuardTheme {
                val context = LocalContext.current
                val isDeviceOwner =
                    remember { BoardGuardDeviceAdminReceiver.isDeviceOwner(context) }

                when {
                    !hasPinState -> {
                        // First installation: there is no teacher PIN yet, so open the
                        // existing password setup screen directly without admin auth.
                        MainSettingsScreen(
                            pinStore = pinStore,
                            isDeviceOwner = isDeviceOwner,
                            onUninstallClicked = {
                                performUninstallFlow(context)
                            },
                            onPinSaved = { isFirstPin ->
                                hasPinState = true
                                if (isFirstPin) {
                                    lockImmediatelyAfterFirstPinSetup()
                                }
                            }
                        )
                    }

                    isAuthenticated -> {
                        // Only a successful admin PIN check can open settings after
                        // the first teacher PIN has already been created.
                        BackHandler {
                            isAuthenticated = false
                        }
                        MainSettingsScreen(
                            pinStore = pinStore,
                            isDeviceOwner = isDeviceOwner,
                            onUninstallClicked = {
                                performUninstallFlow(context)
                            },
                            onPinSaved = {
                                hasPinState = true
                            }
                        )
                    }

                    showPinEntry -> {
                        BackHandler {
                            showPinEntry = false
                        }
                        AdminAuthScreen(
                            onAuthenticated = {
                                showPinEntry = false
                                isAuthenticated = true
                            },
                            verifyPin = { pin -> pinStore.verifyAdminPin(pin) }
                        )
                    }

                    else -> {
                        SimpleHomeScreen(
                            homeOpenCount = homeOpenCount,
                            onSettingsClick = {
                                showPinEntry = true
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hasPinState = pinStore.hasPin()
        // Checking is triggered by opening the app, not by a scheduled task.
        homeOpenCount++
    }

    override fun onStop() {
        super.onStop()
        // Never keep technician authorization after leaving the app, including
        // the first setup before a teacher PIN has been created.
        isAuthenticated = false
        showPinEntry = false
    }

    override fun onStart() {
        super.onStart()

        if (
            pinStore.hasPin() &&
            pinStore.isProtectionEnabled() &&
            BoardGuardDeviceAdminReceiver.isDeviceOwner(this)
        ) {
            ScreenMonitorService.startService(this)
        }
    }

    private fun lockImmediatelyAfterFirstPinSetup() {
        // The first PIN has just been persisted and protection was enabled by the
        // existing settings logic. Arm the same persisted lock state used by the
        // normal ScreenMonitorService flow, then show the existing LockActivity.
        pinStore.setBoardGuardLocked(true)

        // Re-send the start command after arming the lock so the existing service
        // can relaunch LockActivity if the direct launch is interrupted by the OEM.
        if (BoardGuardDeviceAdminReceiver.isDeviceOwner(this)) {
            ScreenMonitorService.startService(this)
        }

        val lockIntent = Intent(this, LockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }

        try {
            Log.i("MainActivity", "FIRST_PIN_CREATED -> LAUNCHING_BOARDGUARD")
            startActivity(lockIntent)
            finishAndRemoveTask()
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to launch lock after first PIN setup", e)
        }
    }

    private fun performUninstallFlow(context: Context) {
        try {
            // 1. Disable protection & stop service
            pinStore.setProtectionEnabled(false)
            pinStore.setBoardGuardLocked(false)
            ScreenMonitorService.stopService(context)

            // 2. Clear lock task packages
            BoardGuardDeviceAdminReceiver.clearLockTaskPackages(context)

            // 3. Attempt to clear Device Owner status
            if (BoardGuardDeviceAdminReceiver.isDeviceOwner(context)) {
                val dpm = context.getSystemService(DEVICE_POLICY_SERVICE) as? DevicePolicyManager
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
 * Simple home screen shown when a PIN already exists.
 * Displays app branding and a single "Settings" button.
 */
@Composable
private fun SimpleHomeScreen(
    homeOpenCount: Int,
    onSettingsClick: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updateState by AppUpdateController.state.collectAsState()

    // No periodic checks: run only when this home screen is opened.
    LaunchedEffect(homeOpenCount) {
        Log.i("FttegoUpdater", "HOME_LAUNCHED homeOpenCount=$homeOpenCount")
        if (homeOpenCount > 0) {
            AppUpdateController.checkWhenHomeOpens(context.applicationContext)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFFF4F6F9)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        painter = painterResource(R.drawable.fttego_logo),
                        contentDescription = stringResource(R.string.app_name)
                    )

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

                    Button(
                        onClick = onSettingsClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1E88E5)
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.button_settings),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // The update button only appears when GitHub has a newer release.
                    val availableRelease = when (val current = updateState) {
                        is UpdateUiState.Available -> current.release
                        is UpdateUiState.Failed -> current.release
                        else -> null
                    }
                    if (availableRelease != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    AppUpdateController.install(
                                        context.applicationContext,
                                        availableRelease
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.button_update_app),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }

                    when (val current = updateState) {
                        is UpdateUiState.Downloading -> {
                            Spacer(modifier = Modifier.height(12.dp))
                            LinearProgressIndicator(
                                progress = { current.percent / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                text = stringResource(R.string.update_downloading, current.percent),
                                fontSize = 12.sp,
                                color = Color(0xFF757575),
                            )
                        }
                        UpdateUiState.Installing -> {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.update_installing),
                                fontSize = 12.sp,
                                color = Color(0xFF757575),
                            )
                        }
                        is UpdateUiState.Failed -> {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = current.message,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                            )
                        }
                        else -> Unit
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        text = "${stringResource(R.string.version)} ${BuildConfig.VERSION_NAME}             © ${Year.now().value} FTTEGO",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Light,
                        color = Color(0xFF383535)
                    )
                }
            }
        }
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
                        Text(
                            text = stringResource(R.string.app_name),
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1A1C1E)
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = stringResource(R.string.enter_admin_pin),
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
    onUninstallClicked: () -> Unit,
    onPinSaved: ((Boolean) -> Unit)? = null
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
    val isFormValid =
        passwordInput.length == 4 && confirmPasswordInput.length == 4 &&
                passwordsMatch && !pinStore.isReservedPin(passwordInput)
    val showMismatchError = confirmPasswordInput.length == 4 && !passwordsMatch
    val showReservedPinError = passwordsMatch && pinStore.isReservedPin(passwordInput)

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

                        Image(
                            painterResource(R.drawable.fttego_logo),
                            contentDescription = ""
                        )

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

                        if (showReservedPinError) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.error_reserved_master_pin),
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
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

                                    onPinSaved?.invoke(isFirstPin)

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
                                            if (isProtectionActive) Color(0xFF4CAF50) else Color(
                                                0xFFB0BEC5
                                            )
                                        )
                                )

                                Spacer(modifier = Modifier.width(6.dp))

                                Text(
                                    text = if (isProtectionActive) stringResource(R.string.status_active) else stringResource(
                                        R.string.status_inactive
                                    ),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = if (isProtectionActive) Color(0xFF4CAF50) else Color(
                                        0xFF78909C
                                    )
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
                                        pinStore.setBoardGuardLocked(false)
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
                                brush = SolidColor(Color(0xFFFFCDD2))
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

                        Spacer(modifier = Modifier.height(24.dp))

                        Text(
                            text = "${stringResource(R.string.version)} ${BuildConfig.VERSION_NAME}             © ${Year.now().value} FTTEGO",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Light,
                            color = Color(0xFF383535)
                        )

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
