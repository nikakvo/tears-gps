package io.github.jqssun.gpssetter.utils

import io.github.jqssun.gpssetter.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Road routing through the FOSSGIS OSRM servers (routing.openstreetmap.de), OpenStreetMap data.
 * No key needed. Usage policy: reasonable non-commercial use, max 1 request/s, a real User-Agent,
 * attribution "© OpenStreetMap contributors, OSRM by FOSSGIS" shown to the user.
 */
object RouteFinder {

    enum class Mode(val server: String) { CAR("routed-car"), BIKE("routed-bike"), FOOT("routed-foot") }

    private const val BASE = "https://routing.openstreetmap.de"
    private val userAgent = "TearsGPS/${BuildConfig.VERSION_NAME} (LSPosed module; +https://github.com/nikakvo/tears-gps)"

    /**
     * Returns the route as timed points. Timing comes from OSRM's per-segment durations, so the
     * replay is slower in town and faster on open roads, like a real trip.
     */
    suspend fun find(
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double,
        mode: Mode
    ): List<RoutePoint> = withContext(Dispatchers.IO) {
        val url = String.format(
            Locale.ROOT,
            "%s/%s/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson&annotations=duration",
            BASE, mode.server, fromLon, fromLat, toLon, toLat
        )
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", userAgent)
        }
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            val json = if (body.isNotEmpty()) JSONObject(body) else JSONObject()
            if (code !in 200..299 || json.optString("code") != "Ok") {
                throw IOException(json.optString("message").ifEmpty { "HTTP $code" })
            }
            parse(json)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Place search through OpenStreetMap Nominatim (fallback when the phone's Geocoder finds nothing).
     * Usage policy: max 1 request/s, real User-Agent, no autocomplete; one request per search here.
     */
    suspend fun searchPlace(query: String): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&q=" +
            URLEncoder.encode(query, "UTF-8")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag())
        }
        try {
            if (conn.responseCode !in 200..299) return@withContext null
            val arr = org.json.JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
            if (arr.length() == 0) return@withContext null
            val o = arr.getJSONObject(0)
            o.getString("lat").toDouble() to o.getString("lon").toDouble()
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(json: JSONObject): List<RoutePoint> {
        val route = json.getJSONArray("routes").getJSONObject(0)
        val coords = route.getJSONObject("geometry").getJSONArray("coordinates")
        val n = coords.length()
        if (n < 2) throw IOException("Empty route")

        val lats = DoubleArray(n)
        val lons = DoubleArray(n)
        for (i in 0 until n) {
            val c = coords.getJSONArray(i)
            lons[i] = c.getDouble(0)
            lats[i] = c.getDouble(1)
        }

        // Per-segment durations (seconds). With a single leg and overview=full there is one per segment.
        val seg = DoubleArray(n - 1)
        val legs = route.getJSONArray("legs")
        val durations = ArrayList<Double>()
        for (l in 0 until legs.length()) {
            legs.getJSONObject(l).optJSONObject("annotation")?.optJSONArray("duration")?.let { d ->
                for (i in 0 until d.length()) durations.add(d.getDouble(i))
            }
        }
        if (durations.size == n - 1) {
            for (i in seg.indices) seg[i] = durations[i]
        } else {
            // Fallback: spread the total duration by segment length.
            val total = route.getDouble("duration")
            val lengths = DoubleArray(n - 1)
            val out = FloatArray(1)
            var sum = 0.0
            for (i in 0 until n - 1) {
                android.location.Location.distanceBetween(lats[i], lons[i], lats[i + 1], lons[i + 1], out)
                lengths[i] = out[0].toDouble(); sum += lengths[i]
            }
            for (i in seg.indices) seg[i] = if (sum > 0) total * lengths[i] / sum else total / seg.size
        }

        val points = ArrayList<RoutePoint>(n)
        var t = 0.0
        points.add(RoutePoint(lats[0], lons[0], 0L))
        for (i in 1 until n) {
            t += seg[i - 1] * 1000.0
            points.add(RoutePoint(lats[i], lons[i], t.toLong()))
        }
        return points
    }
}
