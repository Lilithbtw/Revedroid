package app.revedroid.data.store

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM Keystore key that works only right after a strong biometric match, so
 * biometric unlock can't be faked by calling the prompt's success callback. The
 * sealed secret means nothing by itself: opening it is the proof, because the key
 * refuses to work without a match and GCM rejects anything it didn't seal.
 * Enrolling a new biometric destroys the key.
 */
class BiometricKey(
    private val alias: String = "revedroid_biometric",
) {
    /** A cipher for the prompt that turns biometric unlock on, replacing any older key; null if none can be made. */
    fun encrypter(): Cipher? =
        runCatching {
            val spec =
                KeyGenParameterSpec
                    .Builder(
                        alias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(true)
                    .setInvalidatedByBiometricEnrollment(true)
                    .build()
            val key =
                KeyGenerator
                    .getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
                    .apply { init(spec) }
                    .generateKey()
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        }.getOrNull()

    /** Seals a random secret with an [encrypter] the prompt has unlocked; null if the key refused. */
    fun seal(cipher: Cipher): String? =
        runCatching {
            val secret = ByteArray(32).also(SecureRandom()::nextBytes)
            val iv = cipher.iv
            val sealed = cipher.doFinal(secret)
            val out = ByteArray(1 + iv.size + sealed.size)
            out[0] = iv.size.toByte()
            iv.copyInto(out, 1)
            sealed.copyInto(out, 1 + iv.size)
            Base64.encodeToString(out, Base64.NO_WRAP)
        }.getOrNull()

    /** A cipher for the unlock prompt, or null when the key is gone, e.g. a new enrollment invalidated it. */
    fun decrypter(sealed: String): Cipher? =
        runCatching {
            val raw = Base64.decode(sealed, Base64.NO_WRAP)
            val iv = raw.copyOfRange(1, 1 + raw[0])
            val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            val key = keyStore.getKey(alias, null) as SecretKey
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        }.getOrNull()

    /** Whether a [decrypter] the prompt has unlocked opens [sealed]. */
    fun open(
        cipher: Cipher,
        sealed: String,
    ): Boolean =
        runCatching {
            val raw = Base64.decode(sealed, Base64.NO_WRAP)
            val offset = 1 + raw[0]
            cipher.doFinal(raw, offset, raw.size - offset)
        }.isSuccess

    fun delete() {
        runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(alias) }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
