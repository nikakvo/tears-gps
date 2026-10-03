package io.github.jqssun.gpssetter.utils

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import io.github.jqssun.gpssetter.R

/**
 * Replays a recorded route with its original timing (pauses included), optionally in a loop.
 * Foreground service (specialUse) so it keeps running while other apps are in front.
 */
class RoutePlaybackService : Service() {

    companion object {
        @Volatile
        var isRunning = false
            private set

        const val EXTRA_ROUTE_ID = "route_id"
        const val EXTRA_LOOP = "loop"
        /** Round trip: stay this long at the destination, then come back. Absent/negative = off. */
        const val EXTRA_STAY_MS = "stay_ms"
        private const val ACTION_STOP = "io.github.nikakvo.tearsgps.PLAYBACK_STOP"
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 125
        private const val TICK_MS = 200L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var route: Route? = null
    private var loop = false

    // The timeline actually played: outward, or outward + stay + return for a round trip.
    private var timeline: List<RoutePoint> = emptyList()
    private var outEnd = 0L      // end of the outward leg
    private var stayEnd = -1L    // end of the stay (round trip only)
    private var startWall = 0L
    private var lastNotification = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    /** Keep the CPU awake while a route plays, so it moves smoothly with the screen off too. */
    private fun holdWakeLock(ms: Long) {
        val lock = wakeLock ?: (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TearsGPS:routePlayback")
            .also { it.setReferenceCounted(false); wakeLock = it }
        lock.acquire(ms)
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always enter the foreground first (required within a few seconds of starting).
        startForegroundCompat(buildNotification(null, 0L))

        if (intent?.action == ACTION_STOP) {
            PrefManager.playbackState = null
            stopSelf()
            return START_NOT_STICKY
        }

        val newId = intent?.getStringExtra(EXTRA_ROUTE_ID)
        val state = if (newId != null) {
            // New playback requested from the UI.
            PrefManager.PlaybackState(
                newId, System.currentTimeMillis(),
                intent.getLongExtra(EXTRA_STAY_MS, -1L),
                intent.getBooleanExtra(EXTRA_LOOP, false)
            ).also { PrefManager.playbackState = it }
        } else {
            // Restarted by the system (sticky) or by App.onCreate: continue where it should be now.
            PrefManager.playbackState
        }
        if (state == null || !start(state, fresh = newId != null)) {
            PrefManager.playbackState = null
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    /** Builds the timeline and starts ticking. Returns false when there is nothing to play. */
    private fun start(state: PrefManager.PlaybackState, fresh: Boolean): Boolean {
        val loaded = RouteStore.load(this, state.routeId) ?: return false
        if (loaded.points.size < 2 || loaded.durationMs <= 0) return false
        route = loaded
        loop = state.loop && state.stayMs < 0
        outEnd = loaded.durationMs
        if (state.stayMs >= 0) {
            stayEnd = outEnd + state.stayMs
            val dest = loaded.points.last()
            timeline = loaded.points +
                RoutePoint(dest.lat, dest.lon, stayEnd) +
                loaded.returnPoints.map { it.copy(t = it.t + stayEnd) }
        } else {
            stayEnd = -1L
            timeline = loaded.points
        }
        startWall = state.startWall
        val total = timeline.last().t

        if (fresh) {
            val first = loaded.points.first()
            if (!PrefManager.isStarted) PrefManager.update(start = true, la = first.lat, ln = first.lon)
            PrefManager.setPosition(first.lat, first.lon)
        } else if (!loop && System.currentTimeMillis() - startWall >= total) {
            // Finished while the app was dead: just put the point at the end.
            val last = timeline.last()
            PrefManager.setPosition(last.lat, last.lon)
            return false
        }
        holdWakeLock(total + 60_000L)
        handler.removeCallbacks(tick)
        handler.post(tick)
        return true
    }

    private val tick = object : Runnable {
        override fun run() {
            val r = route ?: return
            val total = timeline.last().t
            val now = System.currentTimeMillis()
            var elapsed = now - startWall
            if (elapsed >= total) {
                if (loop) {
                    // Also covers laps missed while the process was dead.
                    val laps = elapsed / total
                    startWall += laps * total
                    elapsed -= laps * total
                    PrefManager.playbackState = PrefManager.playbackState?.copy(startWall = startWall)
                    holdWakeLock(total + 60_000L)
                } else {
                    val last = timeline.last()
                    PrefManager.setPosition(last.lat, last.lon)
                    PrefManager.playbackState = null
                    stopSelf()
                    return
                }
            }
            val (lat, lon) = positionAt(timeline, elapsed)
            PrefManager.setPosition(lat, lon)

            if (now - lastNotification >= 1000) {
                lastNotification = now
                startForegroundCompat(buildNotification(r, elapsed))
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    /** Linear interpolation between the two samples around time [t]. */
    private fun positionAt(points: List<RoutePoint>, t: Long): Pair<Double, Double> {
        var lo = 0
        var hi = points.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (points[mid].t <= t) lo = mid else hi = mid
        }
        val a = points[lo]
        val b = points[hi]
        val span = b.t - a.t
        if (span <= 0) return b.lat to b.lon
        val f = ((t - a.t).toDouble() / span).coerceIn(0.0, 1.0)
        return (a.lat + (b.lat - a.lat) * f) to (a.lon + (b.lon - a.lon) * f)
    }

    private fun buildNotification(r: Route?, elapsed: Long): Notification {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.route_channel))
                .build()
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, RoutePlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = when {
            r == null -> ""
            stayEnd < 0 ->
                "${RouteStore.formatDuration(elapsed)} / ${RouteStore.formatDuration(r.durationMs)}" +
                    if (loop) " · " + getString(R.string.route_loop) else ""
            elapsed < outEnd -> getString(R.string.route_phase_out,
                RouteStore.formatDuration(elapsed), RouteStore.formatDuration(outEnd))
            elapsed < stayEnd -> getString(R.string.route_phase_stay,
                RouteStore.formatDuration(stayEnd - elapsed))
            else -> getString(R.string.route_phase_back,
                RouteStore.formatDuration(elapsed - stayEnd),
                RouteStore.formatDuration(timeline.last().t - stayEnd))
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle(getString(R.string.route_playing, r?.name ?: ""))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, getString(R.string.joystick_stop), stop)
            .setContentIntent(openApp())
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
    }

    /** Tapping the notification opens Tears GPS (resumes the existing task). */
    private fun openApp(): PendingIntent? =
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(
                this, 1, it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        wakeLock?.let { if (it.isHeld) it.release() }
        isRunning = false
        super.onDestroy()
    }
}
