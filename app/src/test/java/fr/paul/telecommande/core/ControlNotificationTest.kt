package fr.paul.telecommande.core

import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import fr.paul.telecommande.service.ControlNotification
import fr.paul.telecommande.service.ControlReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ControlNotificationTest {
    private val c = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val nm get() = c.getSystemService(NotificationManager::class.java)

    @Test fun showsFiveActionsAndCloses() {
        shadowOf(c).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val d = Device(name = "Salon", type = DeviceType.ANDROID_TV, host = "192.168.1.45")
        DeviceStore(c).save(d)
        ControlNotification.show(c, d.id)
        val n = shadowOf(nm).allNotifications.single()
        assertEquals(listOf("Lecture / pause", "Volume −", "Volume +", "Muet", "Fermer"), n.actions.map { it.title.toString() })
        assertEquals("Salon", n.extras.getString("android.title"))
        ControlReceiver().onReceive(c, Intent(ControlNotification.ACTION).putExtra("key", "close"))
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
    }

    @Test fun disabledShowsNothing() {
        val d = Device(name = "Salon", type = DeviceType.ANDROID_TV, host = "192.168.1.45")
        DeviceStore(c).save(d)
        ControlNotification.setEnabled(c, false)
        ControlNotification.show(c, d.id)
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
        ControlNotification.setEnabled(c, true)
    }
}
