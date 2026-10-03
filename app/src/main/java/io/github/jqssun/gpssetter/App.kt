package io.github.jqssun.gpssetter

import androidx.appcompat.app.AppCompatDelegate
import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.github.jqssun.gpssetter.utils.PrefManager
import io.github.jqssun.gpssetter.xposed.ModuleBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import timber.log.Timber

lateinit var gsApp: App

@HiltAndroidApp
class App : Application() {
    val globalScope = CoroutineScope(Dispatchers.Default)

    companion object {
        fun commonInit() {
            if (BuildConfig.DEBUG) {
                Timber.plant(Timber.DebugTree())
            }
        }
    }

    /**
     * The process came back (app opened, sticky restart, autostart): bring back a route that was
     * playing and the joystick if it was on. Android may refuse a foreground start from the
     * background; then the sticky restart of the service itself takes over.
     */
    private fun resumeServices() {
        fun startFg(cls: Class<*>) = try {
            androidx.core.content.ContextCompat.startForegroundService(this, android.content.Intent(this, cls))
        } catch (e: Exception) {
            Timber.w(e, "Could not resume %s", cls.simpleName)
        }
        if (PrefManager.playbackState != null && !io.github.jqssun.gpssetter.utils.RoutePlaybackService.isRunning) {
            startFg(io.github.jqssun.gpssetter.utils.RoutePlaybackService::class.java)
        }
        if (PrefManager.isJoystickEnabled && PrefManager.isStarted &&
            android.provider.Settings.canDrawOverlays(this) &&
            !io.github.jqssun.gpssetter.utils.JoystickService.isRunning) {
            startFg(io.github.jqssun.gpssetter.utils.JoystickService::class.java)
        }
    }

    override fun onCreate() {
        super.onCreate()
        gsApp = this
        commonInit()
        ModuleBridge.init(PrefManager.pref)
        resumeServices()
        AppCompatDelegate.setDefaultNightMode(PrefManager.darkTheme)
    }
}