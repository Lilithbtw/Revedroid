package app.revedroid.ui.lock

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal

/** Whether a fingerprint, face or other biometric is enrolled and usable. */
fun Context.canUseBiometrics(): Boolean {
    val manager = getSystemService(BiometricManager::class.java) ?: return false
    val result =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        } else {
            @Suppress("DEPRECATION")
            manager.canAuthenticate()
        }
    return result == BiometricManager.BIOMETRIC_SUCCESS
}

/** Shows the system biometric prompt; [onSuccess] runs on the main thread. */
fun Activity.authenticateWithBiometrics(
    title: String,
    cancelLabel: String,
    onSuccess: () -> Unit,
) {
    val builder =
        BiometricPrompt
            .Builder(this)
            .setTitle(title)
            .setNegativeButton(cancelLabel, mainExecutor) { _, _ -> }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
    }
    builder.build().authenticate(
        CancellationSignal(),
        mainExecutor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
        },
    )
}
