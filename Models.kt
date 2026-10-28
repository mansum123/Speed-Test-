package com.smartnet.speedtest

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

enum class Mode(val label: String) { DOWN("Download"), UP("Upload"), BOTH("Both") }

/** Display unit. All stored values stay in Mbps; only the display is converted. */
enum class SpeedUnit(val label: String) {
    MBPS("Mbps"), MBYTES("MB/s"), KBYTES("kB/s");

    fun conv(mbps: Float): Float = when (this) {
        MBPS -> mbps
        MBYTES -> mbps / 8f
        KBYTES -> mbps * 125f
    }

    fun fmt(mbps: Float): String {
        val v = conv(mbps)
        return if (this == KBYTES) String.format(Locale.US, "%.0f", v)
        else String.format(Locale.US, "%.1f", v)
    }
}

/** Multi = many parallel connections (like Ookla). Single = one connection (like a real download). */
enum class ConnType(val label: String, val down: Int, val up: Int) {
    MULTI("Multi", 10, 4), SINGLE("Single", 1, 1)
}

data class Sample(val t: Int, val down: Float, val up: Float, val ping: Int)

data class Live(
    val running: Boolean = false,
    val mode: Mode = Mode.BOTH,
    val plannedSec: Int = 0,
    val elapsed: Int = 0,
    val down: Float = 0f,
    val up: Float = 0f,
    val ping: Int = 0,
    val jitter: Int = 0,
    val totalBytes: Long = 0L,
    val samples: List<Sample> = emptyList(),
    val server: String = "",
    val conn: ConnType = ConnType.MULTI,
    val status: String = "",
    val error: String? = null,
    val finished: Report? = null
)

object TestState {
    val live = MutableStateFlow(Live())
}

data class Stats(val avg: Float, val min: Float, val max: Float)

fun stats(v: List<Float>): Stats =
    if (v.isEmpty()) Stats(0f, 0f, 0f) else Stats(v.average().toFloat(), v.min(), v.max())

/** The first seconds of a TCP test are slow-start. Averages/min/max skip them. */
const val WARMUP_SEC = 3

fun steady(samples: List<Sample>): List<Sample> =
    samples.filter { it.t > WARMUP_SEC }.let { if (it.size >= 5) it else samples }

data class Boost(val first: Float, val rest: Float, val dropPct: Float)

/** Network conditions captured just before the test starts. */
data class NetSnap(
    val band: String = "",
    val channel: Int = 0,
    val rssi: Int = 0,
    val linkDown: Int = 0,
    val linkUp: Int = 0,
    val gwMs: Int = -1,
    val netMs: Int = -1,
    val jitter: Int = -1,
    val loss: Int = -1,
    val dnsMs: Int = -1
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("band", band); put("ch", channel); put("rssi", rssi)
        put("ld", linkDown); put("lu", linkUp)
        put("gw", gwMs); put("net", netMs); put("jit", jitter); put("loss", loss); put("dns", dnsMs)
    }

    companion object {
        fun fromJson(o: JSONObject) = NetSnap(
            o.optString("band"), o.optInt("ch"), o.optInt("rssi"),
            o.optInt("ld"), o.optInt("lu"),
            o.optInt("gw", -1), o.optInt("net", -1), o.optInt("jit", -1),
            o.optInt("loss", -1), o.optInt("dns", -1)
        )
    }
}

