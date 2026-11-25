package fr.paul.telecommande.drivers

import android.content.Context
import fr.paul.telecommande.core.Device
import fr.paul.telecommande.core.DeviceType
import fr.paul.telecommande.core.RemoteKey

/** Interactions nécessaires pendant l'appairage (saisie d'un code affiché sur la TV, message). */
interface PairingUi {
    suspend fun askCode(title: String, message: String, hint: String): String?
    fun info(message: String)
}

/**
 * Un pilote traduit les touches logiques vers un protocole (IR, WebSocket, HTTP, TLS/protobuf…).
 * connect() est appelé avant le premier envoi ; send() renvoie false si la touche n'existe pas
 * pour cet appareil.
 */
abstract class Driver(val device: Device) {
    /** Appelé quand un jeton d'appairage change et doit être sauvegardé. */
    var onDeviceUpdated: ((Device) -> Unit)? = null

    open suspend fun connect(ui: PairingUi) {}
    abstract suspend fun send(key: RemoteKey): Boolean
    open suspend fun powerOn(): Boolean = WakeOnLan.send(device.mac, device.host)
    open fun close() {}

    protected fun persist() { onDeviceUpdated?.invoke(device) }

    companion object {
        fun create(context: Context, device: Device): Driver = when (device.type) {
            DeviceType.IR -> IrDriver(context, device)
            DeviceType.SAMSUNG -> SamsungDriver(device)
            DeviceType.LG -> LgDriver(device)
            DeviceType.ANDROID_TV -> AndroidTvDriver(context, device)
            DeviceType.ROKU -> RokuDriver(device)
            DeviceType.SONY -> SonyDriver(device)
            DeviceType.PHILIPS -> PhilipsDriver(device)
            DeviceType.PANASONIC -> PanasonicDriver(device)
            DeviceType.FREEBOX -> FreeboxDriver(device)
            DeviceType.KODI -> KodiDriver(device)
        }
    }
}
