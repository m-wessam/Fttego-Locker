package com.adowaalshamel.boardguard.backend

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.NetworkInterface
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey

/** The private RSA key never leaves Android Keystore. Only its public SPKI is exported. */
class LockerIdentity(context: Context) {
    private val macFile = AtomicFile(File(context.applicationContext.noBackupFilesDir, MAC_FILE))

    internal fun enrollmentIdentity(trustedMacAddress: String?): LockerPublicIdentity = synchronized(identityLock) {
        val mac = resolveMac(trustedMacAddress)
        LockerPublicIdentity(mac, LockerIdentityFormat.encodePublicKey(keyPair().public.encoded))
    }

    /** Every retry gets a new nonce/signature; a previously signed request is never replayed. */
    fun createActivationRequest(): LockerActivationRequest = synchronized(identityLock) {
        val mac = resolveMac(null)
        try {
            LockerIdentityFormat.activationRequest(
                macAddress = mac,
                keyPair = keyPair(),
                nonceBytes = ByteArray(32).also { SecureRandom().nextBytes(it) },
                issuedAt = System.currentTimeMillis() / 1_000L,
            )
        } catch (e: IOException) {
            throw e
        } catch (_: Exception) {
            throw IOException("IDENTITY_KEY_UNAVAILABLE")
        }
    }

    private fun resolveMac(trustedMacAddress: String?): String {
        val supplied = trustedMacAddress?.let {
            try {
                LockerIdentityFormat.normalizeMac(it)
            } catch (_: IllegalArgumentException) {
                throw IOException("ETHERNET_IDENTITY_INVALID")
            }
        }
        val stored = readStoredMac()
        val hardware = readEthernetMac()
        val mac = stored ?: supplied ?: hardware ?: throw IOException("ETHERNET_IDENTITY_REQUIRED")
        // The installer already reads eth0 using its trusted ADB shell connection. Modern
        // Android may hide this address from an ordinary app UID, including a Device Owner.
        // If the app can also read it, all three sources must agree. Never use a Wi-Fi MAC.
        if ((supplied != null && supplied != mac) || (hardware != null && hardware != mac)) {
            throw IOException("LOCKER_MAC_MISMATCH")
        }
        if (stored == null) writeStoredMac(mac)
        return mac
    }

    private fun readEthernetMac(): String? = try {
        NetworkInterface.getByName("eth0")?.hardwareAddress
            ?.takeIf { it.size == 6 }
            ?.joinToString(":") { "%02X".format(it.toInt() and 0xff) }
            ?.let(LockerIdentityFormat::normalizeMac)
    } catch (_: Exception) {
        null
    }

    private fun readStoredMac(): String? {
        val base = macFile.baseFile
        if (!base.exists() && !File(base.path + ".bak").exists() && !File(base.path + ".new").exists()) {
            return null
        }
        return try {
            val bytes = macFile.openRead().use { it.readNBytes(128) }
            require(bytes.size == 17)
            LockerIdentityFormat.normalizeMac(bytes.toString(Charsets.US_ASCII))
        } catch (_: Exception) {
            throw IOException("IDENTITY_STORAGE_UNAVAILABLE")
        }
    }

    private fun writeStoredMac(mac: String) {
        var stream: FileOutputStream? = null
        try {
            val bytes = mac.toByteArray(Charsets.US_ASCII)
            stream = macFile.startWrite()
            stream.write(bytes)
            stream.fd.sync()
            macFile.finishWrite(stream)
            stream = null
            // AtomicFile logs some commit failures; verify before reporting enrollment success.
            if (!macFile.readFully().contentEquals(bytes)) throw IOException()
        } catch (_: Exception) {
            try { stream?.let(macFile::failWrite) } catch (_: Exception) { /* Never acknowledge an uncommitted identity. */ }
            throw IOException("IDENTITY_STORAGE_UNAVAILABLE")
        }
    }

    private fun keyPair(): KeyPair = try {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (!store.containsAlias(KEY_ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE).apply {
                initialize(
                    KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                        .setKeySize(2_048)
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                        .setUserAuthenticationRequired(false)
                        .build()
                )
            }.generateKeyPair()
        } else {
            val privateKey = store.getKey(KEY_ALIAS, null) as? PrivateKey ?: throw IOException()
            val publicKey = store.getCertificate(KEY_ALIAS)?.publicKey as? RSAPublicKey ?: throw IOException()
            if (publicKey.modulus.bitLength() != 2_048) throw IOException()
            KeyPair(publicKey, privateKey)
        }
    } catch (_: Exception) {
        // Do not silently replace an unreadable key: the server has approved this exact key.
        throw IOException("IDENTITY_KEY_UNAVAILABLE")
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "locker_activation_rsa_v1"
        private const val MAC_FILE = "locker_ethernet_identity_v1"
        private val identityLock = Any()
    }
}

internal data class LockerPublicIdentity(val macAddress: String, val publicKey: String)
