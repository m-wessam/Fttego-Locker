package com.adowaalshamel.boardguard.enrollment

import org.junit.Assert.*
import org.junit.Test

class LockerEnrollmentAccessTest {
    @Test fun identityHandoffOnlyAllowsRootAndShell() {
        assertTrue(LockerEnrollmentAccess.isTrustedCaller(0))
        assertTrue(LockerEnrollmentAccess.isTrustedCaller(2_000))
        for (uid in listOf(-1, 1_000, 10_000, 12_345, 102_000)) {
            assertFalse(LockerEnrollmentAccess.isTrustedCaller(uid))
        }
    }
}
