package io.github.jqssun.gpssetter.utils

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import io.github.controlwear.virtual.joystick.android.JoystickView
import io.github.jqssun.gpssetter.R
import kotlin.math.cos
import kotlin.math.sin

/**
 * Floating joystick that "walks" the simulated location.
 *
 * - Foreground service (type specialUse) with a notification, so Android/HyperOS does not stop it
 *   when the user switches to the app being spoofed.
 * - Movement is speed-based (m/s from the "joystick_speed" setting, scaled by how far the stick is
 *   pushed) and integrated over real elapsed time, so it is smooth and independent of callback rate.
 * - The position is kept as Double in memory; the Float values in the prefs (what the hooks read)
 *   are only written out, never read back between steps, so small steps are not lost to rounding.
 */
class JoystickService : Service() {

    companion object {
        /** True while the joystick overlay service is alive in this process. */
        @Volatile
        var isRunning = false
            private set

        /** True while a route is being recorded from the joystick. */
        @Volatile
        var isRecording = false
            private set

        private const val CHANNEL_ID = "joystick"
        private const val NOTIFICATION_ID = 124
        private const val ACTION_STOP = "io.github.nikakvo.tearsgps.JOYSTICK_STOP"
        private const val ACTION_RECORD = "io.github.nikakvo.tearsgps.JOYSTICK_RECORD"
        private const val ACTION_SAVE = "io.github.nikakvo.tearsgps.JOYSTICK_SAVE"
        private const val ACTION_DISCARD = "io.github.nikakvo.tearsgps.JOYSTICK_DISCARD"
        private const val MIN_ROUTE_METERS = 5.0
        private const val METERS_PER_DEGREE = 111_320.0
        private const val MAX_STEP_SECONDS = 0.25
        private const val WRITE_INTERVAL_MS = 150L
    }

    private var wm: WindowManager? = null
    private var overlay: View? = null

    private var curLat = 0.0
    private var curLon = 0.0
    private var lastTick = 0L
    private var lastWrite = 0L
    private var dirty = false

    private val recorded = ArrayList<RoutePoint>()
    private var recordStart = 0L

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startInForeground()

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        curLat = PrefManager.getLat
        curLon = PrefManager.getLng
        addOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                if (isRecording) saveRecording()
                PrefManager.isJoystickEnabled = false
                stopSelf()
            }
            ACTION_RECORD -> startRecording()
            ACTION_SAVE -> saveRecording()
            ACTION_DISCARD -> {
                isRecording = false
                recorded.clear()
                updateNotification()
            }
        }
        // Restarted by the system after a kill if the joystick is still switched on.
        return if (PrefManager.isJoystickEnabled) START_STICKY else START_NOT_STICKY
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun addOverlay() {
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = LayoutInflater.from(this).inflate(R.layout.joystick, null)
        val joystick = view.findViewById<JoystickView>(R.id.joystickView_right)

        joystick.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Start from the current point (it may have been moved on the map meanwhile).
                    curLat = PrefManager.getLat
                    curLon = PrefManager.getLng
                    lastTick = 0L
                    recordPoint() // a pause before this stroke is kept as a pause
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    lastTick = 0L
                    flush()
                    recordPoint()
                }
            }
            false // let JoystickView handle the gesture itself
        }
        joystick.setOnMoveListener { angle, strength, _ -> onMove(angle, strength) }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }

        manager.addView(view, params)
        wm = manager
        overlay = view
    }

    private fun onMove(angle: Int, strength: Int) {
        // A route being replayed owns the position; the stick is ignored meanwhile.
        if (RoutePlaybackService.isRunning) return
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastTick == 0L) 0.05 else ((now - lastTick) / 1000.0).coerceAtMost(MAX_STEP_SECONDS)
        lastTick = now
        if (strength <= 0) return

        // JoystickView angle: 0 = east, 90 = north (counter-clockwise).
        val meters = PrefManager.joystickSpeed * (strength / 100.0) * dt
        val rad = Math.toRadians(angle.toDouble())
        curLat += meters * sin(rad) / METERS_PER_DEGREE
        curLon += meters * cos(rad) / (METERS_PER_DEGREE * cos(Math.toRadians(curLat)))
        dirty = true

        if (now - lastWrite >= WRITE_INTERVAL_MS) flush()
    }

    private fun flush() {
        if (!dirty) return
        dirty = false
        lastWrite = SystemClock.elapsedRealtime()
        PrefManager.setPosition(curLat, curLon)
        recordPoint()
    }

    private fun recordPoint() {
        if (!isRecording) return
        recorded.add(RoutePoint(curLat, curLon, SystemClock.elapsedRealtime() - recordStart))
    }

    private fun startRecording() {
        if (isRecording) return
        curLat = PrefManager.getLat
        curLon = PrefManager.getLng
        recorded.clear()
        recordStart = SystemClock.elapsedRealtime()
        isRecording = true
        recordPoint()
        updateNotification()
    }

    private fun saveRecording() {
        if (!isRecording) return
        flush()
        recordPoint()
        isRecording = false
        val name = getString(R.string.route_default_name,
            java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.ROOT).format(java.util.Date()))
        val candidate = Route("", name, ArrayList(recorded))
        if (recorded.size >= 2 && candidate.distanceMeters >= MIN_ROUTE_METERS) {
            RouteStore.save(this, name, candidate.points)
            Toast.makeText(applicationContext, getString(R.string.route_saved, name), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(applicationContext, R.string.route_too_short, Toast.LENGTH_SHORT).show()
        }
        recorded.clear()
        updateNotification()
    }

    private fun action(action: String): PendingIntent = PendingIntent.getService(
        this, action.hashCode(),
        Intent(this, JoystickService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildNotification(): Notification {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.joystick_channel))
                .build()
        )
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_joystick)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openApp())
        if (isRecording) {
            b.setContentTitle(getString(R.string.joystick_recording_title))
                .setContentText(getString(R.string.joystick_recording_text))
                .addAction(0, getString(R.string.joystick_save), action(ACTION_SAVE))
                .addAction(0, getString(R.string.joystick_discard), action(ACTION_DISCARD))
        } else {
            b.setContentTitle(getString(R.string.joystick_notification_title))
                .setContentText(getString(R.string.joystick_notification_text))
                .addAction(0, getString(R.string.joystick_record), action(ACTION_RECORD))
        }
        b.addAction(0, getString(R.string.joystick_stop), action(ACTION_STOP))
        return b.build()
    }

    /** startForeground() again with the same id simply updates the notification. */
    private fun updateNotification() = startInForeground()

    private fun startInForeground() {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(),
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
        if (isRecording) saveRecording() else flush()
        isRunning = false
        overlay?.let { wm?.removeView(it) }
        overlay = null
        super.onDestroy()
    }
}
