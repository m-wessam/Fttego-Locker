package com.adowaalshamel.boardguard

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.core.content.edit
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Manages PIN persistence, salted SHA-256 password hashing,
 * and protection status flags using SharedPreferences.
 */
class PinStore(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun hasPin(): Boolean {
        return !prefs.getString(KEY_PIN_HASH, null).isNullOrEmpty() &&
                !prefs.getString(KEY_PIN_SALT, null).isNullOrEmpty()
    }

    fun setPin(pin: String) {
        require(pin.length == 4 && pin.all { it in '0'..'9' }) {
            "Teacher PIN must contain exactly four digits"
        }
        require(!isReservedPin(pin)) {
            "Teacher PIN cannot match a master PIN"
        }
        val saltBytes = ByteArray(16)
        SecureRandom().nextBytes(saltBytes)
        val saltBase64 = Base64.encodeToString(saltBytes, Base64.NO_WRAP)
        val hashBase64 = hashPin(pin, saltBytes)

        prefs.edit {
            putString(KEY_PIN_SALT, saltBase64)
            putString(KEY_PIN_HASH, hashBase64)
        }
    }

    /**
     * Unlocks the secondary lock with either the teacher PIN or the recovery master.
     * This method must NEVER be used to authorize configuration changes.
     */
    fun verifyScreenPin(pin: String): Boolean {
        if (pin.length != 4) return false
        // Admin credentials never unlock the screen, even if they match a
        // teacher PIN stored by an older application version.
        if (pin == ADMIN_MASTER_PIN) return false
        if (pin == RECOVERY_MASTER_PIN) return true

        val saltBase64 = prefs.getString(KEY_PIN_SALT, null) ?: return false
        val storedHash = prefs.getString(KEY_PIN_HASH, null) ?: return false

        val saltBytes = try {
            Base64.decode(saltBase64, Base64.NO_WRAP)
        } catch (_: Exception) {
            return false
        }

        val storedHashBytes = try {
            Base64.decode(storedHash, Base64.NO_WRAP)
        } catch (_: Exception) {
            return false
        }

        val computedHashBytes = Base64.decode(hashPin(pin, saltBytes), Base64.NO_WRAP)
        return MessageDigest.isEqual(computedHashBytes, storedHashBytes)
    }

    /**
     * Authorizes the technician to enter settings. Neither the teacher PIN nor
     * the recovery PIN is accepted here.
     */
    fun verifyAdminPin(pin: String): Boolean = pin == ADMIN_MASTER_PIN

    fun isReservedPin(pin: String): Boolean =
        pin == RECOVERY_MASTER_PIN || pin == ADMIN_MASTER_PIN

    fun isProtectionEnabled(): Boolean {
        return prefs.getBoolean(KEY_PROTECTION_ENABLED, false)
    }

    fun setProtectionEnabled(enabled: Boolean) {
        prefs.edit {
            putBoolean(KEY_PROTECTION_ENABLED, enabled)
        }
    }

    private fun hashPin(pin: String, salt: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        val hashBytes = digest.digest(pin.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(hashBytes, Base64.NO_WRAP)
    }

    fun isBoardGuardLocked(): Boolean {
        return prefs.getBoolean(KEY_BOARDGUARD_LOCKED, false)
    }

    fun setBoardGuardLocked(locked: Boolean) {
        prefs.edit {
            putBoolean(KEY_BOARDGUARD_LOCKED, locked)
        }
    }

    companion object {
        // TEST VALUES ONLY. Replace before deploying to schools.
        // Four-digit shared hardcoded PINs can be extracted from the APK.
        private const val RECOVERY_MASTER_PIN = "6179"
        private const val ADMIN_MASTER_PIN = "8403"
        private const val PREFS_NAME = "boardguard_prefs"
        private const val KEY_PIN_HASH = "key_pin_hash"
        private const val KEY_PIN_SALT = "key_pin_salt"
        private const val KEY_PROTECTION_ENABLED = "key_protection_enabled"

        private const val KEY_BOARDGUARD_LOCKED = "boardguard_locked"
    }
}
