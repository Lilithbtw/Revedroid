package app.revedroid.ui.lock

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import javax.crypto.Cipher

/** Whether a strong fingerprint, face or other biometric is enrolled; weaker ones can't unlock a Keystore key. */
fun Context.canUseBiometrics(): Boolean {
    val manager = getSystemService(BiometricManager::class.java) ?: return false
    val result =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            manager.canAuthenticate()
        }
    return result == BiometricManager.BIOMETRIC_SUCCESS
}

/**
 * Shows the system biometric prompt for [cipher], which only works after a match;
 * [onSuccess] gets the unlocked cipher on the main thread.
 */
fun Activity.authenticateWithBiometrics(
    title: String,
    cancelLabel: String,
    cipher: Cipher,
    onSuccess: (Cipher) -> Unit,
) {
    val builder =
        BiometricPrompt
            .Builder(this)
            .setTitle(title)
            .setNegativeButton(cancelLabel, mainExecutor) { _, _ -> }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
    }
    builder.build().authenticate(
        BiometricPrompt.CryptoObject(cipher),
        CancellationSignal(),
        mainExecutor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                result.cryptoObject?.cipher?.let(onSuccess)
            }
        },
    )
}
