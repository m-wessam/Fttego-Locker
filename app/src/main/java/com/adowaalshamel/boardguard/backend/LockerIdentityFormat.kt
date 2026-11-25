package com.adowaalshamel.boardguard.backend

import java.security.KeyPair
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import java.util.Locale

/** Wire-format rules shared by enrollment and signed activation. */
internal object LockerIdentityFormat {
    private val macPattern = Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}")
    private val noncePattern = Regex("[0-9a-f]{64}")

    fun normalizeMac(value: String): String {
        val mac = value.trim().uppercase(Locale.ROOT)
        require(macPattern.matches(mac)) { "ETHERNET_IDENTITY_INVALID" }
        require(mac !in setOf("00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF", "02:00:00:00:00:00")) {
            "ETHERNET_IDENTITY_INVALID"
        }
        require(mac.substring(0, 2).toInt(16) and 1 == 0) { "ETHERNET_IDENTITY_INVALID" }
        return mac
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun encodePublicKey(der: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(der)

    fun canonicalMessage(macAddress: String, publicKeyDer: ByteArray, nonce: String, issuedAt: Long): String {
        require(noncePattern.matches(nonce)) { "ACTIVATION_NONCE_INVALID" }
        require(issuedAt >= 0) { "ACTIVATION_TIME_INVALID" }
        val fingerprint = hex(MessageDigest.getInstance("SHA-256").digest(publicKeyDer))
        return listOf(
            "FTTEGO-LOCKER-ACTIVATE-v1",
            normalizeMac(macAddress),
            fingerprint,
            nonce,
            issuedAt.toString(),
        ).joinToString("\n")
    }

    fun activationRequest(macAddress: String, keyPair: KeyPair, nonceBytes: ByteArray, issuedAt: Long): LockerActivationRequest {
        require(nonceBytes.size == 32) { "ACTIVATION_NONCE_INVALID" }
        val mac = normalizeMac(macAddress)
        val der = keyPair.public.encoded
        val nonce = hex(nonceBytes)
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(keyPair.private)
        signer.update(canonicalMessage(mac, der, nonce, issuedAt).toByteArray(Charsets.UTF_8))
        return LockerActivationRequest(
            macAddress = mac,
            publicKey = encodePublicKey(der),
            nonce = nonce,
            issuedAt = issuedAt,
            signature = Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign()),
        )
    }
}
