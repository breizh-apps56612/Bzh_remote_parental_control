package fr.paul.telecommande.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import fr.paul.telecommande.core.BoxClient
import fr.paul.telecommande.core.Device
import fr.paul.telecommande.core.DeviceStore
import fr.paul.telecommande.core.DiagLog
import fr.paul.telecommande.core.DriverHub
import fr.paul.telecommande.drivers.AndroidTvDriver
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * « Partager » → « Télécommande » depuis YouTube, le navigateur… : le lien s'ouvre sur la TV choisie.
 * Avec l'appli Box : ouverture directe (YouTube s'ouvre dans l'appli YouTube de la TV).
 * Sans : envoi du lien par la télécommande Android TV.
 */
class ShareToTvActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.dataString ?: ""
        val url = Regex("""https?://\S+""").find(text)?.value
        if (url == null) { Ui.toast(this, "Aucun lien à envoyer à la TV"); finish(); return }
        val tvs = DeviceStore(this).all().filter { it.isSmartBox }
        when {
            tvs.isEmpty() -> { Ui.toast(this, "Ajoutez d'abord une box Android TV dans l'appli"); finish() }
            tvs.size == 1 -> send(tvs[0], url)
            else -> AlertDialog.Builder(this).setTitle("Ouvrir sur quelle TV ?")
                .setItems(tvs.map { it.name }.toTypedArray()) { _, i -> send(tvs[i], url) }
                .setOnCancelListener { finish() }.show()
        }
    }

    private fun send(d: Device, url: String) {
        DiagLog.log("PARTAGE", "Lien envoyé à ${d.name} : $url")
        lifecycleScope.launch {
            val ok = if (d.hasBoxApp) runCatching { BoxClient.of(this@ShareToTvActivity, d).call("open_url", JSONObject().put("url", url)) }.isSuccess else false
            val done = ok || runCatching {
                val tv = DriverHub.driver(this@ShareToTvActivity, d) as AndroidTvDriver
                tv.connect(DriverHub.SilentPairing); tv.launchApp(url); true
            }.getOrDefault(false)
            Ui.toast(this@ShareToTvActivity, if (done) "Ouverture sur ${d.name}…" else "La TV ne répond pas")
            finish()
        }
    }
}
