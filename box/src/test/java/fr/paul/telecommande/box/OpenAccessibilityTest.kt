package fr.paul.telecommande.box

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Cas Freebox : l'accès standard à l'accessibilité est redirigé vers les Paramètres généraux.
 * Le bouton « Activer » doit quand même ouvrir l'écran Accessibilité de l'appli Paramètres.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class OpenAccessibilityTest {
    @Test fun activateOpensAccessibilityScreenEvenWhenRedirected() {
        val a = Robolectric.buildActivity(SetupActivity::class.java).setup().get()
        val spm = shadowOf(a.packageManager)
        val main = ComponentName("com.android.tv.settings", "com.android.tv.settings.MainSettings")
        val acc = ComponentName("com.android.tv.settings", "com.android.tv.settings.accessibility.AccessibilityActivity")
        spm.addActivityIfNotPresent(main)
        spm.addActivityIfNotPresent(acc)
        // l'action standard aboutit aux Paramètres généraux (comme sur la Freebox)
        spm.addIntentFilterForActivity(main, IntentFilter(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply { addCategory(Intent.CATEGORY_DEFAULT) })
        spm.getInternalMutablePackageInfo("com.android.tv.settings").activities.forEach { it.exported = true }

        a.javaClass.getDeclaredMethod("openAccessibility").apply { isAccessible = true }.invoke(a)
        val started = shadowOf(a).nextStartedActivity
        assertEquals(acc, started.component)
    }
}
