package fr.paul.telecommande.ui

import android.app.Activity
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleCoroutineScope
import fr.paul.telecommande.core.BoxClient
import fr.paul.telecommande.core.BoxLink
import fr.paul.telecommande.core.Device
import fr.paul.telecommande.core.DeviceStore
import fr.paul.telecommande.core.ParentalStore
import fr.paul.telecommande.service.AssistantService
import fr.paul.telecommande.shared.AppRule
import fr.paul.telecommande.shared.BoxRules
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Appairage sécurisé téléphone ↔ appli Box, avec code affiché sur la TV. */
object BoxPairing {

    fun start(a: Activity, scope: LifecycleCoroutineScope, device: Device, done: () -> Unit) {
        AlertDialog.Builder(a)
            .setTitle("Appli Box sur la TV")
            .setMessage(
                "1. Sur la TV, installez « Télécommande Box » et ouvrez-la.\n" +
                    "2. Activez la protection (une seule étape, guidée à l'écran).\n" +
                    "3. Appuyez sur « Continuer » : un code s'affichera sur la TV."
            )
            .setPositiveButton("Continuer") { _, _ -> Ui.createPinIfNeeded(a) { askCode(a, scope, device, done) } }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun askCode(a: Activity, scope: LifecycleCoroutineScope, device: Device, done: () -> Unit) {
        scope.launch {
            val client = BoxClient(a, device.host, null)
            val (box, hint) = runCatching { client.pairStart() }.getOrElse {
                AlertDialog.Builder(a).setTitle("Box injoignable").setMessage(it.message).setPositiveButton("OK", null).show()
                return@launch
            }
            val input = EditText(a).apply { inputType = InputType.TYPE_CLASS_NUMBER; hint = "Code à 6 chiffres" }
            val box2 = FrameLayout(a).apply { setPadding(Ui.dp(a, 20), 0, Ui.dp(a, 20), 0); addView(input) }
            AlertDialog.Builder(a).setTitle("Code affiché sur la TV ($box)").apply { hint?.let { setMessage(it) } }.setView(box2)
                .setPositiveButton("Appairer") { _, _ ->
                    scope.launch {
                        runCatching { client.pairConfirm(input.text.toString()) }
                            .onSuccess { fp -> finish(a, device, fp); done() }
                            .onFailure { e -> AlertDialog.Builder(a).setTitle("Appairage refusé").setMessage(e.message).setPositiveButton("OK", null).show() }
                    }
                }
                .setNegativeButton("Annuler", null).show()
        }
    }

    private suspend fun finish(a: Activity, device: Device, fp: String) {
        device.boxFp = fp
        DeviceStore(a).save(device)
        // Reprise des règles simples déjà réglées sur le téléphone
        val ps = ParentalStore(a)
        val old = ps.rules(device.id)
        val r = ps.boxRules(device.id).takeIf { it.version > 0 } ?: BoxRules(
            enabled = old.enabled, dailyTotalMin = old.dailyTotalMin, bedtimeEnabled = old.bedtimeEnabled,
            bedtimeStart = old.bedtimeStart, bedtimeEnd = old.bedtimeEnd,
            apps = old.perAppMin.mapValues { AppRule(limitMin = it.value) }.toMutableMap()
        )
        r.pinHash = ps.boxPinHash
        r.version = System.currentTimeMillis()
        ps.saveBoxRules(device.id, r)
        runCatching { BoxClient.of(a, device).call("set_rules", JSONObject().put("rules", r.toJson())) }
        BoxLink.sync(a)
        AssistantService.start(a)
        Ui.toast(a, "Appli Box appairée : la TV applique maintenant les règles elle-même")
    }
}
