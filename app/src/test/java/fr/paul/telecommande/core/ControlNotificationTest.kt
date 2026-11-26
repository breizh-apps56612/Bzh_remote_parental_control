package fr.paul.telecommande.core

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import fr.paul.telecommande.service.ControlNotification
import fr.paul.telecommande.service.TvControlService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ControlNotificationTest {
    private val c = ApplicationProvider.getApplicationContext<android.app.Application>()

    /** Lecteur des réglages rapides : notification multimédia liée à une session, 5 boutons, titre = la TV. */
    @Test fun mediaPlayerWithSession() {
        val d = Device(name = "Salon", type = DeviceType.ANDROID_TV, host = "192.168.1.45")
        DeviceStore(c).save(d)
        val svc = Robolectric.buildService(TvControlService::class.java, Intent(c, TvControlService::class.java).putExtra("id", d.id))
            .create().startCommand(0, 1).get()
        val n = shadowOf(svc).lastForegroundNotification
        assertNotNull(n)
        assertEquals(listOf("−10 s", "Lecture / pause", "+10 s", "Muet", "Fermer"), n!!.actions.map { it.title.toString() })
        assertNotNull("session multimédia liée", n.extras.getParcelable<android.os.Parcelable>("android.mediaSession"))
        assertEquals("Salon", n.extras.getString("android.title"))
        svc.onDestroy()
    }

    /** −10 s / +10 s passent par « précédent / suivant » (actions standard), Muet et Télécommande en actions perso. */
    @Test fun quickSettingsButtons() {
        val d = Device(name = "Salon", type = DeviceType.ANDROID_TV, host = "192.168.1.45")
        DeviceStore(c).save(d)
        val svc = Robolectric.buildService(TvControlService::class.java, Intent(c, TvControlService::class.java).putExtra("id", d.id))
            .create().startCommand(0, 1).get()
        val session = TvControlService::class.java.getDeclaredField("session").apply { isAccessible = true }.get(svc)
            as android.support.v4.media.session.MediaSessionCompat
        val st = session.controller.playbackState!!
        assertEquals(listOf("mute", "remote"), st.customActions.map { it.action })
        val skip = android.support.v4.media.session.PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or android.support.v4.media.session.PlaybackStateCompat.ACTION_SKIP_TO_NEXT
        assertEquals(skip, st.actions and skip)
        svc.onDestroy()
    }

    @Test fun disabledStartsNothing() {
        val d = Device(name = "Salon", type = DeviceType.ANDROID_TV, host = "192.168.1.45")
        DeviceStore(c).save(d)
        ControlNotification.setEnabled(c, false)
        ControlNotification.show(c, d.id)
        assertNull(shadowOf(c).nextStartedService)
        ControlNotification.setEnabled(c, true)
    }
}
