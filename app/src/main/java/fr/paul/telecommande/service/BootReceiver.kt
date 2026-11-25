package fr.paul.telecommande.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import fr.paul.telecommande.core.DeviceStore
import fr.paul.telecommande.core.ParentalStore

/** Relance le suivi / contrôle parental après un redémarrage du téléphone. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val parental = ParentalStore(context)
        val needed = DeviceStore(context).all().any {
            it.isSmartBox && (it.hasBoxApp || it.monitorEnabled || parental.rules(it.id).enabled ||
                (it.ytScreenId.isNotBlank() && (it.ytAutoSkipAds || it.ytSkipSponsors)))
        }
        if (needed) AssistantService.start(context)
    }
}
