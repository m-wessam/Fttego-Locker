package com.adowaalshamel.boardguard

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.adowaalshamel.boardguard.backend.LockerRuntime
import com.adowaalshamel.boardguard.ui.theme.BoardGuardTheme
import com.adowaalshamel.boardguard.update.AppUpdateController
import com.adowaalshamel.boardguard.update.LockerRelease
import com.adowaalshamel.boardguard.update.UpdateUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Launcher page for app information and an explicitly requested update. */
class MainActivity : ComponentActivity() {
    private lateinit var runtime: LockerRuntime
    private var homeVisible by mutableStateOf(false)
    private var redirectingToLock = false
    private var checkJob: Job? = null
    private var installJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runtime = LockerRuntime.get(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        // A launcher entry must never provide another page while the screen is locked.
        if (redirectIfLocked()) return

        homeVisible = true
        setContent {
            BoardGuardTheme {
                if (homeVisible) {
                    val updateState by AppUpdateController.state.collectAsState()
                    AppHomeScreen(updateState, ::installUpdate)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                runtime.policyRevision.collect { redirectIfLocked() }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ensureMonitorService()
    }

    override fun onResume() {
        super.onResume()
        if (!redirectIfLocked()) checkForUpdate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!redirectIfLocked()) checkForUpdate()
    }

    override fun onStop() {
        if (::runtime.isInitialized && runtime.pinStore.isBoardGuardLocked()) {
            checkJob?.cancel()
            installJob?.cancel()
        }
        super.onStop()
    }

    private fun checkForUpdate() {
        if (isFinishing || checkJob?.isActive == true) return
        checkJob = lifecycleScope.launch {
            AppUpdateController.checkWhenHomeOpens(applicationContext)
        }
    }

    private fun installUpdate(release: LockerRelease) {
        if (redirectIfLocked() || isFinishing || installJob?.isActive == true) return
        installJob = lifecycleScope.launch {
            AppUpdateController.install(applicationContext, release)
        }
    }

    private fun redirectIfLocked(): Boolean {
        if (redirectingToLock) return true
        if (!runtime.pinStore.isBoardGuardLocked()) return false

        redirectingToLock = true
        homeVisible = false
        checkJob?.cancel()
        installJob?.cancel()
        ensureMonitorService()
        try {
            startActivity(Intent(this, LockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            })
        } catch (_: RuntimeException) {
            // The monitor service also restores the persisted lock; the home page stays hidden.
            Log.w(TAG, "Unable to open the lock directly from the launcher")
        } finally {
            finish()
        }
        return true
    }

    private fun ensureMonitorService() {
        if (!BoardGuardDeviceAdminReceiver.isDeviceOwner(this)) return
        try {
            // Monitoring must also run before the first password and while policy is disabled.
            ScreenMonitorService.startService(this)
        } catch (_: RuntimeException) {
            Log.w(TAG, "Unable to start screen monitoring from the launcher")
        }
    }

    companion object {
        private const val TAG = "FttegoHome"
    }
}

@Composable
private fun AppHomeScreen(updateState: UpdateUiState, onUpdate: (LockerRelease) -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF1B222C)),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier.widthIn(max = 500.dp).fillMaxWidth().padding(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    painter = painterResource(R.drawable.fttego_logo),
                    contentDescription = null,
                    modifier = Modifier.height(72.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.app_name),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1A1C1E),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.app_description),
                    fontSize = 15.sp,
                    color = Color(0xFF757575),
                    textAlign = TextAlign.Center,
                )

                val release = when (updateState) {
                    is UpdateUiState.Available -> updateState.release
                    is UpdateUiState.Failed -> updateState.release
                    else -> null
                }
                if (release != null) {
                    Spacer(Modifier.height(24.dp))
                    OutlinedButton(
                        onClick = { onUpdate(release) },
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

                when (updateState) {
                    is UpdateUiState.Downloading -> {
                        Spacer(Modifier.height(24.dp))
                        LinearProgressIndicator(
                            progress = { updateState.percent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.update_downloading, updateState.percent),
                            fontSize = 12.sp,
                            color = Color(0xFF757575),
                        )
                    }
                    UpdateUiState.Installing -> {
                        Spacer(Modifier.height(24.dp))
                        Text(
                            text = stringResource(R.string.update_installing),
                            fontSize = 12.sp,
                            color = Color(0xFF757575),
                            textAlign = TextAlign.Center,
                        )
                    }
                    is UpdateUiState.Failed -> {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = updateState.message,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }
                    else -> Unit
                }
            }
        }
    }
}
