package com.adowaalshamel.boardguard.enrollment

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import com.adowaalshamel.boardguard.BoardGuardDeviceAdminReceiver
import com.adowaalshamel.boardguard.ScreenMonitorService
import com.adowaalshamel.boardguard.backend.LockerIdentity
import org.json.JSONObject

/** Public key handoff through the same authorized shell channel used for provisioning. */
class LockerEnrollmentProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (!LockerEnrollmentAccess.isTrustedCaller(Binder.getCallingUid())) {
            throw SecurityException("ENROLLMENT_CALLER_NOT_ALLOWED")
        }
        require(method == "getIdentity" && (extras == null || extras.isEmpty)) { "ENROLLMENT_METHOD_NOT_SUPPORTED" }
        val callingIdentity = Binder.clearCallingIdentity()
        try {
            val appContext = context?.applicationContext ?: error("ENROLLMENT_CONTEXT_UNAVAILABLE")
            if (!BoardGuardDeviceAdminReceiver.isDeviceOwner(appContext)) {
                throw SecurityException("LOCKER_DEVICE_OWNER_REQUIRED")
            }
            val identity = LockerIdentity(appContext).enrollmentIdentity(arg)
            val json = JSONObject()
                .put("protocolVersion", 1)
                .put("macAddress", identity.macAddress)
                .put("publicKey", identity.publicKey)
                .toString()
            ScreenMonitorService.startService(appContext)
            return Bundle().apply { putString("identity", json) }
        } finally {
            Binder.restoreCallingIdentity(callingIdentity)
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
        throw UnsupportedOperationException("ENROLLMENT_METHOD_NOT_SUPPORTED")
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("ENROLLMENT_METHOD_NOT_SUPPORTED")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("ENROLLMENT_METHOD_NOT_SUPPORTED")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("ENROLLMENT_METHOD_NOT_SUPPORTED")
}

internal object LockerEnrollmentAccess {
    fun isTrustedCaller(uid: Int): Boolean = uid == 2_000 || uid == 0
}
