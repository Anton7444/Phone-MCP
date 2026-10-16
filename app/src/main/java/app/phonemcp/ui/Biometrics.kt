package app.phonemcp.ui

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

object Biometrics {
    private const val AUTH = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /** False when the phone has no lock screen / biometrics set up; callers then skip the gate rather than lock the user out. */
    fun available(ctx: Context): Boolean =
        try { BiometricManager.from(ctx).canAuthenticate(AUTH) == BiometricManager.BIOMETRIC_SUCCESS } catch (_: Exception) { false }

    fun prompt(activity: FragmentActivity, title: String, subtitle: String, onResult: (Boolean) -> Unit) {
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true)
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(false)
        }
        try {
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(AUTH)
                .build()
            BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback).authenticate(info)
        } catch (_: Exception) {
            onResult(false)
        }
    }
}
