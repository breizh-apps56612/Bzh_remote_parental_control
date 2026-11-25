package fr.paul.telecommande.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import fr.paul.telecommande.core.DiagLog

/**
 * Code parent oublié : on le remplace UNIQUEMENT après le verrouillage du téléphone
 * (code, schéma ou empreinte du parent). Un enfant ne peut donc pas contourner le contrôle parental.
 */
class PinResetActivity : AppCompatActivity() {
    companion object {
        private var onDone: (() -> Unit)? = null
        fun start(a: Activity, done: () -> Unit) {
            onDone = done
            a.startActivity(Intent(a, PinResetActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        val intent = if (km.isKeyguardSecure) km.createConfirmDeviceCredentialIntent(
            "Code parent oublié", "Déverrouillez le téléphone pour créer un nouveau code parent."
        ) else null
        if (intent == null) {
            AlertDialog.Builder(this).setTitle("Code parent oublié")
                .setMessage("Ce téléphone n'a pas de verrouillage d'écran : impossible de vérifier que vous êtes le parent.\n\n" +
                    "Ajoutez un verrouillage (Paramètres › Sécurité › Verrouillage de l'écran), puis réessayez.")
                .setPositiveButton("Compris") { _, _ -> finish() }
                .setOnCancelListener { finish() }.show()
            return
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) { DiagLog.log("PARENTAL", "Réinitialisation du code parent annulée"); finish(); return }
        DiagLog.log("PARENTAL", "Identité confirmée par le verrouillage du téléphone : nouveau code parent")
        Ui.createPin(this) { created ->
            if (created) { DiagLog.log("PARENTAL", "Nouveau code parent enregistré"); onDone?.invoke() }
            onDone = null
            finish()
        }
    }
}
