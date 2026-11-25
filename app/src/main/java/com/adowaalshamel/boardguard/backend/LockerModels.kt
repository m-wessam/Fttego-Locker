package com.adowaalshamel.boardguard.backend

import java.io.IOException

data class LockerCredentials(val deviceId: String, val deviceToken: String) {
    override fun toString(): String = "LockerCredentials(deviceId=$deviceId, deviceToken=<redacted>)"
}

data class LockerActivationRequest(
    val macAddress: String,
    val publicKey: String,
    val nonce: String,
    val issuedAt: Long,
    val signature: String,
)

data class LockerPolicy(val lockEnabled: Boolean, val hasPassword: Boolean, val revision: String)

data class LockerPassword(val password: String?, val revision: String) {
    override fun toString(): String = "LockerPassword(password=<redacted>, revision=$revision)"
}

class LockerDeviceApiException(
    val statusCode: Int,
    val errorCode: String,
    val retryAfterSeconds: Long? = null,
) : IOException("Locker API: HTTP $statusCode ($errorCode)")

enum class LockerFailure {
    NETWORK,
    SERVICE_UNAVAILABLE,
    RATE_LIMITED,
    AUTHORIZATION,
    NOT_REGISTERED,
    INVALID_RESPONSE,
    INVALID_CLOCK,
    STORAGE,
    UNKNOWN,
}

data class LockerSyncResult(
    val synchronized: Boolean,
    val firstPassword: Boolean = false,
    val policyChanged: Boolean = false,
    val failure: LockerFailure? = null,
    val retryAfterSeconds: Long? = null,
)

sealed interface LockerUnlockResult {
    data object Unlocked : LockerUnlockResult
    data object Incorrect : LockerUnlockResult
    data object Disabled : LockerUnlockResult
    data class Blocked(val retryAfterSeconds: Long) : LockerUnlockResult
    data class Unavailable(val reason: LockerFailure) : LockerUnlockResult
}

internal val LOCKER_PIN_PATTERN = Regex("[0-9]{4}")
