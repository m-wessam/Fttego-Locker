package com.adowaalshamel.boardguard.backend

import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LockerDeviceApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: LockerDeviceApi
    private var now = 1_800_000_000_000L
    private val credentials = LockerCredentials("101", "lkd_device_test")

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        api = LockerDeviceApi(server.url("/functions/v1").toString(), nowMillis = { now })
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun activationUsesOnlySignedDeviceIdentityWithoutBearerCredentials() = runBlocking {
        server.enqueue(json("""{"deviceId":"101","deviceToken":"lkd_device_test"}"""))
        val activation = LockerActivationRequest("AA:BB:CC:DD:EE:01", "cHVibGlj", "a".repeat(64), now / 1000, "c2lnbmF0dXJl")

        assertEquals(credentials, api.activate(activation))

        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        val body = JSONObject(request.body.readUtf8())
        assertEquals("POST", request.method)
        assertEquals("/functions/v1/api/locker/device/activate", request.path)
        assertNull(request.getHeader("Authorization"))
        assertEquals(5, body.length())
        assertEquals(activation.macAddress, body.getString("macAddress"))
        assertEquals(activation.publicKey, body.getString("publicKey"))
        assertEquals(activation.nonce, body.getString("nonce"))
        assertEquals(activation.issuedAt, body.getLong("issuedAt"))
        assertEquals(activation.signature, body.getString("signature"))
        assertFalse(body.has("deviceToken"))
        assertFalse(body.has("managerToken"))
        assertFalse(body.has("privateKey"))
        assertFalse(credentials.toString().contains(credentials.deviceToken))
    }

    @Test
    fun policyAndPasswordAreBodylessDeviceRequestsWithMatchingRevisionAndLeadingZero() = runBlocking {
        server.enqueue(policy("locker-101-1"))
        server.enqueue(password("0123", "locker-101-1"))

        val (policy, password) = api.readConsistentPolicy(credentials)

        assertTrue(policy.lockEnabled)
        assertEquals("0123", password.password)
        assertEquals(policy.revision, password.revision)
        val policyRequest = server.takeRequest(1, TimeUnit.SECONDS)!!
        val passwordRequest = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("GET", policyRequest.method)
        assertEquals("GET", passwordRequest.method)
        assertEquals("/functions/v1/api/locker/device/me/policy", policyRequest.path)
        assertEquals("/functions/v1/api/locker/devices/101/password", passwordRequest.path)
        assertEquals(0L, policyRequest.bodySize)
        assertEquals(0L, passwordRequest.bodySize)
        assertEquals("Bearer lkd_device_test", policyRequest.getHeader("Authorization"))
        assertEquals("Bearer lkd_device_test", passwordRequest.getHeader("Authorization"))
        assertEquals("locker-101-1", passwordRequest.getHeader("X-Locker-If-Revision"))
        assertFalse(password.toString().contains("0123"))
    }

    @Test
    fun emptyPasswordIsAnExplicitNullWithAnUnlockedPolicy() = runBlocking {
        server.enqueue(policy("v1", enabled = false, hasPassword = false))
        server.enqueue(json("""{"deviceId":"101","password":null}""").setHeader("X-Locker-Policy-Revision", "v1"))
        val snapshot = api.readConsistentPolicy(credentials)
        assertFalse(snapshot.first.lockEnabled)
        assertNull(snapshot.second.password)
    }

    @Test
    fun concurrentPolicyChangeRefetchesBothHalvesInsteadOfCombiningRevisions() = runBlocking {
        server.enqueue(policy("v1"))
        server.enqueue(json("""{"error":{"code":"POLICY_CHANGED"}}""").setResponseCode(412))
        server.enqueue(policy("v2", enabled = false))
        server.enqueue(password("5678", "v2"))

        val snapshot = api.readConsistentPolicy(credentials)

        assertEquals("v2", snapshot.first.revision)
        assertFalse(snapshot.first.lockEnabled)
        assertEquals("5678", snapshot.second.password)
        assertEquals(4, server.requestCount)
        repeat(2) { server.takeRequest() }
        assertEquals("/functions/v1/api/locker/device/me/policy", server.takeRequest().path)
        assertEquals("v2", server.takeRequest().getHeader("X-Locker-If-Revision"))
    }

    @Test
    fun mismatchedResponseRevisionAlsoRefetchesBothHalves() = runBlocking {
        server.enqueue(policy("v1"))
        server.enqueue(password("0123", "v2"))
        server.enqueue(policy("v3"))
        server.enqueue(password("9999", "v3"))
        assertEquals("v3", api.readConsistentPolicy(credentials).first.revision)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun repeatedRevisionChangesStopAfterThreeAttempts() = runBlocking {
        repeat(3) {
            server.enqueue(policy("v$it"))
            server.enqueue(json("""{"error":{"code":"POLICY_CHANGED"}}""").setResponseCode(412))
        }
        val error = expectFailure<LockerDeviceApiException> { api.readConsistentPolicy(credentials) }
        assertEquals(412, error.statusCode)
        assertEquals(6, server.requestCount)
        assertFalse(permitsOfflineFallback(error))
    }

    @Test
    fun missingRevisionAndNonBooleanPolicyNeverBecomeUnlockedDefaults() = runBlocking {
        server.enqueue(json("""{"lockEnabled":false,"hasPassword":false}"""))
        assertInvalid { api.getPolicy(credentials) }
        server.enqueue(json("""{"lockEnabled":"false","hasPassword":false}""").setHeader("X-Locker-Policy-Revision", "v1"))
        assertInvalid { api.getPolicy(credentials) }
        server.enqueue(policy("v1", enabled = true, hasPassword = false))
        assertInvalid { api.getPolicy(credentials) }
    }

    @Test
    fun inconsistentPasswordFlagIsRejectedWithoutStoringEitherHalf() = runBlocking {
        server.enqueue(policy("v1", enabled = false, hasPassword = false))
        server.enqueue(password("0123", "v1"))
        assertInvalid { api.readConsistentPolicy(credentials) }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun missingNumericAndOtherDevicePasswordsAreRejected() = runBlocking {
        listOf(
            """{"deviceId":"101"}""",
            """{"deviceId":"101","password":1234}""",
            """{"deviceId":"102","password":"0123"}""",
            """{"deviceId":"101","password":"٠١٢٣"}""",
        ).forEach { body ->
            server.enqueue(json(body).setHeader("X-Locker-Policy-Revision", "v1"))
            assertInvalid { api.getPassword(credentials, "v1") }
        }
    }

    @Test
    fun unlockPostsTheFourDigitStringAndTreatsFalseAsAuthoritative() = runBlocking {
        server.enqueue(json("""{"valid":false}"""))
        assertFalse(api.unlock(credentials, "0123"))
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/functions/v1/api/locker/device/me/unlock", request.path)
        assertEquals("Bearer lkd_device_test", request.getHeader("Authorization"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals(1, body.length())
        assertEquals("0123", body.getString("password"))

        server.enqueue(json("""{"valid":"true"}"""))
        assertInvalid { api.unlock(credentials, "0123") }
    }

    @Test
    fun incorrectPinShapeFailsBeforeAnyNetworkRequest() = runBlocking {
        expectFailure<IllegalArgumentException> { api.unlock(credentials, "123") }
        expectFailure<IllegalArgumentException> { api.unlock(credentials, "１２３４") }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun rateLimitRemainsRateLimitDuringBackoffAndNeverAllowsOfflineFallback() = runBlocking {
        server.enqueue(json("""{"valid":false}""").setResponseCode(429).setHeader("Retry-After", "60"))
        val first = expectFailure<LockerDeviceApiException> { api.unlock(credentials, "0123") }
        assertEquals(429, first.statusCode)
        assertEquals(60L, first.retryAfterSeconds)
        assertFalse(permitsOfflineFallback(first))
        now += 1_001
        val waiting = expectFailure<LockerDeviceApiException> { api.getPolicy(credentials) }
        assertEquals(429, waiting.statusCode)
        assertEquals(59L, waiting.retryAfterSeconds)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun rateLimitWithoutHeaderUsesTheDocumentedSixtySeconds() = runBlocking {
        server.enqueue(json("""{"valid":false}""").setResponseCode(429))
        val failure = expectFailure<LockerDeviceApiException> { api.unlock(credentials, "0123") }
        assertEquals(60L, failure.retryAfterSeconds)
    }

    @Test
    fun serviceBackoffPreserves503SoOfflineCacheCanStillBeUsed() = runBlocking {
        server.enqueue(json("""{"error":{"code":"SERVICE_UNAVAILABLE"}}""")
            .setResponseCode(503).setHeader("Retry-After", "30"))
        val first = expectFailure<LockerDeviceApiException> { api.getPolicy(credentials) }
        assertEquals(503, first.statusCode)
        assertTrue(permitsOfflineFallback(first))
        val waiting = expectFailure<LockerDeviceApiException> { api.unlock(credentials, "0123") }
        assertEquals(503, waiting.statusCode)
        assertTrue(permitsOfflineFallback(waiting))
        assertEquals(1, server.requestCount)

        now += 30_001
        server.enqueue(json("""{"valid":true}"""))
        assertTrue(api.unlock(credentials, "0123"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun retryAfterHttpDateIsHonored() = runBlocking {
        val deadline = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(now + 90_000).atZone(ZoneOffset.UTC))
        server.enqueue(json("""{"error":{"code":"SERVICE_UNAVAILABLE"}}""")
            .setResponseCode(503).setHeader("Retry-After", deadline))
        val failure = expectFailure<LockerDeviceApiException> { api.getPolicy(credentials) }
        assertEquals(90L, failure.retryAfterSeconds)
    }

    @Test
    fun credentialBearingRequestsNeverFollowRedirects() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/redirect")))
        val failure = expectFailure<LockerDeviceApiException> { api.unlock(credentials, "0123") }
        assertEquals(307, failure.statusCode)
        assertFalse(permitsOfflineFallback(failure))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aDisconnectedUnlockIsNotAutomaticallyReplayed() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        expectFailure<IOException> { api.unlock(credentials, "0123") }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun serviceRetryAfterZeroDoesNotAutomaticallyReplayAnUnlockAttempt() = runBlocking {
        server.enqueue(json("""{"error":{"code":"SERVICE_UNAVAILABLE"}}""")
            .setResponseCode(503).setHeader("Retry-After", "0"))
        // A second response makes a hidden retry observable rather than hanging the test.
        server.enqueue(json("""{"valid":true}"""))
        val failure = expectFailure<LockerDeviceApiException> { api.unlock(credentials, "0123") }
        assertEquals(503, failure.statusCode)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun cancellingVerificationCancelsTheHttpCallWithoutReplayingIt() = runBlocking {
        server.enqueue(json("""{"valid":true}""").setBodyDelay(1, TimeUnit.SECONDS))
        val verification = async(Dispatchers.IO) { api.unlock(credentials, "0123") }
        assertEquals("POST", server.takeRequest(1, TimeUnit.SECONDS)!!.method)
        verification.cancelAndJoin()
        assertTrue(verification.isCancelled)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun unstructuredErrorContentsNeverEnterExceptionMessages() = runBlocking {
        server.enqueue(json("""{"error":{"code":"lkd_secret_value","message":"PIN 0123"}}""").setResponseCode(401))
        val failure = expectFailure<LockerDeviceApiException> { api.getPolicy(credentials) }
        assertEquals("HTTP_401", failure.errorCode)
        assertFalse(failure.message.orEmpty().contains("0123"))
        assertFalse(failure.message.orEmpty().contains("secret"))
    }

    @Test
    fun trailingGarbageCannotBeAcceptedAsAnUnlockResponse() = runBlocking {
        server.enqueue(json("""{"valid":true} ignored"""))
        assertInvalid { api.unlock(credentials, "0123") }
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun policy(revision: String, enabled: Boolean = true, hasPassword: Boolean = true) =
        json("""{"lockEnabled":$enabled,"hasPassword":$hasPassword}""").setHeader("X-Locker-Policy-Revision", revision)

    private fun password(pin: String, revision: String) =
        json("""{"deviceId":"101","password":"$pin"}""").setHeader("X-Locker-Policy-Revision", revision)

    private suspend fun assertInvalid(block: suspend () -> Unit) {
        assertEquals("INVALID_RESPONSE", expectFailure<LockerDeviceApiException>(block).errorCode)
    }

    private suspend inline fun <reified T : Throwable> expectFailure(block: suspend () -> Unit): T {
        try {
            block()
        } catch (failure: Throwable) {
            if (failure is T) return failure
            throw AssertionError("Unexpected failure type: ${failure.javaClass.simpleName}", failure)
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }
}
