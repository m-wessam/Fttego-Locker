package com.adowaalshamel.boardguard.update

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.util.Log
import com.adowaalshamel.boardguard.BoardGuardDeviceAdminReceiver
import com.adowaalshamel.boardguard.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A check is started only when the user opens the application's Settings entry screen. */
sealed interface UpdateUiState {
    data object Hidden : UpdateUiState
    data object Checking : UpdateUiState
    data class Available(val release: LockerRelease) : UpdateUiState
    data class Downloading(val percent: Int) : UpdateUiState
    data object Installing : UpdateUiState
    data class Failed(val release: LockerRelease?, val message: String) : UpdateUiState
}

data class LockerRelease(
    val versionCode: Long,
    val apkUrl: String,
)

/** No background worker, scheduler or service is used. */
object AppUpdateController {
    private const val TAG = "FttegoUpdater"

    // This is the same GitHub repository / asset used by LockerInstaller.
    private const val GITHUB_OWNER = "m-wessam"
    private const val GITHUB_REPO = "Fttego-Locker"
    private const val APK_NAME = "locker.apk"
    private const val RELEASE_API =
        "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val MAX_APK_BYTES = 150L * 1024L * 1024L
    private const val MAX_REDIRECTS = 8

    private val mutableState = MutableStateFlow<UpdateUiState>(UpdateUiState.Hidden)
    val state = mutableState.asStateFlow()
    private var checkInFlight = false

    suspend fun checkWhenHomeOpens(context: Context) {
        if (checkInFlight || mutableState.value is UpdateUiState.Downloading ||
            mutableState.value is UpdateUiState.Installing
        ) return

        // This feature is intended for an already provisioned screen only.
        if (!BoardGuardDeviceAdminReceiver.isDeviceOwner(context)) {
            mutableState.value = UpdateUiState.Hidden
            return
        }

        checkInFlight = true
        mutableState.value = UpdateUiState.Checking
        try {
            val release = withContext(Dispatchers.IO) {
                try {
                    fetchLatestRelease()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Update check failed; the locker remains usable", e)
                    null
                }
            }
            mutableState.value = if (release != null &&
                release.versionCode > BuildConfig.VERSION_CODE.toLong()
            ) {
                UpdateUiState.Available(release)
            } else {
                UpdateUiState.Hidden
            }
        } finally {
            checkInFlight = false
        }
    }

    suspend fun install(context: Context, release: LockerRelease) {
        val current = mutableState.value
        if (current is UpdateUiState.Downloading || current is UpdateUiState.Installing) return

        lastAttemptedRelease = release
        mutableState.value = UpdateUiState.Downloading(0)
        withContext(Dispatchers.IO) {
            var downloadedApk: File? = null
            try {
                require(BoardGuardDeviceAdminReceiver.isDeviceOwner(context)) {
                    "This screen must be managed by Fttego Locker to update it."
                }
                require(release.versionCode > BuildConfig.VERSION_CODE.toLong()) {
                    "This version is already installed."
                }

                downloadedApk = downloadApk(context, release.apkUrl)
                verifyApk(context, downloadedApk, release.versionCode)
                mutableState.value = UpdateUiState.Installing
                commitUpdate(context, downloadedApk)
                // The result is delivered to UpdateInstallReceiver. A successful
                // self-update can terminate this process before UI callbacks run.
            } catch (e: CancellationException) {
                mutableState.value = UpdateUiState.Available(release)
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Update failed before PackageInstaller finished", e)
                mutableState.value = UpdateUiState.Failed(
                    release = release,
                    message = friendlyError(e),
                )
            } finally {
                downloadedApk?.delete()
            }
        }
    }

