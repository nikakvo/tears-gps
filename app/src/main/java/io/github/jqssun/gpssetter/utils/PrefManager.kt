package io.github.jqssun.gpssetter.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import io.github.jqssun.gpssetter.BuildConfig
import io.github.jqssun.gpssetter.gsApp
import io.github.jqssun.gpssetter.xposed.ModuleBridge
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch


object PrefManager   {

    private const val START = "start"
    private const val LATITUDE = "latitude"
    private const val LONGITUDE = "longitude"
    private const val HOOKED_SYSTEM = "system_hooked"
    private const val RANDOM_POSITION = "random_position"
    private const val ACCURACY_SETTING = "accuracy_level"
    private const val MAP_TYPE = "map_type"
    private const val DARK_THEME = "dark_theme"
    private const val DISABLE_UPDATE = "update_disabled"
    private const val ENABLE_JOYSTICK = "joystick_enabled"
    private const val JOYSTICK_SPEED = "joystick_speed"


    // Private storage for the UI. The hooks no longer read this file (that needed
    // MODE_WORLD_READABLE + New XSharedPreferences, deprecated in LSPosed); hook-relevant
    // keys are mirrored to the framework's remote preferences by ModuleBridge.
    val pref: SharedPreferences by lazy {
        gsApp.getSharedPreferences("${BuildConfig.APPLICATION_ID}_prefs", Context.MODE_PRIVATE)
            .also { it.registerOnSharedPreferenceChangeListener(mirror) }
    }

    // Kept as a field: SharedPreferences only holds listeners weakly.
    private val mirror = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key in ModuleBridge.HOOK_KEYS) ModuleBridge.syncAll()
    }


    val isStarted : Boolean
        get() = pref.getBoolean(START, false)

    val getLat : Double
        get() = pref.getFloat(LATITUDE, 40.7128F).toDouble()

    val getLng : Double
        get() = pref.getFloat(LONGITUDE, -74.0060F).toDouble()

    var isSystemHooked : Boolean
        get() = pref.getBoolean(HOOKED_SYSTEM, false)
        set(value) { pref.edit().putBoolean(HOOKED_SYSTEM,value).apply() }

    var isRandomPosition :Boolean
        get() = pref.getBoolean(RANDOM_POSITION, false)
        set(value) { pref.edit().putBoolean(RANDOM_POSITION, value).apply() }

    var accuracy : String?
        get() = pref.getString(ACCURACY_SETTING,"10")
        set(value) { pref.edit().putString(ACCURACY_SETTING,value).apply()}

    var mapType : Int
        get() = pref.getInt(MAP_TYPE,1)
        set(value) { pref.edit().putInt(MAP_TYPE,value).apply()}

    var darkTheme: Int
        get() = pref.getInt(DARK_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        set(value) = pref.edit().putInt(DARK_THEME, value).apply()

    var isUpdateDisabled: Boolean
        get() = pref.getBoolean(DISABLE_UPDATE, false)
        set(value) = pref.edit().putBoolean(DISABLE_UPDATE, value).apply()

    var isJoystickEnabled: Boolean
        get() = pref.getBoolean(ENABLE_JOYSTICK, false)
        set(value) = pref.edit().putBoolean(ENABLE_JOYSTICK, value).apply()

    /** Joystick speed in m/s at full deflection (stored as a string by the settings dropdown). */
    var joystickSpeedSetting: String
        get() = pref.getString(JOYSTICK_SPEED, "1.4") ?: "1.4"
        set(value) = pref.edit().putString(JOYSTICK_SPEED, value).apply()

    val joystickSpeed: Double
        get() = joystickSpeedSetting.toDoubleOrNull() ?: 1.4

    // Running route playback, so it can resume after the process was killed.
    // startWall = wall-clock time (ms) of the route's t = 0.
    data class PlaybackState(val routeId: String, val startWall: Long, val stayMs: Long, val loop: Boolean)

    var playbackState: PlaybackState?
        get() {
            val id = pref.getString("pb_route", null) ?: return null
            return PlaybackState(id, pref.getLong("pb_start", 0L), pref.getLong("pb_stay", -1L),
                pref.getBoolean("pb_loop", false))
        }
        set(value) {
            val e = pref.edit()
            if (value == null) e.remove("pb_route").remove("pb_start").remove("pb_stay").remove("pb_loop")
            else e.putString("pb_route", value.routeId).putLong("pb_start", value.startWall)
                .putLong("pb_stay", value.stayMs).putBoolean("pb_loop", value.loop)
            e.commit() // must survive an immediate kill
        }

    /** Move the point without touching the start flag (used by the joystick). */
    fun setPosition(la: Double, ln: Double) {
        pref.edit()
            .putFloat(LATITUDE, la.toFloat())
            .putFloat(LONGITUDE, ln.toFloat())
            .apply()
    }

    fun update(start:Boolean, la: Double, ln: Double) {
        runInBackground {
            val prefEditor = pref.edit()
            prefEditor.putFloat(LATITUDE, la.toFloat())
            prefEditor.putFloat(LONGITUDE, ln.toFloat())
            prefEditor.putBoolean(START, start)
            prefEditor.apply()
        }

    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun runInBackground(method: suspend () -> Unit){
        GlobalScope.launch(Dispatchers.IO) {
            method.invoke()
        }
    }

}