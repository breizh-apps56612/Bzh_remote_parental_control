package fr.paul.telecommande.ui

import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import fr.paul.telecommande.core.BoxClient
import fr.paul.telecommande.core.BoxLink
import fr.paul.telecommande.core.DeviceStore
import fr.paul.telecommande.core.DiagLog
import fr.paul.telecommande.core.ParentalStore
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * La TV affiche le pavé du code parent : le parent valide ici, sur SON téléphone
 * (empreinte, visage, verrouillage du téléphone ou code parent tapé à l'abri des regards).
 * Rien n'est validé sans un geste du parent.
 */
class PinApproveActivity : AppCompatActivity() {
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) { finish(); return }
        val device = intent.getStringExtra("device")?.let { DeviceStore(this).get(it) } ?: run { finish(); return }
        val id = intent.getStringExtra("id") ?: run { finish(); return }
        val title = intent.getStringExtra("title").orEmpty()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(BoxLink.PIN_NOTIF)

        fun approve() {
            if (done) return; done = true
            lifecycleScope.launch {
                val r = runCatching { BoxClient.of(this@PinApproveActivity, device).call("pin_approve", JSONObject().put("id", id).put("title", title)) }
                val ok = r.getOrNull()?.optBoolean("ok") == true
                DiagLog.log("PARENTAL", "Validation à distance « $title » (demande $id) : ${if (ok) "acceptée" else r.exceptionOrNull()?.message ?: r.getOrNull()?.optString("error")}")
                val why = r.exceptionOrNull()
                Ui.toast(this@PinApproveActivity, when {
                    ok -> "Validé sur ${device.name} ✓"
                    why is fr.paul.telecommande.core.RemoteException -> why.message ?: "Refusé par la TV"
                    else -> "TV injoignable"
                })
                finish()
            }
        }

        fun withPin() = Ui.requirePin(this, "${device.name} : $title", cancel = { if (!done) finish() }) { approve() }

        val bm = BiometricManager.from(this)
        val auths = if (Build.VERSION.SDK_INT >= 30) BIOMETRIC_WEAK or DEVICE_CREDENTIAL else BIOMETRIC_WEAK
        val canBio = bm.canAuthenticate(auths) == BiometricManager.BIOMETRIC_SUCCESS

        lifecycleScope.launch {
            BoxLink.pinClosed.collect { closed -> if (closed == id && !done) { Ui.toast(this@PinApproveActivity, "Le pavé a été fermé sur la TV"); finish() } }
        }

        AlertDialog.Builder(this)
            .setTitle("${device.name} demande le code parent")
            .setMessage("« $title »\n\nValidez ici : l'enfant ne voit rien sur la TV.")
            .setPositiveButton(if (canBio) "Empreinte / visage" else "Taper le code") { _, _ ->
                if (!canBio) { withPin(); return@setPositiveButton }
                val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = approve()
                    override fun onAuthenticationError(code: Int, msg: CharSequence) {
                        if (code == BiometricPrompt.ERROR_NEGATIVE_BUTTON) withPin() else if (!done) finish()
                    }
                })
                val info = BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Code parent pour ${device.name}").setSubtitle(title)
                    .setAllowedAuthenticators(auths)
                    .apply { if (Build.VERSION.SDK_INT < 30) setNegativeButtonText("Taper le code parent") }
                    .build()
                prompt.authenticate(info)
            }
            .setNeutralButton(if (canBio && ParentalStore(this).hasPin) "Taper le code" else null) { _, _ -> withPin() }
            .setNegativeButton("Refuser") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }
}
