package com.adowaalshamel.boardguard.storage

import com.adowaalshamel.boardguard.backend.LockerCredentials
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator

class LockerSnapshotTest {
    @Test fun firstPasswordLocksImmediatelyAndLaterPasswordKeepsOpenSession() {
        val initial = LockerSnapshot().withPolicy("r1", false, null)
        val first = initial.withPolicy("r2", true, "0123")
        assertTrue(first.everPinAssigned)
        assertTrue(first.boardLocked)
        assertEquals(1L, first.lockGeneration)
        val opened = checkNotNull(first.afterSuccessfulAttempt(first.lockGeneration))
        val changed = opened.withPolicy("r3", true, "5678")
        assertFalse(changed.boardLocked)
        assertEquals(opened.lockGeneration, changed.lockGeneration)
        assertTrue(PinDigest.matches("5678", changed.pinSalt, changed.pinHash))
        assertFalse(PinDigest.matches("0123", changed.pinSalt, changed.pinHash))
    }

    @Test fun laterPasswordKeepsLockedSessionAndDisableReleasesIt() {
        val first = LockerSnapshot().withPolicy("r1", true, "0123")
        val changed = first.withPolicy("r2", true, "5678")
        assertTrue(changed.boardLocked)
        assertEquals(first.lockGeneration, changed.lockGeneration)
        val disabled = changed.withPolicy("r3", false, "5678")
        assertFalse(disabled.boardLocked)
        assertTrue(disabled.hasPin)
        val enabledAgain = disabled.withPolicy("r4", true, "5678")
        assertFalse(enabledAgain.boardLocked)
        assertEquals(first.lockGeneration, enabledAgain.lockGeneration)
    }

    @Test fun repeatedPolicyDoesNotRearmOrRegeneratePinDigest() {
        val first = LockerSnapshot().withPolicy("r1", true, "0123")
        val repeated = first.withPolicy("r1", true, "0123")
        assertEquals(first, repeated)
        assertSame(first.pinSalt, repeated.pinSalt)
        assertSame(first.pinHash, repeated.pinHash)
    }

    @Test fun lateVerificationCannotOpenNewerLockSession() {
        val first = LockerSnapshot().withPolicy("r1", true, "0123")
        val verificationGeneration = first.lockGeneration
        val newerLock = first.withBoardLocked(true)
        assertTrue(newerLock.boardLocked)
        assertNull(newerLock.afterSuccessfulAttempt(verificationGeneration))
        assertTrue(newerLock.boardLocked)
        assertEquals(verificationGeneration + 1, newerLock.lockGeneration)
        assertFalse(checkNotNull(newerLock.afterSuccessfulAttempt(newerLock.lockGeneration)).boardLocked)
    }

    @Test fun fifthFailureBlocksForSixtySecondsAndPersists() {
        val now = 1_000_000L
        var state = LockerSnapshot().withPolicy("r1", true, "0123")
        repeat(4) { state = state.withFailedAttempt(now) }
        assertEquals(0L, state.remainingBlockSeconds(now))
        state = state.withFailedAttempt(now)
        assertEquals(60L, state.remainingBlockSeconds(now))
        val restored = LockerSnapshotCodec.decode(LockerSnapshotCodec.encode(state))
        assertEquals(60L, restored.remainingBlockSeconds(now + 1L))
        assertEquals(1L, restored.remainingBlockSeconds(now + 59_999L))
        assertSame(restored, restored.withFailedAttempt(now + 5_000L))
        assertEquals(0L, restored.remainingBlockSeconds(now + 60_000L))
        assertEquals(1, restored.withFailedAttempt(now + 60_000L).failedAttempts)
    }

    @Test fun successfulVerificationClearsBlockAndSessionTogether() {
        val state = LockerSnapshot().withPolicy("r1", true, "0123").withBlock(60L, 1_000L)
        val unlocked = checkNotNull(state.afterSuccessfulAttempt(state.lockGeneration))
        assertFalse(unlocked.boardLocked)
        assertEquals(0, unlocked.failedAttempts)
        assertEquals(0L, unlocked.blockedUntilMillis)
    }

