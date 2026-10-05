package io.github.jqssun.gpssetter.xposed

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.location.Location
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import io.github.jqssun.gpssetter.BuildConfig
import io.github.libxposed.api.XposedModule
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.Random
import kotlin.math.cos

/**
 * Location hooks, ported from the legacy XposedBridge API to libxposed API 102.
 *
 * Settings are written by the module UI and read live on every hooked call, so starting/stopping
 * or moving the point takes effect without a reboot. system_server reads the framework's remote
 * preferences (group ModuleBridge.PREFS_GROUP); app processes read the same keys from a small
 * remote file (ModuleBridge.LIVE_FILE), see [live].
 */
@SuppressLint("NewApi")
object LocationHook {

    const val TAG = "TearsGPS"

    val ignorePkg = setOf("com.android.location.fused", BuildConfig.APPLICATION_ID)

    private const val pi = 3.14159265359
    private const val earth = 6378137.0
    private const val APP_INTERVAL = 80L
    private const val SYSTEM_INTERVAL = 200L

    @Volatile private var newlat: Double = 45.0
    @Volatile private var newlng: Double = 0.0
    @Volatile private var accuracy: Float = 10.0f
    @Volatile private var mLastUpdated: Long = 0

    // Motion derived from how the base point moves (route playback / joystick), so apps get a
    // real speed + bearing and can animate smoothly instead of jumping between fixes.
    private const val STOP_AFTER_MS = 3000L
    private const val MAX_SPEED = 150f          // m/s, ignore teleports (manual point moves)
    private const val SMOOTH = 0.5f             // EMA weight of the newest sample
    @Volatile private var curSpeed: Float = 0f
    @Volatile private var curBearing: Float = 0f
    @Volatile private var moving = false
    private var lastBaseLat = Double.NaN
    private var lastBaseLng = Double.NaN
    private var lastMoveTime = 0L
    private val rand = Random()

    private lateinit var module: XposedModule
    private var prefs: SharedPreferences? = null

    private fun prefs(): SharedPreferences? {
        prefs?.let { return it }
        return try {
            module.getRemotePreferences(ModuleBridge.PREFS_GROUP).also { prefs = it }
        } catch (t: Throwable) {
            module.log(Log.ERROR, TAG, "remote preferences unavailable", t)
            null
        }
    }

    // ---- live settings outside system_server ------------------------------------------------
    // The framework delivers remote-preference CHANGES to system_server, but a running app
    // process may never receive them and keeps the values from its own start: a route seemed
    // frozen, or the fake point stayed after stopping, until the app was restarted. So app
    // processes re-read the same keys from a small remote file (written by the UI on every
    // change); openRemoteFile opens it fresh each time, cached for LIVE_MS. Missing or
    // unreadable file -> remote preferences as before.
    @Volatile private var inSystemServer = false
    private const val LIVE_MS = 3000L
    @Volatile private var liveAt = 0L
    @Volatile private var liveMap: Map<String, String>? = null

    private fun live(): Map<String, String>? {
        if (inSystemServer) return null // remote preferences are live there
        val now = SystemClock.elapsedRealtime()
        if (liveAt != 0L && now - liveAt < LIVE_MS) return liveMap
        liveAt = now
        liveMap = try {
            val text = ParcelFileDescriptor.AutoCloseInputStream(module.openRemoteFile(ModuleBridge.LIVE_FILE))
                .bufferedReader().use { it.readText() }
            text.lines().filter { '=' in it }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
                .takeIf { it["end"] == "1" } // only a completely written file
        } catch (_: Throwable) {
            null
        }
        return liveMap
    }

    // An empty value means the UI has never set the key: fall back like a missing key would.
    private fun bool(key: String, def: Boolean): Boolean =
        live()?.get(key)?.takeIf { it.isNotEmpty() }?.let { it == "1" } ?: prefs()?.getBoolean(key, def) ?: def

    private fun float(key: String, def: Float): Float =
        live()?.get(key)?.takeIf { it.isNotEmpty() }?.toFloatOrNull() ?: prefs()?.getFloat(key, def) ?: def

    private fun string(key: String, def: String): String =
        live()?.get(key)?.takeIf { it.isNotEmpty() } ?: prefs()?.getString(key, def) ?: def

    private val isStarted get() = bool("start", false)
    private val isHookedSystem get() = bool("system_hooked", true)
    private val isRandomPosition get() = bool("random_position", false)
    private val baseLat get() = float("latitude", 45.0f).toDouble()
    private val baseLng get() = float("longitude", 0.0f).toDouble()
    private val accuracySetting get() = string("accuracy_level", "10")

    private fun refresh(interval: Long) {
        if (System.currentTimeMillis() - mLastUpdated <= interval) return
        try {
            val now = System.currentTimeMillis()
            mLastUpdated = now
            val lat = baseLat
            val lng = baseLng
            updateMotion(lat, lng, now)
            if (isRandomPosition) {
                val x = (rand.nextInt(50) - 15).toDouble()
                val y = (rand.nextInt(50) - 15).toDouble()
                val dlat = x / earth
                val dlng = y / (earth * cos(pi * lat / 180.0))
                newlat = lat + (dlat * 180.0 / pi)
                newlng = lng + (dlng * 180.0 / pi)
            } else {
                newlat = lat
                newlng = lng
            }
            accuracy = accuracySetting.toFloat()
        } catch (e: Exception) {
            module.log(Log.ERROR, TAG, "failed to read settings", e)
        }
    }

