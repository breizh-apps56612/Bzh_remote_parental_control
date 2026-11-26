package fr.paul.telecommande.ui

import android.app.ProgressDialog
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import fr.paul.telecommande.core.DeviceStore
import fr.paul.telecommande.core.DiagLog
import fr.paul.telecommande.core.Device
import fr.paul.telecommande.core.DriverHub
import fr.paul.telecommande.core.RemoteKey
import fr.paul.telecommande.drivers.VestelDriver
import kotlinx.coroutines.launch

/**
 * Test guidé d'une TV Vestel : on envoie « Volume + » par chaque chemin possible et l'utilisateur
 * dit si la TV a réagi. Le chemin qui marche est mémorisé. Chaque étape avance par un clic.
 */
object VestelSetup {

    fun start(activity: AppCompatActivity, device: Device, onDone: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle("Test de la TV")
            .setMessage("Allumez la TV et regardez-la.\n\nJe vais monter le son d'un cran pour trouver la bonne façon de lui parler.")
            .setPositiveButton("Envoyer Volume +") { _, _ -> probe(activity, device, onDone) }
            .setNegativeButton("Plus tard") { _, _ -> onDone() }
            .setCancelable(false)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun probe(activity: AppCompatActivity, device: Device, onDone: () -> Unit) {
        val progress = ProgressDialog(activity).apply { setMessage("Recherche de la TV…"); setCancelable(false); show() }
        activity.lifecycleScope.launch {
            val driver = DriverHub.driver(activity, device) as? VestelDriver
            val methods = driver?.available().orEmpty()
            progress.dismiss()
            if (driver == null || methods.isEmpty()) { failed(activity, device, onDone, unreachable = true); return@launch }
            tryMethod(activity, device, driver, methods, 0, onDone)
        }
    }

    private fun tryMethod(activity: AppCompatActivity, device: Device, driver: VestelDriver,
                          methods: List<VestelDriver.Method>, index: Int, onDone: () -> Unit) {
        if (index >= methods.size) { failed(activity, device, onDone, unreachable = false); return }
        val m = methods[index]
        activity.lifecycleScope.launch {
            val sent = runCatching { driver.sendCode(m, VestelDriver.CODES.getValue(RemoteKey.VOL_UP)) }.isSuccess
            if (!sent) { tryMethod(activity, device, driver, methods, index + 1, onDone); return@launch }
            AlertDialog.Builder(activity)
                .setTitle("Essai ${index + 1} sur ${methods.size}")
                .setMessage("Le son de la TV a-t-il monté d'un cran ?")
                .setPositiveButton("Oui") { _, _ ->
                    device.secret = m.id; driver.device.secret = m.id
                    DeviceStore(activity).save(device)
                    DiagLog.log("VESTEL", "Test réussi : chemin ${m.id} retenu pour ${device.host}")
                    activity.lifecycleScope.launch { runCatching { driver.sendCode(m, VestelDriver.CODES.getValue(RemoteKey.VOL_DOWN)) } }
                    Toast.makeText(activity, "TV prête : le son est remis comme avant", Toast.LENGTH_LONG).show()
                    onDone()
                }
                .setNegativeButton("Non") { _, _ ->
                    DiagLog.log("VESTEL", "Test : pas de réaction par ${m.id}")
                    tryMethod(activity, device, driver, methods, index + 1, onDone)
                }
                .setCancelable(false)
                .show()
        }
    }

    private fun failed(activity: AppCompatActivity, device: Device, onDone: () -> Unit, unreachable: Boolean) {
        DiagLog.log("VESTEL", if (unreachable) "Test : TV injoignable à ${device.host}" else "Test : aucune réaction de la TV")
        val msg = if (unreachable)
            "La TV ne répond pas à ${device.host}.\n\nVérifiez qu'elle est allumée et connectée au même Wi-Fi que le téléphone, puis relancez le test (appui long sur la TV > « Tester la télécommande »)."
        else
            "La TV est bien sur le réseau, mais elle refuse encore les touches envoyées par le Wi-Fi.\n\n" +
            "Sur ces TV, il faut l'autoriser une fois, avec la télécommande de la TV :\n" +
            "Menu (ou Paramètres) > Système > Plus > « Télécommande virtuelle » : Activé.\n" +
            "Activez aussi, si vous le voyez, « Réveil par réseau » / « Wake on LAN ».\n\n" +
            "Puis touchez « Refaire le test »."
        val b = AlertDialog.Builder(activity).setTitle("La TV n'a pas réagi").setMessage(msg).setCancelable(false)
        if (unreachable) b.setPositiveButton("OK") { _, _ -> onDone() }
        else b.setPositiveButton("Refaire le test") { _, _ -> probe(activity, device, onDone) }
            .setNegativeButton("Plus tard") { _, _ -> onDone() }
        b.show()
    }
}
