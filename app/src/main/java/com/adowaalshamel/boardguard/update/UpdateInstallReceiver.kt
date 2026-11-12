package com.adowaalshamel.boardguard.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/** Receives the result of a user-requested PackageInstaller session. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_RESULT) return

        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Device Owner updates usually need no confirmation, but some Android
            // builds may still request one. Honor that request rather than failing.
            val confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            if (confirmation == null) {
                AppUpdateController.installationResult(
                    context, PackageInstaller.STATUS_FAILURE, "Missing system confirmation"
                )
                return
            }
            try {
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirmation)
            } catch (e: Exception) {
                Log.e(TAG, "Unable to launch system update confirmation", e)
                AppUpdateController.installationResult(
                    context, PackageInstaller.STATUS_FAILURE, e.message
                )
            }
            return
        }
        AppUpdateController.installationResult(
            context,
            status,
            intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
        )
    }

    companion object {
        const val ACTION_INSTALL_RESULT = "com.adowaalshamel.boardguard.UPDATE_INSTALL_RESULT"
        private const val TAG = "FttegoUpdateReceiver"
    }
}
