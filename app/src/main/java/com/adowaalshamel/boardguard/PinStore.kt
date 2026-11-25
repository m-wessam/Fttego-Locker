package com.adowaalshamel.boardguard

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.adowaalshamel.boardguard.backend.LockerCredentials
import com.adowaalshamel.boardguard.storage.LockerSnapshot
import com.adowaalshamel.boardguard.storage.LockerSnapshotCipher
import com.adowaalshamel.boardguard.storage.PinDigest
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Screen credentials, coherent server cache and local lock session in one encrypted file. */
class PinStore(context: Context) {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, FILE_NAME))
    private val cacheKey = file.baseFile.absolutePath

    fun hasPin(): Boolean = synchronized(storeLock) { state().let { it.hasPin || it.storageFailure } }
    fun isProtectionEnabled(): Boolean = synchronized(storeLock) { state().let { it.lockEnabled || it.storageFailure } }
    fun isBoardGuardLocked(): Boolean = synchronized(storeLock) { state().let { it.boardLocked || it.storageFailure } }
    fun hasCachedPolicy(): Boolean = synchronized(storeLock) { state().let { it.cachedPolicy && !it.storageFailure } }
    fun getRevision(): String? = synchronized(storeLock) { state().takeIf { it.cachedPolicy && !it.storageFailure }?.revision }
    fun getLockGeneration(): Long = synchronized(storeLock) { state().lockGeneration }
    fun isOfflineUnlockAllowed(): Boolean = synchronized(storeLock) { state().canUnlockOffline }

    /** Called once per new vendor lock event, not for watchdog/activity relaunches. */
    fun setBoardGuardLocked(locked: Boolean) = synchronized(storeLock) {
        val current = state()
        if (!locked && current.storageFailure) throw IOException("LOCKER_STORAGE_UNAVAILABLE")
        commit(current.withBoardLocked(locked))
    }

    /** Returns true only for the first ever assigned PIN; a later PIN keeps the current session. */
    fun cachePolicy(revision: String, lockEnabled: Boolean, password: String?): Boolean = synchronized(storeLock) {
        val current = state()
        val firstPassword = password != null && !current.everPinAssigned
        commit(current.withPolicy(revision, lockEnabled, password))
        firstPassword
    }

    fun verifyCachedPin(pin: String): Boolean = synchronized(storeLock) {
        val current = state()
        current.canUnlockOffline && current.remainingBlockSeconds(System.currentTimeMillis()) == 0L &&
            PinDigest.matches(pin, current.pinSalt, current.pinHash)
    }

    fun remainingBlockSeconds(nowMillis: Long = System.currentTimeMillis()): Long = synchronized(storeLock) {
        state().remainingBlockSeconds(nowMillis)
    }

    fun recordFailedAttempt(nowMillis: Long = System.currentTimeMillis()): Long = synchronized(storeLock) {
        val updated = state().withFailedAttempt(nowMillis)
        commit(updated)
        updated.remainingBlockSeconds(nowMillis)
    }

    /** An old network reply cannot open a newer vendor lock session. */
    fun recordSuccessfulAttempt(expectedGeneration: Long): Boolean = synchronized(storeLock) {
        val updated = state().afterSuccessfulAttempt(expectedGeneration) ?: return@synchronized false
        commit(updated)
        true
    }

    fun blockAttempts(seconds: Long) = synchronized(storeLock) {
        commit(state().withBlock(seconds, System.currentTimeMillis()))
    }

    fun markServerAuthRejected() = synchronized(storeLock) {
        commit(state().copy(offlineAllowed = false))
    }

    fun loadCredentials(): LockerCredentials? = synchronized(storeLock) { state().credentials }

    fun saveCredentials(credentials: LockerCredentials) = synchronized(storeLock) {
        require(credentials.deviceId.isNotBlank() && credentials.deviceId.length <= 512) { "DEVICE_ID_INVALID" }
        require(credentials.deviceToken.isNotBlank() && credentials.deviceToken.length <= 8_192) { "DEVICE_TOKEN_INVALID" }
        commit(state().copy(credentials = credentials))
    }

    fun clearCredentials() = synchronized(storeLock) {
        commit(state().copy(credentials = null, offlineAllowed = false))
    }

    private fun state(): LockerSnapshot {
        sharedStates[cacheKey]?.let { return it }
        val base = file.baseFile
        val exists = base.exists() || File(base.path + ".bak").exists() || File(base.path + ".new").exists()
        val loaded = if (!exists) LockerSnapshot() else try {
            val bytes = file.openRead().use { it.readNBytes(LockerSnapshotCipher.MAX_FILE_BYTES + 1) }
            LockerSnapshotCipher.decrypt(bytes, encryptionKey(create = false))
        } catch (_: Exception) {
            // A lost key or unreadable prior cache must never turn a configured screen into
            // an unconfigured/unlocked one. Only a successful server resync recovers this.
            LockerSnapshot().failClosed()
        }
        sharedStates[cacheKey] = loaded
        return loaded
    }

    private fun commit(updated: LockerSnapshot) {
        val current = state()
        if (updated == current) return
        var stream: FileOutputStream? = null
        try {
            val encrypted = LockerSnapshotCipher.encrypt(updated, encryptionKey(create = true))
            stream = file.startWrite()
            stream.write(encrypted)
            stream.fd.sync()
            file.finishWrite(stream)
            stream = null
            // AtomicFile can log a rename failure without throwing. Confirm the exact commit
            // before acknowledging a changed policy, credentials or successful unlock.
            if (!file.readFully().contentEquals(encrypted)) throw IOException()
            sharedStates[cacheKey] = updated
        } catch (_: Exception) {
            sharedStates[cacheKey] = current.failClosed()
            try { stream?.let(file::failWrite) } catch (_: Exception) { /* Keep the fail-closed state. */ }
            throw IOException("LOCKER_STORAGE_UNAVAILABLE")
        }
    }

    private fun encryptionKey(create: Boolean): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (store.containsAlias(KEY_ALIAS)) {
            return store.getKey(KEY_ALIAS, null) as? SecretKey ?: throw IOException("LOCKER_STORAGE_UNAVAILABLE")
        }
        if (!create) throw IOException("LOCKER_STORAGE_UNAVAILABLE")
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
        }.generateKey()
    }

    companion object {
        private const val FILE_NAME = "locker_secure_snapshot_v1"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "locker_snapshot_aes_v1"
        // Service, provider and lock Activity run in one app process and share this lock/cache.
        // AtomicFile itself supplies no mutual exclusion.
        private val storeLock = Any()
        private val sharedStates = mutableMapOf<String, LockerSnapshot>()
    }
}
