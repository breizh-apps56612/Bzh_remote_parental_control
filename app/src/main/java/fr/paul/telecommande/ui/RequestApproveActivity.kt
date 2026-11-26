package fr.paul.telecommande.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import fr.paul.telecommande.core.BoxLink
import fr.paul.telecommande.core.DeviceStore
import kotlinx.coroutines.launch

/**
 * Bouton « Accepter » de la notification « Demande de temps ».
 * Avant : la réponse partait directement, même téléphone verrouillé (un enfant pouvait s'accorder du temps).
 * Maintenant : déverrouillage du téléphone (Android), puis empreinte ou code parent, puis envoi à la TV.
 */
class RequestApproveActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val device = intent.getStringExtra("device")?.let { DeviceStore(this).get(it) } ?: run { finish(); return }
        val rid = intent.getStringExtra("rid") ?: run { finish(); return }
        val minutes = intent.getIntExtra("minutes", 15)
        val what = intent.getStringExtra("what").orEmpty().ifBlank { "$minutes min de plus" }
        if (savedInstanceState != null) return   // rotation : la fenêtre de validation est déjà ouverte
        Ui.requirePin(this, "Accorder ${what} sur ${device.name} ?", cancel = { finish() }) {
            lifecycleScope.launch {
                BoxLink.respond(this@RequestApproveActivity, device, rid, true, minutes)
                    .onSuccess { Ui.toast(this@RequestApproveActivity, "Temps accordé") }
                    .onFailure { Ui.toast(this@RequestApproveActivity, "Box injoignable : réponse non envoyée") }
                finish()
            }
        }
    }
}
