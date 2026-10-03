package io.github.jqssun.gpssetter.utils

import android.content.Context
import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** One recorded sample: position and time since the start of the route (ms). */
data class RoutePoint(val lat: Double, val lon: Double, val t: Long)

/**
 * [back] is an optional real return path (B -> A), e.g. computed by OSRM; when it is missing,
 * a round trip replays [points] in reverse.
 */
data class Route(
    val id: String,
    val name: String,
    val points: List<RoutePoint>,
    val back: List<RoutePoint>? = null
) {
    /** Return path: the stored one, or the outward path mirrored in time. */
    val returnPoints: List<RoutePoint>
        get() = back ?: points.reversed().map { it.copy(t = durationMs - it.t) }

    val durationMs: Long get() = points.lastOrNull()?.t ?: 0L

    val distanceMeters: Double
        get() {
            var sum = 0.0
            val out = FloatArray(1)
            for (i in 1 until points.size) {
                val a = points[i - 1]
                val b = points[i]
                Location.distanceBetween(a.lat, a.lon, b.lat, b.lon, out)
                sum += out[0]
            }
            return sum
        }
}

/** Routes are small JSON files in the app's private storage: files/routes/<id>.json */
object RouteStore {

    private fun dir(context: Context) = File(context.filesDir, "routes").apply { mkdirs() }

    fun list(context: Context): List<Route> =
        dir(context).listFiles { f -> f.name.endsWith(".json") }
            ?.mapNotNull { read(it) }
            ?.sortedByDescending { it.id.toLongOrNull() ?: 0L }
            ?: emptyList()

    fun load(context: Context, id: String): Route? = read(File(dir(context), "$id.json"))

    fun save(
        context: Context, name: String, points: List<RoutePoint>, back: List<RoutePoint>? = null
    ): Route {
        val route = Route(System.currentTimeMillis().toString(), name, points, back)
        write(context, route)
        return route
    }

    fun rename(context: Context, id: String, name: String) {
        load(context, id)?.let { write(context, it.copy(name = name)) }
    }

    fun delete(context: Context, id: String) {
        File(dir(context), "$id.json").delete()
    }

    private fun write(context: Context, route: Route) {
        fun encode(list: List<RoutePoint>) = JSONArray().also { arr ->
            for (p in list) arr.put(JSONArray().put(p.lat).put(p.lon).put(p.t))
        }
        val json = JSONObject().put("name", route.name).put("points", encode(route.points))
        route.back?.let { json.put("back", encode(it)) }
        val target = File(dir(context), "${route.id}.json")
        val tmp = File(target.path + ".tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(target)
    }

    private fun read(file: File): Route? = try {
        val json = JSONObject(file.readText())
        fun decode(arr: JSONArray) = ArrayList<RoutePoint>(arr.length()).also { list ->
            for (i in 0 until arr.length()) {
                val p = arr.getJSONArray(i)
                list.add(RoutePoint(p.getDouble(0), p.getDouble(1), p.getLong(2)))
            }
        }
        Route(
            file.nameWithoutExtension,
            json.optString("name", file.nameWithoutExtension),
            decode(json.getJSONArray("points")),
            json.optJSONArray("back")?.let { decode(it) }
        )
    } catch (e: Exception) {
        null
    }

    fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }

    fun formatDistance(m: Double): String =
        if (m >= 1000) String.format(Locale.ROOT, "%.2f km", m / 1000) else String.format(Locale.ROOT, "%.0f m", m)
}
