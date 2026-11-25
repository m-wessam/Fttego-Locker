package com.adowaalshamel.boardguard.storage

import com.adowaalshamel.boardguard.backend.LockerCredentials
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One coherent policy, local lock session and credentials; never contains a plaintext PIN. */
internal data class LockerSnapshot(
    val cachedPolicy: Boolean = false,
    val revision: String? = null,
    val lockEnabled: Boolean = false,
    val pinSalt: ByteArray? = null,
    val pinHash: ByteArray? = null,
    val everPinAssigned: Boolean = false,
    val boardLocked: Boolean = false,
    val failedAttempts: Int = 0,
    val blockedUntilMillis: Long = 0L,
    val offlineAllowed: Boolean = false,
    val credentials: LockerCredentials? = null,
    val storageFailure: Boolean = false,
    val lockGeneration: Long = 0L,
) {
    val hasPin: Boolean get() = pinSalt != null && pinHash != null
    val canUnlockOffline: Boolean
        get() = cachedPolicy && !storageFailure && offlineAllowed && lockEnabled && hasPin

    fun withPolicy(newRevision: String, enabled: Boolean, password: String?): LockerSnapshot {
        require(newRevision.isNotBlank() && newRevision.length <= 1_024) { "POLICY_REVISION_INVALID" }
        require(password == null || PinDigest.isValidPin(password)) { "POLICY_PASSWORD_INVALID" }
        require(!enabled || password != null) { "POLICY_PASSWORD_REQUIRED" }
        val firstPassword = password != null && !everPinAssigned
        val samePin = password != null && PinDigest.matches(password, pinSalt, pinHash)
        val salt = when {
            password == null -> null
            samePin -> pinSalt
            else -> ByteArray(PinDigest.SALT_BYTES).also { SecureRandom().nextBytes(it) }
        }
        val hash = when {
            password == null -> null
            samePin -> pinHash
            else -> PinDigest.hash(password, checkNotNull(salt))
        }
        return copy(
            cachedPolicy = true,
            revision = newRevision,
            lockEnabled = enabled,
            pinSalt = salt,
            pinHash = hash,
            everPinAssigned = everPinAssigned || password != null,
            boardLocked = enabled && (boardLocked || firstPassword),
            offlineAllowed = true,
            storageFailure = false,
            lockGeneration = if (firstPassword && enabled) nextGeneration() else lockGeneration,
        )
    }

    fun withBoardLocked(locked: Boolean): LockerSnapshot = copy(
        boardLocked = locked,
        lockGeneration = if (locked) nextGeneration() else lockGeneration,
    )

    fun remainingBlockSeconds(nowMillis: Long): Long {
        if (blockedUntilMillis <= nowMillis) return 0L
        val remaining = blockedUntilMillis - nowMillis
        return remaining / 1_000L + if (remaining % 1_000L == 0L) 0L else 1L
    }

    fun withFailedAttempt(nowMillis: Long): LockerSnapshot {
        if (remainingBlockSeconds(nowMillis) > 0L) return this
        val failures = failedAttempts + 1
        return if (failures >= 5) copy(failedAttempts = 0, blockedUntilMillis = deadline(nowMillis, 60L))
        else copy(failedAttempts = failures, blockedUntilMillis = 0L)
    }

    fun withBlock(seconds: Long, nowMillis: Long): LockerSnapshot = copy(
        failedAttempts = 0,
        blockedUntilMillis = maxOf(blockedUntilMillis, deadline(nowMillis, seconds.coerceAtLeast(0L))),
    )

    fun afterSuccessfulAttempt(expectedGeneration: Long): LockerSnapshot? {
        if (expectedGeneration != lockGeneration) return null
        if (storageFailure || !cachedPolicy) throw IOException("LOCKER_STORAGE_UNAVAILABLE")
        return copy(boardLocked = false, failedAttempts = 0, blockedUntilMillis = 0L)
    }

    fun failClosed(): LockerSnapshot = copy(
        lockEnabled = true,
        everPinAssigned = true,
        boardLocked = true,
        offlineAllowed = false,
        storageFailure = true,
    )

    private fun nextGeneration(): Long = try {
        Math.addExact(lockGeneration, 1L)
    } catch (_: ArithmeticException) {
        throw IOException("LOCKER_STORAGE_UNAVAILABLE")
    }

    private fun deadline(nowMillis: Long, seconds: Long): Long = try {
        Math.addExact(nowMillis, Math.multiplyExact(seconds, 1_000L))
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }
}

internal object PinDigest {
    const val SALT_BYTES = 16
    const val HASH_BYTES = 32

    fun isValidPin(pin: String): Boolean = pin.length == 4 && pin.all { it in '0'..'9' }

