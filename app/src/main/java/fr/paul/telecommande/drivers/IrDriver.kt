package fr.paul.telecommande.drivers

import android.content.Context
import android.hardware.ConsumerIrManager
import fr.paul.telecommande.core.Device
import fr.paul.telecommande.core.RemoteException
import fr.paul.telecommande.core.RemoteKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Pilote infrarouge via l'émetteur intégré (Xiaomi/Redmi/POCO, Huawei, Honor, anciens Samsung/LG/HTC…).
 * La marque « auto » envoie la touche dans tous les protocoles connus, l'un après l'autre.
 */
class IrDriver(context: Context, device: Device) : Driver(device) {
    private val ir = context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
    private var toggle = false

    override suspend fun connect(ui: PairingUi) {
        if (ir == null || !ir.hasIrEmitter()) {
            throw RemoteException("Ce téléphone n'a pas d'émetteur infrarouge. Utilisez un appareil Wi-Fi à la place.")
        }
    }

    override suspend fun send(key: RemoteKey): Boolean = withContext(Dispatchers.IO) {
        val manager = ir ?: throw RemoteException("Infrarouge indisponible")
        toggle = !toggle
        val brands = if (device.irBrand == IrBrands.AUTO || device.irBrand.isBlank()) IrBrands.ALL
        else listOfNotNull(IrBrands.byId(device.irBrand))
        var sent = false
        for (b in brands) {
            val frame = IrBrands.frame(b, key, toggle) ?: continue
            try {
                manager.transmit(frame.frequency, frame.pattern)
                sent = true
            } catch (e: Exception) {
                throw RemoteException("Échec de l'émission IR : ${e.message}", e)
            }
            if (brands.size > 1) delay(150)
        }
        sent
    }

    override suspend fun powerOn(): Boolean = send(RemoteKey.POWER)
}
