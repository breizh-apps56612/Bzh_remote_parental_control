package fr.paul.telecommande

import android.app.Activity
import android.app.Application
import android.os.Bundle
import fr.paul.telecommande.core.DiagLog

class App : Application() {
    companion object {
        /** Une page de l'appli est à l'écran (pour ouvrir directement la validation du code parent). */
        @Volatile var foreground = false
            private set
        private var started = 0
    }

    override fun onCreate() {
        super.onCreate()
        DiagLog.init(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) { started++; foreground = true }
            override fun onActivityStopped(a: Activity) { started = maxOf(0, started - 1); foreground = started > 0 }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
}