    @Test fun serverBlockCannotShortenExistingCooldown() {
        val state = LockerSnapshot().withBlock(120L, 1_000L)
        assertEquals(state.blockedUntilMillis, state.withBlock(20L, 2_000L).blockedUntilMillis)
        assertEquals(Long.MAX_VALUE, state.withBlock(Long.MAX_VALUE, 1_000L).blockedUntilMillis)
    }

    @Test fun authRejectionDisablesOfflineUnlockUntilCoherentSync() {
        val state = LockerSnapshot().withPolicy("r1", true, "0123")
        val rejected = state.copy(offlineAllowed = false)
        assertFalse(rejected.canUnlockOffline)
        assertTrue(rejected.boardLocked)
        assertTrue(rejected.withPolicy("r1", true, "0123").canUnlockOffline)
        assertFalse(LockerSnapshot().canUnlockOffline)
    }

    @Test fun corruptStateFailsClosedAndRequiresPolicyRecovery() {
        val closed = LockerSnapshot().failClosed()
        assertTrue(closed.lockEnabled)
        assertTrue(closed.boardLocked)
        assertFalse(closed.canUnlockOffline)
        assertThrows(IOException::class.java) { closed.afterSuccessfulAttempt(closed.lockGeneration) }
        val restored = closed.withPolicy("r1", true, "0123")
        assertTrue(restored.boardLocked)
        assertTrue(restored.canUnlockOffline)
        assertFalse(closed.withPolicy("r1", false, null).boardLocked)
    }

    @Test fun everyFourDigitPinIsOrdinaryAndLeadingZerosAreRetained() {
        val state = LockerSnapshot().withPolicy("r1", true, "0123")
        for (wrong in listOf("123", "01234", "6179", "8403", "٠١٢٣")) {
            assertFalse(PinDigest.matches(wrong, state.pinSalt, state.pinHash))
        }
        for (pin in listOf("0000", "0123", "6179", "8403")) {
            val updated = state.withPolicy("r2", true, pin)
            assertTrue(PinDigest.matches(pin, updated.pinSalt, updated.pinHash))
        }
    }

    @Test fun encryptedSnapshotRestoresPolicyCredentialsAndThrottle() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val state = LockerSnapshot().withPolicy("locker-101-4", true, "0123")
            .withFailedAttempt(1_000L)
            .copy(credentials = LockerCredentials("101", "lkd_test_token"))
        val first = LockerSnapshotCipher.encrypt(state, key)
        val second = LockerSnapshotCipher.encrypt(state, key)
        assertFalse(first.contentEquals(second))
        val restored = LockerSnapshotCipher.decrypt(first, key)
        assertEquals(state.credentials, restored.credentials)
        assertEquals(state.revision, restored.revision)
        assertEquals(state.lockGeneration, restored.lockGeneration)
        assertEquals(state.failedAttempts, restored.failedAttempts)
        assertEquals(state.boardLocked, restored.boardLocked)
        assertTrue(restored.canUnlockOffline)
        assertTrue(PinDigest.matches("0123", restored.pinSalt, restored.pinHash))
        val plaintext = LockerSnapshotCodec.encode(state).toString(Charsets.ISO_8859_1)
        assertFalse(plaintext.contains("0123"))
        assertFalse(first.toString(Charsets.ISO_8859_1).contains("lkd_test_token"))
    }

    @Test fun ciphertextTamperingAndWrongEncryptionKeyAreRejected() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val encrypted = LockerSnapshotCipher.encrypt(LockerSnapshot().withPolicy("r1", true, "0123"), key)
        assertThrows(GeneralSecurityException::class.java) { LockerSnapshotCipher.decrypt(encrypted, otherKey) }
        val tampered = encrypted.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        assertThrows(GeneralSecurityException::class.java) { LockerSnapshotCipher.decrypt(tampered, key) }
    }

    @Test fun inconsistentPolicyAndTrailingStoredDataAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { LockerSnapshot().withPolicy("r1", true, null) }
        assertThrows(IllegalArgumentException::class.java) { LockerSnapshot().withPolicy("r1", true, "１２３４") }
        val encoded = LockerSnapshotCodec.encode(LockerSnapshot())
        assertThrows(IllegalArgumentException::class.java) { LockerSnapshotCodec.decode(encoded + byteArrayOf(0)) }
    }
}
