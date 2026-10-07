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
        val saltBytes = ByteArray(16)
        SecureRandom().nextBytes(saltBytes)
        val saltBase64 = Base64.encodeToString(saltBytes, Base64.NO_WRAP)
        val hashBase64 = hashPin(pin, saltBytes)

        prefs.edit {
            putString(KEY_PIN_SALT, saltBase64)
            putString(KEY_PIN_HASH, hashBase64)
        }
    }

    fun verifyPin(pin: String): Boolean {
        val saltBase64 = prefs.getString(KEY_PIN_SALT, null) ?: return false
        val storedHash = prefs.getString(KEY_PIN_HASH, null) ?: return false

        val saltBytes = try {
            Base64.decode(saltBase64, Base64.NO_WRAP)
        } catch (_: Exception) {
            return false
        }

        val computedHash = hashPin(pin, saltBytes)
        return computedHash == storedHash
    }

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

    companion object {
        private const val PREFS_NAME = "boardguard_prefs"
        private const val KEY_PIN_HASH = "key_pin_hash"
        private const val KEY_PIN_SALT = "key_pin_salt"
        private const val KEY_PROTECTION_ENABLED = "key_protection_enabled"
    }
}
