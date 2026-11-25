package fr.paul.telecommande.ui

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import fr.paul.telecommande.core.DiagLog
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Confirmation « c'est bien le parent » par l'empreinte (ou le visage), sans le code de verrouillage du téléphone :
 * un enfant qui connaît ce code ne passe pas.
 *
 * Renfort : quand le téléphone le permet (empreinte de classe forte, Android 7+), la validation passe par une clé
 * du coffre-fort du téléphone, invalidée par Android dès qu'une NOUVELLE empreinte est enregistrée. Un enfant qui
 * ajouterait son doigt (en connaissant le code du téléphone) ne peut donc pas s'en servir : le code parent est alors
 * exigé une fois, puis la clé est recréée.
 */
object ParentAuth {
    private const val KEY = "parent_bio_v1"

    enum class Level { STRONG, WEAK, NONE }

    fun level(c: Context): Level {
        val bm = BiometricManager.from(c)
        return when {
            Build.VERSION.SDK_INT >= 24 && bm.canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS -> Level.STRONG
            bm.canAuthenticate(BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS -> Level.WEAK
            else -> Level.NONE
        }
    }

    fun available(c: Context) = level(c) != Level.NONE

    /** Recrée la clé (après création du code parent, ou après une saisie du code suite à une nouvelle empreinte). */
    fun reset() {
        if (Build.VERSION.SDK_INT < 24) return
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY) }
    }

    private class Invalidated : Exception()

    /** Chiffreur lié à l'empreinte ; [Invalidated] si une empreinte a été ajoutée depuis sa création. */
    private fun cipher(): Cipher {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(KEY)) {
            val spec = KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(true)
                .apply {
                    if (Build.VERSION.SDK_INT >= 24) setInvalidatedByBiometricEnrollment(true)
                    if (Build.VERSION.SDK_INT >= 30) setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                }
                .build()
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
        }
        val key = ks.getKey(KEY, null) as SecretKey
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            try { init(Cipher.ENCRYPT_MODE, key) } catch (e: KeyPermanentlyInvalidatedException) { throw Invalidated() }
        }
    }

    /**
     * Ouvre l'empreinte. [onCode] : le parent préfère taper son code (ou l'empreinte est indisponible / bloquée).
     * [onNewFingerprint] : une empreinte a été ajoutée sur ce téléphone, le code parent est exigé.
     */
    fun prompt(a: FragmentActivity, title: String, subtitle: String, onOk: () -> Unit, onCode: () -> Unit, onNewFingerprint: () -> Unit) {
        val lvl = level(a)
        if (lvl == Level.NONE) { onCode(); return }
        var crypto: BiometricPrompt.CryptoObject? = null
        if (lvl == Level.STRONG) {
            try { crypto = BiometricPrompt.CryptoObject(cipher()) }
            catch (e: Invalidated) {
                DiagLog.log("PARENTAL", "Nouvelle empreinte enregistrée sur le téléphone : code parent exigé")
                onNewFingerprint(); return
            } catch (e: Exception) { DiagLog.log("PARENTAL", "Clé d'empreinte indisponible (${e.javaClass.simpleName}) : empreinte simple") }
        }
        var handled = false
        val p = BiometricPrompt(a, ContextCompat.getMainExecutor(a), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (handled) return; handled = true
                // Avec la clé : on s'en sert vraiment, preuve que c'est une empreinte enregistrée AVANT la clé
                val c = result.cryptoObject?.cipher
                if (crypto != null && (c == null || runCatching { c.doFinal(byteArrayOf(1)) }.isFailure)) { onCode(); return }
                onOk()
            }
            override fun onAuthenticationError(code: Int, msg: CharSequence) {
                if (handled) return; handled = true
                if (!a.isFinishing) onCode()
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title).setSubtitle(subtitle)
            .setAllowedAuthenticators(if (crypto != null) BIOMETRIC_STRONG else BIOMETRIC_WEAK)
            .setNegativeButtonText("Taper le code parent")
            .build()
        if (crypto != null) p.authenticate(info, crypto) else p.authenticate(info)
    }
}
