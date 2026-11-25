package com.adowaalshamel.boardguard.backend

import android.content.Context
import com.adowaalshamel.boardguard.PinStore
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One coordinator for the lock UI and the existing screen monitor service. */
class LockerRuntime private constructor(context: Context) {
    val pinStore = PinStore(context.applicationContext)
    private val identity = LockerIdentity(context.applicationContext)
    private val api = LockerDeviceApi()
    private val mutex = Mutex()
    private val revision = MutableSharedFlow<String?>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        .apply { tryEmit(pinStore.getRevision()) }
    // Every successful snapshot commit must notify the lock UI, including recovery to the
    // same disabled revision after a local storage failure had temporarily failed closed.
    val policyRevision: SharedFlow<String?> = revision.asSharedFlow()

    suspend fun synchronize(): LockerSyncResult = withContext(Dispatchers.IO) {
        mutex.withLock { synchronizeLocked() }
    }

    suspend fun verifyPin(pin: String): LockerUnlockResult = withContext(Dispatchers.IO) {
        mutex.withLock { verifyPinLocked(pin) }
    }

    private suspend fun synchronizeLocked(): LockerSyncResult {
        return try {
            var currentCredentials = credentials()
            val snapshot = try {
                api.readConsistentPolicy(currentCredentials)
            } catch (error: LockerDeviceApiException) {
                if (error.statusCode != 401) throw error
                // A revoked/stale token is never evidence that offline verification is allowed.
                rejectServerCredentials(clearToken = true)
                currentCredentials = credentials()
                // Exactly one signed recovery, with a fresh nonce, before giving up.
                api.readConsistentPolicy(currentCredentials)
            }
            val (policy, password) = snapshot
            val changed = stored {
                !hasCachedPolicy() || getRevision() != policy.revision ||
                    isProtectionEnabled() != policy.lockEnabled || hasPin() != policy.hasPassword
            }
            val firstPassword = stored { cachePolicy(policy.revision, policy.lockEnabled, password.password) }
            revision.emit(policy.revision)
            LockerSyncResult(synchronized = true, firstPassword = firstPassword, policyChanged = changed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            try {
                rememberServerFailure(error)
                LockerSyncResult(
                    synchronized = false,
                    failure = classifyLockerFailure(error),
                    retryAfterSeconds = (error as? LockerDeviceApiException)?.retryAfterSeconds,
                )
            } catch (_: LockerLocalFailure) {
                LockerSyncResult(synchronized = false, failure = LockerFailure.STORAGE)
            }
        }
    }

    private suspend fun verifyPinLocked(pin: String): LockerUnlockResult {
        if (!LOCKER_PIN_PATTERN.matches(pin)) return LockerUnlockResult.Incorrect
        return try {
            if (stored { hasCachedPolicy() && !isProtectionEnabled() }) return LockerUnlockResult.Disabled
            // A missing/untrusted snapshot must first be replaced with a coherent server policy.
            if (stored { !hasCachedPolicy() || !isOfflineUnlockAllowed() }) {
                val synchronization = synchronizeLocked()
                if (!synchronization.synchronized) {
                    val blocked = stored { remainingBlockSeconds() }
                    if (blocked > 0) return LockerUnlockResult.Blocked(blocked)
                    return LockerUnlockResult.Unavailable(synchronization.failure ?: LockerFailure.UNKNOWN)
                }
            }
            if (stored { !isProtectionEnabled() }) return LockerUnlockResult.Disabled
            stored { remainingBlockSeconds() }.takeIf { it > 0 }?.let {
                return LockerUnlockResult.Blocked(it)
            }
            val generation = stored { getLockGeneration() }
            try {
                val valid = api.unlock(credentials(), pin)
                finishVerification(valid, generation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                rememberServerFailure(error)
                when {
                    error is LockerDeviceApiException && error.statusCode == 429 -> {
                        LockerUnlockResult.Blocked(stored { remainingBlockSeconds() }.coerceAtLeast(1))
                    }
                    error is LockerDeviceApiException && error.statusCode == 401 -> {
                        // Recover for the next user attempt. Do not replay this POST or fall back locally.
                        val synchronization = synchronizeLocked()
                        if (synchronization.synchronized && stored { !isProtectionEnabled() }) {
                            LockerUnlockResult.Disabled
                        } else {
                            LockerUnlockResult.Unavailable(
                                synchronization.failure ?: LockerFailure.AUTHORIZATION,
                            )
                        }
                    }
                    permitsOfflineFallback(error) -> verifyOffline(pin, generation)
                    else -> LockerUnlockResult.Unavailable(classifyLockerFailure(error))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LockerUnlockResult.Unavailable(classifyLockerFailure(error))
        }
    }

    private fun verifyOffline(pin: String, generation: Long): LockerUnlockResult {
        if (stored { !hasCachedPolicy() || !isOfflineUnlockAllowed() || !hasPin() }) {
            return LockerUnlockResult.Unavailable(LockerFailure.NETWORK)
        }
        if (stored { !isProtectionEnabled() }) return LockerUnlockResult.Disabled
        stored { remainingBlockSeconds() }.takeIf { it > 0 }?.let {
            return LockerUnlockResult.Blocked(it)
        }
        return finishVerification(stored { verifyCachedPin(pin) }, generation)
    }

    private fun finishVerification(valid: Boolean, generation: Long): LockerUnlockResult {
        if (valid) {
            // Store checks the generation atomically: a late HTTP response cannot open a newer lock cycle.
            return if (stored { recordSuccessfulAttempt(generation) }) {
                LockerUnlockResult.Unlocked
            } else {
                LockerUnlockResult.Unavailable(LockerFailure.UNKNOWN)
            }
        }
        val blocked = stored { recordFailedAttempt() }
        return if (blocked > 0) LockerUnlockResult.Blocked(blocked) else LockerUnlockResult.Incorrect
    }

    private suspend fun credentials(): LockerCredentials {
        stored { loadCredentials() }?.let { return it }
        val request = try {
            identity.createActivationRequest()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val reason = if (error.message == "ETHERNET_IDENTITY_REQUIRED") {
                LockerFailure.NOT_REGISTERED
            } else {
                LockerFailure.STORAGE
            }
            throw LockerLocalFailure(reason)
        }
        return api.activate(request).also { credential -> stored { saveCredentials(credential) } }
    }

    private fun rememberServerFailure(error: Exception) {
        if (error !is LockerDeviceApiException) return
        when (error.statusCode) {
            401 -> rejectServerCredentials(clearToken = true)
            403, 404 -> rejectServerCredentials(clearToken = true)
            429 -> stored { blockAttempts(error.retryAfterSeconds?.coerceAtLeast(1) ?: 60L) }
        }
    }

    private fun rejectServerCredentials(clearToken: Boolean) {
        stored {
            markServerAuthRejected()
            if (clearToken) clearCredentials()
        }
    }

    private inline fun <T> stored(action: PinStore.() -> T): T = try {
        pinStore.action()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // A local encrypted-storage error must not be mistaken for a transport failure.
        throw LockerLocalFailure(LockerFailure.STORAGE)
    }

    companion object {
        @Volatile private var instance: LockerRuntime? = null

        fun get(context: Context): LockerRuntime = instance ?: synchronized(this) {
            instance ?: LockerRuntime(context.applicationContext).also { instance = it }
        }
    }
}

internal class LockerLocalFailure(val reason: LockerFailure) : Exception(reason.name)

/** Only loss of transport or the API's explicit service-unavailable response permits the cache. */
internal fun permitsOfflineFallback(error: Exception): Boolean = when (error) {
    is LockerDeviceApiException -> error.statusCode == 503
    is IOException -> true
    else -> false
}

internal fun classifyLockerFailure(error: Exception): LockerFailure = when (error) {
    is LockerLocalFailure -> error.reason
    is LockerDeviceApiException -> when {
        error.errorCode in setOf("INVALID_RESPONSE", "POLICY_REVISION_MISMATCH") || error.statusCode == 412 ->
            LockerFailure.INVALID_RESPONSE
        error.errorCode == "DEVICE_KEY_NOT_APPROVED" || error.statusCode == 404 -> LockerFailure.NOT_REGISTERED
        error.statusCode == 401 || error.statusCode == 403 -> LockerFailure.AUTHORIZATION
        error.statusCode == 429 -> LockerFailure.RATE_LIMITED
        error.statusCode == 503 -> LockerFailure.SERVICE_UNAVAILABLE
        error.statusCode == 400 && error.errorCode in setOf(
            "INVALID_ISSUED_AT", "ACTIVATION_EXPIRED", "ACTIVATION_TIME_INVALID", "INVALID_TIMESTAMP", "CLOCK_SKEW",
        ) -> LockerFailure.INVALID_CLOCK
        else -> LockerFailure.UNKNOWN
    }
    is IOException -> LockerFailure.NETWORK
    else -> LockerFailure.UNKNOWN
}
