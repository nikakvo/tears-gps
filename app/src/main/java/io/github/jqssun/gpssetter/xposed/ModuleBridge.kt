package io.github.jqssun.gpssetter.xposed

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
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
 *
 * The same keys also go into a small remote FILE ([LIVE_FILE]). Hooks inside app processes read
 * that file instead of the remote preferences: changes to the remote preferences are not always
 * delivered to an already running app process, which then keeps the values from its own start
 * (see LocationHook.live()).
 */
object ModuleBridge {

    /** Remote preferences group shared with the hooks. */
    const val PREFS_GROUP = "gpssetter"

    /** "key=value" lines, last line "end=1"; read by the hooks in app processes. */
    const val LIVE_FILE = "hookstate"

    /** Keys the hooks read. */
    val HOOK_KEYS = setOf(
        "start", "latitude", "longitude", "system_hooked", "random_position", "accuracy_level"
    )

    /** Position-only changes (route playback, joystick) rewrite the file at most this often. */
    private const val LIVE_POSITION_MS = 2000L
    @Volatile private var lastLive: String? = null
    @Volatile private var lastLiveFlags: String? = null
    @Volatile private var lastLiveAt = 0L
    private val liveHandler by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var trailingPosted = false

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

    /** Copy every hook-relevant value from the local prefs into the remote group and file. */
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
        writeLive(local)
    }

    private fun writeLive(local: SharedPreferences) {
        val svc = service ?: return
        val all = local.all
        fun value(k: String) = when (val v = all[k]) {
            null -> ""
            is Boolean -> if (v) "1" else "0"
            else -> v.toString()
        }
        val lines = HOOK_KEYS.sorted().map { "$it=${value(it)}" }
        val content = lines.joinToString("\n") + "\nend=1\n"
        if (content == lastLive) return
        val flags = lines.filterNot { it.startsWith("latitude=") || it.startsWith("longitude=") }.joinToString("\n")
        val now = SystemClock.elapsedRealtime()
        if (flags == lastLiveFlags && now - lastLiveAt < LIVE_POSITION_MS) {
            // Throttled: write once more after the window, so the position where movement
            // stopped (end of a route, joystick released) is not left behind.
            if (!trailingPosted) {
                trailingPosted = true
                liveHandler.postDelayed({
                    trailingPosted = false
                    localPrefs?.let { writeLive(it) }
                }, LIVE_POSITION_MS)
            }
            return
        }
        try {
            val pfd = svc.openRemoteFile(LIVE_FILE)
            ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { out ->
                out.channel.truncate(0)
                out.write(content.toByteArray())
                out.flush()
            }
            lastLive = content
            lastLiveFlags = flags
            lastLiveAt = now
        } catch (t: Throwable) {
            Timber.e(t, "Writing the live hook state failed")
        }
    }
}
