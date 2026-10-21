package `in`.goxl.mydooraccess

import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import java.security.KeyStore
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Holds the shared HMAC secret inside the Android Keystore. The key is non-exportable and every use
 * requires a fresh biometric or device-credential authentication (bound through a CryptoObject).
 */
object KeystoreSigner {
    private const val ALIAS = "door-relay-hmac"
    private const val ALGORITHM = "HmacSHA256"

    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun hasKey(): Boolean = try { store().containsAlias(ALIAS) } catch (e: Exception) { false }

    fun importSecret(secret: ByteArray) {
        val protection = KeyProtection.Builder(KeyProperties.PURPOSE_SIGN)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            .build()
        store().setEntry(ALIAS, KeyStore.SecretKeyEntry(SecretKeySpec(secret, ALGORITHM)), protection)
    }

    fun delete() { try { store().deleteEntry(ALIAS) } catch (_: Exception) {} }

    /** An initialised Mac that cannot produce output until the user authenticates through a BiometricPrompt. */
    fun newMac(): Mac = Mac.getInstance(ALGORITHM).apply { init(store().getKey(ALIAS, null) as SecretKey) }
}
