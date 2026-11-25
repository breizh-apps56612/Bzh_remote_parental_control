package fr.paul.telecommande.ui

import androidx.lifecycle.LifecycleCoroutineScope
import fr.paul.telecommande.core.RemoteKey
import fr.paul.telecommande.drivers.Driver
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Souris façon pavé tactile : les pas de curseur demandés par le doigt sont mis en file
 * et envoyés à la TV à un rythme régulier (au plus ~25 par seconde), en alternant
 * horizontal et vertical pour les diagonales. Jamais de touche maintenue : rien ne peut rester coincé.
 */
object MouseHold {
    /** Applis qui affichent un curseur piloté par les flèches. */
    val CURSOR_APPS = setOf(
        "com.esaba.downloader", "org.mozilla.tv.firefox", "com.cloudmosa.puffinTV", "com.tvwebbrowser.v22",
        "com.android.chrome", "org.chromium.chrome", "com.opera.browser.tv"
    )

    private const val MAX_PENDING = 10       // retard maximal du curseur sur le doigt
    private const val INTERVAL_MS = 40L

    private var pendingX = 0                  // >0 droite, <0 gauche
    private var pendingY = 0                  // >0 bas, <0 haut
    private var pump: Job? = null

    fun step(scope: LifecycleCoroutineScope, driver: Driver, g: TouchpadView.Gesture) {
        synchronized(this) {
            when (g) {
                TouchpadView.Gesture.RIGHT -> pendingX = (pendingX + 1).coerceAtMost(MAX_PENDING)
                TouchpadView.Gesture.LEFT -> pendingX = (pendingX - 1).coerceAtLeast(-MAX_PENDING)
                TouchpadView.Gesture.DOWN -> pendingY = (pendingY + 1).coerceAtMost(MAX_PENDING)
                TouchpadView.Gesture.UP -> pendingY = (pendingY - 1).coerceAtLeast(-MAX_PENDING)
                else -> return
            }
            if (pump?.isActive == true) return
            pump = scope.launch {
                var horizontal = true
                while (true) {
                    val key = synchronized(this@MouseHold) {
                        val useX = pendingX != 0 && (horizontal || pendingY == 0)
                        when {
                            useX -> { val k = if (pendingX > 0) RemoteKey.RIGHT else RemoteKey.LEFT; pendingX -= Integer.signum(pendingX); k }
                            pendingY != 0 -> { val k = if (pendingY > 0) RemoteKey.DOWN else RemoteKey.UP; pendingY -= Integer.signum(pendingY); k }
                            else -> null
                        }
                    } ?: break
                    horizontal = !horizontal
                    runCatching { driver.send(key) }
                    delay(INTERVAL_MS)
                }
            }
        }
    }

    /** Doigt levé : le curseur s'arrête presque tout de suite (on garde au plus 2 pas de retard). */
    fun release() = synchronized(this) {
        if (abs(pendingX) > 2) pendingX = 2 * Integer.signum(pendingX)
        if (abs(pendingY) > 2) pendingY = 2 * Integer.signum(pendingY)
    }

    fun stopAll() = synchronized(this) { pendingX = 0; pendingY = 0; pump?.cancel(); pump = null }
}