    /**
     * Speed/bearing from successive base positions. A "no change" sample does not reset the
     * clock, so the next change is measured over the whole interval (correct average even when
     * playback ticks and location reports are not in sync); EMA smooths the rest.
     */
    @Synchronized
    private fun updateMotion(lat: Double, lng: Double, now: Long) {
        if (lastBaseLat.isNaN()) {
            lastBaseLat = lat; lastBaseLng = lng; lastMoveTime = now
            return
        }
        if (lat != lastBaseLat || lng != lastBaseLng) {
            val dt = now - lastMoveTime
            val r = FloatArray(2)
            Location.distanceBetween(lastBaseLat, lastBaseLng, lat, lng, r)
            val sample = if (dt > 0) r[0] / (dt / 1000f) else Float.MAX_VALUE
            if (dt in 1L..10_000L && sample <= MAX_SPEED) {
                curSpeed = if (moving) curSpeed + SMOOTH * (sample - curSpeed) else sample
                if (r[0] > 0.5f) curBearing = (r[1] + 360f) % 360f
                moving = true
            } else {
                // Teleport (map tap, search) or a long pause: start fresh, no fake velocity.
                curSpeed = 0f
                moving = false
            }
            lastBaseLat = lat; lastBaseLng = lng; lastMoveTime = now
        } else if (moving && now - lastMoveTime > STOP_AFTER_MS) {
            curSpeed = 0f
            moving = false
        }
    }

    /** Speed + bearing like a real receiver: bearing only while moving. */
    private fun applyMotion(loc: Location) {
        loc.speed = curSpeed
        loc.speedAccuracyMetersPerSecond = if (moving) 0.5f else 0F
        if (moving && curSpeed > 0.3f) {
            loc.bearing = curBearing
            loc.bearingAccuracyDegrees = 10f
        } else {
            loc.removeBearing()
        }
    }

