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

        fun biometric() {
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

        // Fenêtre claire : ce que demande la TV, un grand bouton principal, les autres choix en dessous
        val c = this
        var dialog: androidx.appcompat.app.AlertDialog? = null
        val box = android.widget.LinearLayout(c).apply {
            orientation = android.widget.LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER_HORIZONTAL
            val p = Ui.dp(c, 24); setPadding(p, Ui.dp(c, 28), p, Ui.dp(c, 16))
        }
        box.addView(Ui.badge(c, "🔒").apply { textSize = 26f }, android.widget.LinearLayout.LayoutParams(Ui.dp(c, 64), Ui.dp(c, 64)))
        box.addView(Ui.text(c, "${device.name} demande le code parent", 19f).apply {
            typeface = Ui.bold; gravity = android.view.Gravity.CENTER }, Ui.lp(c, 16))
        box.addView(Ui.text(c, "« $title »", 16f).apply { gravity = android.view.Gravity.CENTER }, Ui.lp(c, 8))
        box.addView(Ui.text(c, "Validez ici : l'enfant ne voit rien sur la TV.", 13.5f, muted = true).apply { gravity = android.view.Gravity.CENTER }, Ui.lp(c, 6))
        box.addView(Ui.button(c, if (canBio) "Valider avec l'empreinte" else "Taper le code parent", primary = true) {
            dialog?.setOnCancelListener(null); dialog?.dismiss(); if (canBio) biometric() else withPin()
        }, android.widget.LinearLayout.LayoutParams(-1, Ui.dp(c, 54)).apply { topMargin = Ui.dp(c, 22) })
        if (canBio && ParentalStore(this).hasPin) box.addView(Ui.button(c, "Taper le code") {
            dialog?.setOnCancelListener(null); dialog?.dismiss(); withPin()
        }, android.widget.LinearLayout.LayoutParams(-1, Ui.dp(c, 50)).apply { topMargin = Ui.dp(c, 10) })
        box.addView(Ui.text(c, "Refuser", 15f, muted = true).apply {
            gravity = android.view.Gravity.CENTER; setPadding(0, Ui.dp(c, 14), 0, Ui.dp(c, 6)); minHeight = Ui.dp(c, 48)
            isClickable = true; isFocusable = true; setOnClickListener { dialog?.dismiss(); finish() }
        }, Ui.lp(c, 4))
        dialog = AlertDialog.Builder(this).setView(box).setOnCancelListener { finish() }.show()
    }
}