data class Report(
    val id: Long,
    val mode: Mode,
    val plannedSec: Int,
    val ranSec: Int,
    val totalBytes: Long,
    val samples: List<Sample>,
    val server: String = "",
    val conn: ConnType = ConnType.MULTI,
    val snap: NetSnap? = null
) {
    fun downStats() = stats(steady(samples).map { it.down })
    fun upStats() = stats(steady(samples).map { it.up })

    private fun pick(s: Sample): Float = if (mode == Mode.UP) s.up else s.down

    /** Compares the first 30 seconds with everything after 40 seconds. */
    fun boost(): Boost? {
        val a = samples.filter { it.t <= 30 }.map { pick(it) }
        val b = samples.filter { it.t > 40 }.map { pick(it) }
        if (a.size < 20 || b.size < 30) return null
        val first = a.average().toFloat()
        val rest = b.average().toFloat()
        if (first <= 0f) return null
        return Boost(first, rest, (first - rest) / first * 100f)
    }

    fun toCsv(): String = buildString {
        append("second,download_mbps,upload_mbps,ping_ms\n")
        samples.forEach {
            append("${it.t},${f2(it.down)},${f2(it.up)},${it.ping}\n")
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("mode", mode.name)
        put("planned", plannedSec)
        put("ran", ranSec)
        put("bytes", totalBytes)
        put("server", server)
        put("conn", conn.name)
        snap?.let { put("snap", it.toJson()) }
        put("s", JSONArray().apply {
            samples.forEach {
                put(JSONArray(listOf(it.t, r2(it.down), r2(it.up), it.ping)))
            }
        })
    }

    companion object {
        private fun r2(v: Float): Double = Math.round(v * 100.0) / 100.0

        fun fromJson(o: JSONObject): Report {
            val a = o.getJSONArray("s")
            val list = (0 until a.length()).map { i ->
                val r = a.getJSONArray(i)
                Sample(r.getInt(0), r.getDouble(1).toFloat(), r.getDouble(2).toFloat(), r.getInt(3))
            }
            val conn = try {
                ConnType.valueOf(o.optString("conn", "MULTI"))
            } catch (e: Exception) {
                ConnType.MULTI
            }
            return Report(
                o.getLong("id"), Mode.valueOf(o.getString("mode")),
                o.getInt("planned"), o.getInt("ran"), o.getLong("bytes"), list,
                o.optString("server", ""), conn,
                o.optJSONObject("snap")?.let { NetSnap.fromJson(it) }
            )
        }
    }
}

object History {
    private fun prefs(c: Context) = c.getSharedPreferences("history", Context.MODE_PRIVATE)

    fun load(c: Context): List<Report> {
        return try {
            val a = JSONArray(prefs(c).getString("list", "[]"))
            (0 until a.length()).map { Report.fromJson(a.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(c: Context, r: Report) {
        val list = (listOf(r) + load(c)).take(30)
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(c).edit().putString("list", arr.toString()).apply()
    }

    fun clear(c: Context) {
        prefs(c).edit().remove("list").apply()
    }
}

/** A Wi-Fi signal reading saved for one room (customer-site survey). */
data class RoomReading(val t: Long, val label: String, val rssi: Int, val band: String, val link: Int)

/** Small settings store: unit, gauge scale, connection type, selected server, room readings. */
object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun unit(c: Context): SpeedUnit =
        try { SpeedUnit.valueOf(p(c).getString("unit", "MBPS")!!) } catch (e: Exception) { SpeedUnit.MBPS }

    fun setUnit(c: Context, u: SpeedUnit) = p(c).edit().putString("unit", u.name).apply()

    /** 0 = auto, otherwise 100 / 500 / 1000 Mbps. */
    fun scale(c: Context): Int = p(c).getInt("scale", 0)

    fun setScale(c: Context, s: Int) = p(c).edit().putInt("scale", s).apply()

    fun conn(c: Context): ConnType =
        try { ConnType.valueOf(p(c).getString("conn", "MULTI")!!) } catch (e: Exception) { ConnType.MULTI }

    fun setConn(c: Context, t: ConnType) = p(c).edit().putString("conn", t.name).apply()

    fun server(c: Context): Server =
        try { Server.fromJson(JSONObject(p(c).getString("server", "")!!)) } catch (e: Exception) { AUTO_SERVER }

    fun setServer(c: Context, s: Server) = p(c).edit().putString("server", s.toJson().toString()).apply()

    fun rooms(c: Context): List<RoomReading> = try {
        val a = JSONArray(p(c).getString("rooms", "[]"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            RoomReading(o.getLong("t"), o.getString("l"), o.getInt("r"), o.getString("b"), o.getInt("k"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun setRooms(c: Context, list: List<RoomReading>) {
        val a = JSONArray()
        list.take(100).forEach {
            a.put(JSONObject().put("t", it.t).put("l", it.label).put("r", it.rssi).put("b", it.band).put("k", it.link))
        }
        p(c).edit().putString("rooms", a.toString()).apply()
    }
}

fun f1(v: Float): String = String.format(Locale.US, "%.1f", v)
fun f2(v: Float): String = String.format(Locale.US, "%.2f", v)
fun fmtBytes(b: Long): String =
    if (b >= 1_000_000_000L) String.format(Locale.US, "%.2f GB", b / 1e9)
    else String.format(Locale.US, "%.0f MB", b / 1e6)

/** Clock style, for timers and chart axes: 2:05 */
fun fmtTime(s: Int): String = String.format(Locale.US, "%d:%02d", s / 60, s % 60)

/** Written duration with units: 5 min, 1 hr, 1 hr 30 min, 45 sec */
fun fmtDur(sec: Int): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    val parts = ArrayList<String>()
    if (h > 0) parts += "$h hr"
    if (m > 0) parts += "$m min"
    if (s > 0 && h == 0) parts += "$s sec"
    return if (parts.isEmpty()) "0 sec" else parts.joinToString(" ")
}

fun fmtMin(min: Int): String = fmtDur(min * 60)
