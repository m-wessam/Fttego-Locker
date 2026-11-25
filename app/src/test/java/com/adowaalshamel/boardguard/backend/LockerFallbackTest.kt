package com.adowaalshamel.boardguard.backend

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockerFallbackTest {
    @Test
    fun onlyTransportLossAndExplicit503AllowCachedVerification() {
        assertTrue(permitsOfflineFallback(IOException("Connection unavailable")))
        assertTrue(permitsOfflineFallback(LockerDeviceApiException(503, "SERVICE_UNAVAILABLE", 60)))
        listOf(200, 302, 400, 401, 403, 404, 409, 412, 429, 500, 502, 504).forEach { status ->
            assertFalse("HTTP $status must not allow cache", permitsOfflineFallback(LockerDeviceApiException(status, "TEST_ERROR")))
        }
        assertFalse(permitsOfflineFallback(LockerLocalFailure(LockerFailure.STORAGE)))
        assertFalse(permitsOfflineFallback(IllegalStateException("Malformed response")))
    }

    @Test
    fun storageAndAuthorizationStayDistinctFromNetworkFailures() {
        assertEquals(LockerFailure.STORAGE, classifyLockerFailure(LockerLocalFailure(LockerFailure.STORAGE)))
        assertEquals(LockerFailure.NETWORK, classifyLockerFailure(IOException()))
        assertEquals(LockerFailure.AUTHORIZATION, classifyLockerFailure(LockerDeviceApiException(401, "UNAUTHORIZED")))
        assertEquals(LockerFailure.NOT_REGISTERED, classifyLockerFailure(LockerDeviceApiException(403, "DEVICE_KEY_NOT_APPROVED")))
        assertEquals(LockerFailure.RATE_LIMITED, classifyLockerFailure(LockerDeviceApiException(429, "TOO_MANY_ATTEMPTS", 60)))
        assertEquals(LockerFailure.INVALID_RESPONSE, classifyLockerFailure(LockerDeviceApiException(200, "INVALID_RESPONSE")))
    }
}
