package com.adowaalshamel.boardguard.backend

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

class LockerIdentityFormatTest {
    @Test fun activationSignatureMatchesExactServerCanonicalFormat() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2_048) }.generateKeyPair()
        val nonceBytes = ByteArray(32) { it.toByte() }
        val request = LockerIdentityFormat.activationRequest("aa:bb:cc:dd:ee:01", keyPair, nonceBytes, 1_795_440_000L)
        val expectedNonce = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(keyPair.public.encoded)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val canonical = "FTTEGO-LOCKER-ACTIVATE-v1\nAA:BB:CC:DD:EE:01\n$fingerprint\n$expectedNonce\n1795440000"
        assertEquals(canonical, LockerIdentityFormat.canonicalMessage(request.macAddress, keyPair.public.encoded, request.nonce, request.issuedAt))
        assertFalse(canonical.endsWith("\n"))
        assertEquals(expectedNonce, request.nonce)
        assertFalse(request.signature.contains('='))
        assertFalse(request.publicKey.contains('='))
        val der = Base64.getUrlDecoder().decode(request.publicKey)
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der)) as RSAPublicKey
        assertEquals(2_048, publicKey.modulus.bitLength())
        assertArrayEquals(keyPair.public.encoded, der)
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(publicKey)
        verifier.update(canonical.toByteArray(Charsets.UTF_8))
        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(request.signature)))
        verifier.initVerify(publicKey)
        verifier.update((canonical + "\n").toByteArray(Charsets.UTF_8))
        assertFalse(verifier.verify(Base64.getUrlDecoder().decode(request.signature)))
    }

    @Test fun freshNonceChangesSignedRequest() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2_048) }.generateKeyPair()
        val first = LockerIdentityFormat.activationRequest("AA:BB:CC:DD:EE:01", pair, ByteArray(32), 100L)
        val second = LockerIdentityFormat.activationRequest("AA:BB:CC:DD:EE:01", pair, ByteArray(32) { 1 }, 100L)
        assertNotEquals(first.nonce, second.nonce)
        assertNotEquals(first.signature, second.signature)
        assertEquals(first.publicKey, second.publicKey)
    }

    @Test fun rejectsPlaceholderAndMulticastIdentities() {
        for (mac in listOf("00:00:00:00:00:00", "02:00:00:00:00:00", "FF:FF:FF:FF:FF:FF", "01:23:45:67:89:AB", "eth0")) {
            assertThrows(IllegalArgumentException::class.java) { LockerIdentityFormat.normalizeMac(mac) }
        }
        assertEquals("AA:BB:CC:DD:EE:01", LockerIdentityFormat.normalizeMac("aa:bb:cc:dd:ee:01"))
    }

    @Test fun rejectsWrongNonceShapeAndNegativeTimestamp() {
        assertThrows(IllegalArgumentException::class.java) {
            LockerIdentityFormat.canonicalMessage("AA:BB:CC:DD:EE:01", byteArrayOf(1), "A".repeat(64), 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LockerIdentityFormat.canonicalMessage("AA:BB:CC:DD:EE:01", byteArrayOf(1), "0".repeat(64), -1)
        }
    }
}