    fun hash(pin: String, salt: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run {
        update(salt)
        digest(pin.toByteArray(Charsets.UTF_8))
    }

    fun matches(pin: String, salt: ByteArray?, expected: ByteArray?): Boolean {
        if (!isValidPin(pin) || salt?.size != SALT_BYTES || expected?.size != HASH_BYTES) return false
        return MessageDigest.isEqual(expected, hash(pin, salt))
    }
}

/** Versioned binary encoding keeps credentials and policy in the same encrypted commit. */
internal object LockerSnapshotCodec {
    fun encode(state: LockerSnapshot): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(1)
            output.writeBoolean(state.cachedPolicy)
            output.writeBoolean(state.revision != null)
            state.revision?.let(output::writeUTF)
            output.writeBoolean(state.lockEnabled)
            output.writeBoolean(state.hasPin)
            if (state.hasPin) {
                output.write(checkNotNull(state.pinSalt))
                output.write(checkNotNull(state.pinHash))
            }
            output.writeBoolean(state.everPinAssigned)
            output.writeBoolean(state.boardLocked)
            output.writeInt(state.failedAttempts)
            output.writeLong(state.blockedUntilMillis)
            output.writeBoolean(state.offlineAllowed)
            output.writeBoolean(state.credentials != null)
            state.credentials?.let {
                output.writeUTF(it.deviceId)
                output.writeUTF(it.deviceToken)
            }
            output.writeBoolean(state.storageFailure)
            output.writeLong(state.lockGeneration)
        }
        bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): LockerSnapshot = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(input.readInt() == 1) { "LOCKER_STORAGE_INVALID" }
        val cached = input.readBoolean()
        val revision = if (input.readBoolean()) input.readUTF() else null
        val enabled = input.readBoolean()
        val hasPin = input.readBoolean()
        val salt = if (hasPin) ByteArray(PinDigest.SALT_BYTES).also(input::readFully) else null
        val hash = if (hasPin) ByteArray(PinDigest.HASH_BYTES).also(input::readFully) else null
        val everAssigned = input.readBoolean()
        val locked = input.readBoolean()
        val failures = input.readInt()
        val blockedUntil = input.readLong()
        val offlineAllowed = input.readBoolean()
        val credentials = if (input.readBoolean()) LockerCredentials(input.readUTF(), input.readUTF()) else null
        val storageFailure = input.readBoolean()
        val generation = input.readLong()
        require(input.available() == 0 && failures in 0..4 && blockedUntil >= 0 && generation >= 0) { "LOCKER_STORAGE_INVALID" }
        require(!cached || !revision.isNullOrBlank()) { "LOCKER_STORAGE_INVALID" }
        require(revision == null || revision.length <= 1_024) { "LOCKER_STORAGE_INVALID" }
        require(!enabled || hasPin || storageFailure) { "LOCKER_STORAGE_INVALID" }
        require(!hasPin || everAssigned) { "LOCKER_STORAGE_INVALID" }
        require(!offlineAllowed || (cached && !storageFailure)) { "LOCKER_STORAGE_INVALID" }
        require(credentials == null || (
            credentials.deviceId.isNotBlank() && credentials.deviceId.length <= 512 &&
                credentials.deviceToken.isNotBlank() && credentials.deviceToken.length <= 8_192
            )) { "LOCKER_STORAGE_INVALID" }
        LockerSnapshot(cached, revision, enabled, salt, hash, everAssigned, locked, failures, blockedUntil,
            offlineAllowed, credentials, storageFailure, generation)
    }
}

/** AEAD authenticates the complete snapshot, including its format version. */
internal object LockerSnapshotCipher {
    private val associatedData = "FTTEGO-LOCKER-SNAPSHOT-v1".toByteArray(Charsets.US_ASCII)
    private const val VERSION = 1
    private const val IV_BYTES = 12
    const val MAX_FILE_BYTES = 32_768

    fun encrypt(state: LockerSnapshot, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(associatedData)
        require(cipher.iv.size == IV_BYTES) { "LOCKER_STORAGE_INVALID" }
        val plaintext = LockerSnapshotCodec.encode(state)
        return try {
            byteArrayOf(VERSION.toByte()) + cipher.iv + cipher.doFinal(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    fun decrypt(bytes: ByteArray, key: SecretKey): LockerSnapshot {
        require(bytes.size in (1 + IV_BYTES + 16)..MAX_FILE_BYTES && bytes[0].toInt() == VERSION) { "LOCKER_STORAGE_INVALID" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(1, 1 + IV_BYTES)))
        cipher.updateAAD(associatedData)
        val plaintext = cipher.doFinal(bytes, 1 + IV_BYTES, bytes.size - 1 - IV_BYTES)
        return try {
            LockerSnapshotCodec.decode(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }
}
