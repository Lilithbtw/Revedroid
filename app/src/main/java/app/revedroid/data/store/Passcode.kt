package app.revedroid.data.store

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Salted PBKDF2 hashes for the app-lock passcode, stored as `salt:hash` in Base64. */
object Passcode {
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 8

    fun isValid(passcode: String): Boolean = passcode.length in MIN_LENGTH..MAX_LENGTH && passcode.all(Char::isDigit)

    fun hash(passcode: String): String {
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        return encoder.encodeToString(salt) + ":" + encoder.encodeToString(derive(passcode, salt))
    }

    fun verify(
        passcode: String,
        stored: String,
    ): Boolean {
        val (salt, expected) =
            runCatching { stored.split(':', limit = 2).map(decoder::decode) }.getOrNull()?.takeIf { it.size == 2 } ?: return false
        return MessageDigest.isEqual(derive(passcode, salt), expected)
    }

    private fun derive(
        passcode: String,
        salt: ByteArray,
    ): ByteArray {
        val spec = PBEKeySpec(passcode.toCharArray(), salt, ITERATIONS, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()
    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256
    private const val ITERATIONS = 120_000
}
