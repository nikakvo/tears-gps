package io.github.jqssun.gpssetter.xposed

import android.content.SharedPreferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import timber.log.Timber

/**
 * App-side link to the Xposed framework (LSPosed) through the libxposed service.
 *
 * The hooks read their settings from the framework's remote preferences. The UI keeps its own
 * private SharedPreferences as the source of truth and mirrors the hook-relevant keys into the
 * remote group whenever they change, plus a full sync every time the service (re)binds.
 */
object ModuleBridge {

    /** Remote preferences group shared with the hooks. */
    const val PREFS_GROUP = "gpssetter"

    /** Keys the hooks read. */
    val HOOK_KEYS = setOf(
        "start", "latitude", "longitude", "system_hooked", "random_position", "accuracy_level"
    )

    @Volatile
    var service: XposedService? = null
        private set

    private val _active = MutableLiveData<Boolean>()
    /** true once the framework service is bound, false when it dies. */
    val active: LiveData<Boolean> = _active

    private var localPrefs: SharedPreferences? = null

    /** Call exactly once, from Application.onCreate(). */
    fun init(local: SharedPreferences) {
        localPrefs = local
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(bound: XposedService) {
                service = bound
                Timber.i("Xposed service bound")
                syncAll()
                _active.postValue(true)
            }

            override fun onServiceDied(dead: XposedService) {
                service = null
                _active.postValue(false)
            }
        })
    }

    private fun remote(): SharedPreferences? = try {
        service?.getRemotePreferences(PREFS_GROUP)
    } catch (t: Throwable) {
        Timber.e(t, "Remote preferences unavailable")
        null
    }

    /** Copy every hook-relevant value from the local prefs into the remote group. */
    fun syncAll() {
        val local = localPrefs ?: return
        val editor = remote()?.edit() ?: return
        for (key in HOOK_KEYS) {
            when (val v = local.all[key]) {
                null -> editor.remove(key)
                is Boolean -> editor.putBoolean(key, v)
                is Float -> editor.putFloat(key, v)
                is Int -> editor.putInt(key, v)
                is Long -> editor.putLong(key, v)
                is String -> editor.putString(key, v)
            }
        }
        editor.apply()
    }
}