    internal fun installationResult(context: Context, status: Int, message: String?) {
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                lastAttemptedRelease = null
                mutableState.value = if (BoardGuardDeviceAdminReceiver.isDeviceOwner(context)) {
                    UpdateUiState.Hidden
                } else {
                    UpdateUiState.Failed(
                        null,
                        "Update finished, but device management needs administrator attention."
                    )
                }
            }
            else -> {
                val previous = mutableState.value
                val release = when (previous) {
                    is UpdateUiState.Available -> previous.release
                    is UpdateUiState.Failed -> previous.release
                    else -> lastAttemptedRelease
                }
                Log.e(TAG, "PackageInstaller failed: status=$status detail=${message.orEmpty()}")
                mutableState.value = UpdateUiState.Failed(release, installerError(status))
            }
        }
    }

    private var lastAttemptedRelease: LockerRelease? = null

    private fun fetchLatestRelease(): LockerRelease? {
        val connection = openHttpsConnection(RELEASE_API)
        val response = try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("GitHub responded with HTTP ${connection.responseCode}")
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
        val json = JSONObject(response)
        val body = json.optString("body", "")
        // Publish e.g. "versionCode=2" on its own line in the Release description.
        val versionCode = Regex(
            pattern = "(?m)^\\s*versionCode\\s*=\\s*(\\d+)\\s*$",
            option = RegexOption.IGNORE_CASE,
        ).find(body)?.groupValues?.get(1)?.toLongOrNull()

        if (versionCode == null) {
            Log.w(TAG, "Latest GitHub Release has no valid versionCode=N in its description")
            return null
        }
        if (versionCode <= BuildConfig.VERSION_CODE.toLong()) return null

        val assets = json.optJSONArray("assets") ?: return null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            if (asset.optString("name") != APK_NAME) continue
            val url = asset.optString("browser_download_url")
            if (!isTrustedReleaseAssetUrl(url)) {
                Log.w(TAG, "Release APK URL is not from the configured repository")
                return null
            }
            return LockerRelease(versionCode, url)
        }
        Log.w(TAG, "Latest GitHub Release does not contain $APK_NAME")
        return null
    }

    private suspend fun downloadApk(context: Context, url: String): File {
        require(isTrustedReleaseAssetUrl(url)) { "Invalid GitHub APK download URL" }
        val directory = File(context.cacheDir, "fttego_updates").apply { mkdirs() }
        val partial = File(directory, "locker.apk.part")
        val destination = File(directory, "locker.apk")
        partial.delete()
        destination.delete()

        try {
            followDownloadRedirects(url) { connection ->
                val length = connection.contentLengthLong
                if (length > MAX_APK_BYTES) throw IOException("APK exceeds allowed size")
                var received = 0L
                connection.inputStream.use { input ->
                    FileOutputStream(partial).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count == -1) break
                            received += count
                            if (received > MAX_APK_BYTES) {
                                throw IOException("APK exceeds allowed size")
                            }
                            output.write(buffer, 0, count)
                            if (length > 0L) {
                                val percent = ((received * 100) / length).toInt().coerceIn(0, 100)
                                val previous = mutableState.value
                                if (previous !is UpdateUiState.Downloading ||
                                    percent >= previous.percent + 2 || percent == 100
                                ) {
                                    mutableState.value = UpdateUiState.Downloading(percent)
                                }
                            }
                        }
                        output.fd.sync()
                    }
                }
                if (received == 0L || (length > 0L && received != length)) {
                    throw IOException("The update download is incomplete")
                }
            }
            if (!partial.renameTo(destination)) {
                throw IOException("Unable to save the downloaded update")
            }
            return destination
        } finally {
            partial.delete()
        }
    }

    private fun verifyApk(context: Context, apk: File, expectedVersionCode: Long) {
        val pm = context.packageManager
        val archiveFlags = PackageManager.PackageInfoFlags.of(
            (PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_RECEIVERS).toLong()
        )
        val installedFlags = PackageManager.PackageInfoFlags.of(
            PackageManager.GET_SIGNING_CERTIFICATES.toLong()
        )
        val incoming = pm.getPackageArchiveInfo(apk.absolutePath, archiveFlags)
            ?: throw IOException("The downloaded file is not a valid APK")
        val installed = pm.getPackageInfo(context.packageName, installedFlags)

        if (incoming.packageName != context.packageName) {
            throw IOException("This update belongs to a different application")
        }
        if (incoming.longVersionCode != expectedVersionCode ||
            incoming.longVersionCode <= installed.longVersionCode
        ) {
            throw IOException("The downloaded APK version does not match the published update")
        }

        // Requiring the existing receiver prevents an update from breaking Device Owner.
        val expectedReceiver = BoardGuardDeviceAdminReceiver::class.java.name
        val receiverIsPreserved = incoming.receivers.orEmpty().any { receiver ->
            receiver.name == expectedReceiver &&
                receiver.permission == Manifest.permission.BIND_DEVICE_ADMIN
        }
        if (!receiverIsPreserved) {
            throw IOException("The update does not include the required device administrator")
        }

        // Only permit APKs signed with exactly the currently installed signer(s).
        // Android also verifies signing identity during PackageInstaller.commit().
        val installedSigners = installed.signingInfo?.apkContentsSigners
            ?.map { it.toCharsString() }?.toSet().orEmpty()
        val incomingSigners = incoming.signingInfo?.apkContentsSigners
            ?.map { it.toCharsString() }?.toSet().orEmpty()
        if (installedSigners.isEmpty() || incomingSigners != installedSigners) {
            throw IOException("The update is not signed with the original app signing key")
        }
    }

    private fun commitUpdate(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply {
                setAppPackageName(context.packageName)
                setSize(apk.length())
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        val sessionId = installer.createSession(params)
        var committed = false
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    apk.inputStream().use { input -> input.copyTo(output) }
                    session.fsync(output)
                }
                val callback = Intent(context, UpdateInstallReceiver::class.java).apply {
                    action = UpdateInstallReceiver.ACTION_INSTALL_RESULT
                }
                val sender = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    callback,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                ).intentSender
                session.commit(sender)
                committed = true
            }
        } finally {
            if (!committed) runCatching { installer.abandonSession(sessionId) }
        }
    }

    private fun openHttpsConnection(url: String): HttpURLConnection {
        val parsedUrl = URL(url)
        require(parsedUrl.protocol == "https") { "HTTPS is required for updates" }
        require(isTrustedHost(parsedUrl.host)) { "Unexpected download server" }
        return (parsedUrl.openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "FttegoLocker-Android-Updater")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
    }

    private suspend fun followDownloadRedirects(
        initialUrl: String,
        download: suspend (HttpURLConnection) -> Unit,
    ) {
        var currentUrl = initialUrl
        repeat(MAX_REDIRECTS + 1) { redirectIndex ->
            val connection = openHttpsConnection(currentUrl)
            try {
                when (val code = connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        download(connection)
                        return
                    }
                    301, 302, 303, 307, 308 -> {
                        if (redirectIndex == MAX_REDIRECTS) {
                            throw IOException("Too many GitHub download redirects")
                        }
                        val location = connection.getHeaderField("Location")
                            ?: throw IOException("Missing download redirect URL")
                        currentUrl = URL(URL(currentUrl), location).toString()
                    }
                    else -> throw IOException("Download server responded with HTTP $code")
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun isTrustedReleaseAssetUrl(url: String): Boolean = try {
        val parsed = URL(url)
        parsed.protocol == "https" && parsed.host.equals("github.com", ignoreCase = true) &&
            parsed.path.startsWith("/$GITHUB_OWNER/$GITHUB_REPO/releases/download/")
    } catch (_: Exception) {
        false
    }

    private fun isTrustedHost(host: String): Boolean =
        host.equals("api.github.com", ignoreCase = true) ||
            host.equals("github.com", ignoreCase = true) ||
            host.equals("objects.githubusercontent.com", ignoreCase = true) ||
            host.equals("release-assets.githubusercontent.com", ignoreCase = true)

    private fun friendlyError(error: Exception): String = when {
        error is java.net.UnknownHostException ->
            "No internet connection. Connect to Wi-Fi and try again."
        error is java.net.SocketTimeoutException ->
            "The connection timed out. Please try again."
        error.message.orEmpty().contains("HTTP 404") ->
            "The update file was not found on GitHub."
        error.message.orEmpty().contains("HTTP 403") ->
            "GitHub is temporarily refusing downloads. Try again later."
        error.message.orEmpty().contains("sign", ignoreCase = true) ->
            "The new version was signed differently. Contact your administrator."
        error.message.orEmpty().contains("version", ignoreCase = true) ->
            "The downloaded version does not match the published update."
        error.message.orEmpty().contains("administrator", ignoreCase = true) ->
            "The update cannot be installed safely. Contact your administrator."
        else -> "Unable to update the app. Check the internet and try again."
    }

    private fun installerError(status: Int): String = when (status) {
        PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough space on this screen to update."
        PackageInstaller.STATUS_FAILURE_CONFLICT ->
            "The update conflicts with the installed app. Contact your administrator."
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
            "This update is not compatible with the screen."
        PackageInstaller.STATUS_FAILURE_INVALID ->
            "The downloaded update could not be installed. Contact your administrator."
        PackageInstaller.STATUS_FAILURE_BLOCKED ->
            "The screen is blocking the update. Contact your administrator."
        PackageInstaller.STATUS_FAILURE_ABORTED -> "The app update was cancelled."
        PackageInstaller.STATUS_FAILURE_TIMEOUT -> "The update took too long. Please try again."
        else -> "Installation did not complete. Please try again."
    }
}