    private fun clearMockFlag(location: Location) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                location.isMock = false
            } else {
                HiddenApiBypass.invoke(location.javaClass, location, "setIsFromMockProvider", false)
            }
        } catch (e: Exception) {
            module.log(Log.WARN, TAG, "unable to clear mock flag: $e")
        }
    }

    /** A fresh fake fix, used where the original code returned a brand-new Location. */
    private fun fakeLocation(provider: String?): Location =
        Location(provider ?: LocationManager.GPS_PROVIDER).apply {
            time = System.currentTimeMillis() - 300
            latitude = newlat
            longitude = newlng
            altitude = 0.0
            accuracy = this@LocationHook.accuracy
        }.also { applyMotion(it) }

    /** A fake fix that keeps timing/bearing data from the real one (null -> fresh fix). */
    private fun fakeFrom(origin: Location?): Location {
        if (origin == null) return fakeLocation(LocationManager.GPS_PROVIDER)
        return Location(origin.provider).apply {
            time = origin.time
            accuracy = this@LocationHook.accuracy
            elapsedRealtimeNanos = origin.elapsedRealtimeNanos
            verticalAccuracyMeters = origin.verticalAccuracyMeters
            latitude = newlat
            longitude = newlng
            altitude = 0.0
        }.also { applyMotion(it) }
    }

    /** Overwrite a Location in place with the fake position (keeps time/bearing data). */
    private fun spoofInPlace(loc: Location) {
        loc.latitude = newlat
        loc.longitude = newlng
        loc.altitude = 0.0
        loc.accuracy = accuracy
        applyMotion(loc)
        clearMockFlag(loc)
    }

    @Volatile private var reportLogged = false

    // ---------------------------------------------------------------- system_server

    fun hookSystemServer(module: XposedModule, cl: ClassLoader) {
        this.module = module
        inSystemServer = true
        module.log(Log.INFO, TAG, "hooking system_server")

        // Hooks are always installed; whether they act is decided per call from the live
        // settings ("start" + "system_hooked").
        fun active(): Boolean {
            if (!(isStarted && isHookedSystem)) return false
            refresh(SYSTEM_INTERVAL)
            return true
        }

        if (Build.VERSION.SDK_INT < 34) {
            val lms = Class.forName("com.android.server.LocationManagerService", false, cl)

            module.hook(
                lms.getDeclaredMethod("getLastLocation", LocationRequest::class.java, String::class.java)
            ).intercept { chain ->
                if (active()) fakeLocation(LocationManager.GPS_PROVIDER) else chain.proceed()
            }

            for (method in lms.declaredMethods) {
                if (method.returnType == Boolean::class.javaPrimitiveType &&
                    method.name in setOf(
                        "addGnssBatchingCallback",
                        "addGnssMeasurementsListener",
                        "addGnssNavigationMessageListener"
                    )
                ) {
                    module.hook(method).intercept { chain ->
                        if (active()) false else chain.proceed()
                    }
                }
            }

            val receiver = Class.forName("com.android.server.LocationManagerService\$Receiver", false, cl)
            module.hook(
                receiver.getDeclaredMethod("callLocationChangedLocked", Location::class.java)
            ).intercept { chain ->
                if (!active()) return@intercept chain.proceed()
                val loc = fakeFrom(chain.getArgs()[0] as Location?)
                clearMockFlag(loc)
                chain.proceed(arrayOf<Any?>(loc))
            }
        } else {
            val lms = Class.forName("com.android.server.location.LocationManagerService", false, cl)

            for (method in lms.declaredMethods) {
                if (method.name == "getLastLocation" && method.returnType == Location::class.java) {
                    module.hook(method).intercept { chain ->
                        if (active()) fakeLocation(LocationManager.GPS_PROVIDER) else chain.proceed()
                    }
                } else if (method.returnType == Void.TYPE &&
                    method.name in setOf(
                        "startGnssBatch",
                        "addGnssAntennaInfoListener",
                        "addGnssMeasurementsListener",
                        "addGnssNavigationMessageListener"
                    )
                ) {
                    // Raw GNSS data would reveal the real position: swallow these
                    // registrations while spoofing.
                    module.hook(method).intercept { chain ->
                        if (active()) null else chain.proceed()
                    }
                }
            }

            module.hook(
                lms.getDeclaredMethod("injectLocation", Location::class.java)
            ).intercept { chain ->
                if (!active()) return@intercept chain.proceed()
                val loc = fakeFrom(chain.getArgs()[0] as Location?)
                clearMockFlag(loc)
                chain.proceed(arrayOf<Any?>(loc))
            }

            hookProviderManager(cl, ::active)
        }
    }

    /**
     * Android 14+: every location fix from every provider (gps, network, fused, passive)
     * passes through LocationProviderManager.onReportLocation(LocationResult) before it is
     * cached and delivered to listeners in all apps. Spoofing it there makes the system
     * hook cover live updates system-wide, not only "last known location".
     */
    private fun hookProviderManager(cl: ClassLoader, active: () -> Boolean) {
        try {
            val lpm = Class.forName(
                "com.android.server.location.provider.LocationProviderManager", false, cl
            )
            val lrClass = Class.forName("android.location.LocationResult", false, cl)
            val lrSize = lrClass.getMethod("size")
            val lrGet = lrClass.getMethod("get", Int::class.javaPrimitiveType)
            var hooked = 0

            for (m in lpm.declaredMethods) {
                if (m.name == "onReportLocation" &&
                    m.parameterTypes.size == 1 && m.parameterTypes[0] == lrClass
                ) {
                    module.hook(m).intercept { chain ->
                        val result = chain.getArgs()[0]
                        if (result != null && active()) {
                            val n = lrSize.invoke(result) as Int
                            for (i in 0 until n) spoofInPlace(lrGet.invoke(result, i) as Location)
                            if (!reportLogged) {
                                reportLogged = true
                                module.log(Log.INFO, TAG, "spoofing provider reports ($n fix(es) in first batch)")
                            }
                        }
                        chain.proceed()
                    }
                    hooked++
                } else if (m.name == "getLastLocationUnsafe" && m.returnType == Location::class.java) {
                    // Cached fixes taken before spoofing started must not leak.
                    module.hook(m).intercept { chain ->
                        val real = chain.proceed() as Location?
                        if (real != null && active()) fakeFrom(real).also { clearMockFlag(it) } else real
                    }
                    hooked++
                }
            }
            module.log(Log.INFO, TAG, "LocationProviderManager: $hooked hook(s)")
        } catch (t: Throwable) {
            module.log(Log.ERROR, TAG, "LocationProviderManager hooks failed", t)
        }
    }

    // ---------------------------------------------------------------- apps

    fun hookApp(module: XposedModule, packageName: String) {
        this.module = module

        fun active(): Boolean {
            refresh(APP_INTERVAL)
            return isStarted
        }

        val locationClass = Location::class.java

        module.hook(locationClass.getDeclaredMethod("getLatitude")).intercept { chain ->
            if (active()) newlat else chain.proceed()
        }
        module.hook(locationClass.getDeclaredMethod("getLongitude")).intercept { chain ->
            if (active()) newlng else chain.proceed()
        }
        module.hook(locationClass.getDeclaredMethod("getAccuracy")).intercept { chain ->
            if (active()) accuracy else chain.proceed()
        }

        module.hook(locationClass.getDeclaredMethod("set", Location::class.java)).intercept { chain ->
            if (!active()) return@intercept chain.proceed()
            val loc = fakeFrom(chain.getArgs()[0] as Location?)
            clearMockFlag(loc)
            chain.proceed(arrayOf<Any?>(loc))
        }

        module.hook(
            LocationManager::class.java.getDeclaredMethod("getLastKnownLocation", String::class.java)
        ).intercept { chain ->
            if (!active()) return@intercept chain.proceed()
            val loc = fakeLocation(chain.getArgs()[0] as String?)
            clearMockFlag(loc)
            loc
        }

        module.log(Log.INFO, TAG, "hooked $packageName")
    }
}
