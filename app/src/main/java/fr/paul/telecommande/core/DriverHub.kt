package fr.paul.telecommande.core

import android.content.Context
import android.provider.Settings
import fr.paul.telecommande.drivers.AndroidTvDriver
import fr.paul.telecommande.drivers.Driver
import fr.paul.telecommande.drivers.PairingUi
import fr.paul.telecommande.youtube.YouTubeAssistant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Connexions partagées entre l'écran télécommande et le service d'arrière-plan :
 * une seule connexion par appareil, un assistant YouTube par box, les minuteurs de veille.
 */
object DriverHub {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val drivers = HashMap<String, Driver>()
    private val assistants = HashMap<String, YouTubeAssistant>()
    private val sleepJobs = HashMap<String, Job>()

    /** Heure (epoch ms) de mise en veille programmée, par appareil. */
    private val _sleepAt = MutableStateFlow<Map<String, Long>>(emptyMap())
    val sleepAt: StateFlow<Map<String, Long>> = _sleepAt

    @Synchronized
    fun driver(context: Context, device: Device): Driver {
        val existing = drivers[device.id]
        if (existing != null && existing.device.type == device.type && existing.device.host == device.host) return existing
        existing?.close()
        val store = DeviceStore(context)
        return Driver.create(context.applicationContext, device).also { d ->
            d.onDeviceUpdated = { store.save(it) }
            drivers[device.id] = d
        }
    }

    @Synchronized
    fun assistant(context: Context, device: Device): YouTubeAssistant {
        assistants[device.id]?.let { return it }
        val store = DeviceStore(context)
        val phoneId = "tel-" + (Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: device.id)
        return YouTubeAssistant(device, phoneId, scope) { _ -> store.save(device) }.also { assistants[device.id] = it }
    }

    @Synchronized
    fun activeAssistants(): Map<String, YouTubeAssistant> = assistants.filterValues { it.running }

    @Synchronized
    fun stopAssistant(id: String) { assistants.remove(id)?.stop() }

    /** Ferme la connexion d'un appareil si plus rien ne s'en sert. */
    @Synchronized
    fun release(id: String) {
        if (sleepJobs[id]?.isActive == true || TvMonitor.isWatching(id)) return
        drivers.remove(id)?.close()
    }

    fun startSleepTimer(context: Context, device: Device, minutes: Int) {
        cancelSleepTimer(device.id)
        val at = System.currentTimeMillis() + minutes * 60_000L
        _sleepAt.update { it + (device.id to at) }
        sleepJobs[device.id] = scope.launch {
            delay(minutes * 60_000L)
            val d = driver(context, device)
            runCatching {
                d.connect(SilentPairing)
                DiagLog.log("MINUTEUR", "Mise en veille programmée")
                if (d is fr.paul.telecommande.drivers.AndroidTvDriver) d.sleep() else d.send(RemoteKey.POWER)
            }
            _sleepAt.update { it - device.id }
            sleepJobs.remove(device.id)
        }
    }

    fun cancelSleepTimer(id: String) {
        sleepJobs.remove(id)?.cancel()
        _sleepAt.update { it - id }
    }

    fun hasBackgroundWork(): Boolean = activeAssistants().isNotEmpty() || _sleepAt.value.isNotEmpty() || TvMonitor.active() || BoxLink.active()

    fun androidTv(context: Context, device: Device): AndroidTvDriver? = driver(context, device) as? AndroidTvDriver

    /** Utilisé hors écran : aucun appairage interactif possible. */
    object SilentPairing : PairingUi {
        override suspend fun askCode(title: String, message: String, hint: String): String? = null
        override fun info(message: String) {}
    }
}
