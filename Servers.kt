package com.smartnet.speedtest

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * kind: "cf" = Cloudflare (automatic, cannot be picked by city),
 *       "ookla" = server from the Ookla server list,
 *       "custom" = your own server (download URL, optional upload URL).
 */
data class Server(
    val kind: String,
    val id: String,
    val name: String,
    val city: String,
    val country: String,
    val url: String,
    val upUrl: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val distKm: Int = -1,
    val host: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("kind", kind); put("id", id); put("name", name); put("city", city); put("cc", country)
        put("url", url); put("up", upUrl); put("lat", lat); put("lon", lon); put("d", distKm); put("host", host)
    }

    companion object {
        fun fromJson(o: JSONObject) = Server(
            o.getString("kind"), o.getString("id"), o.getString("name"), o.optString("city"),
            o.optString("cc"), o.optString("url"), o.optString("up"),
            o.optDouble("lat", 0.0), o.optDouble("lon", 0.0), o.optInt("d", -1), o.optString("host")
        )
    }
}

val AUTO_SERVER = Server("cf", "auto", "Cloudflare (automatic)", "", "", "")

data class CfInfo(val colo: String, val city: String, val country: String, val ip: String) {
    val label: String
        get() {
            val place = listOf(city, country).filter { it.isNotBlank() }.joinToString(", ")
            return if (place.isEmpty()) "Cloudflare " + colo else "Cloudflare " + colo + " (" + place + ")"
        }
}

/** What the speed engine actually talks to. */
data class Endpoints(
    val label: String,
    val downs: List<String>,
    val up: String?,
    val ping: String,
    val head: Boolean = false,
    val upChunk: Long = 8_000_000L
)

class Resolved(val ep: Endpoints?, val err: String?)

class SearchResult(val list: List<Server>, val error: String?)

object Servers {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    private const val CF_DOWN = "https://speed.cloudflare.com/__down?bytes=25000000"
    private const val CF_UP = "https://speed.cloudflare.com/__up"
    private const val CF_PING = "https://speed.cloudflare.com/__down?bytes=0"

    @Volatile
    private var cfCache: CfInfo? = null

    /** Which Cloudflare data centre this connection reaches (colo code), from the trace page. */
    suspend fun cf(force: Boolean = false): CfInfo? = withContext(Dispatchers.IO) {
        if (!force) cfCache?.let { return@withContext it }
        try {
            val trace = http.newCall(
                Request.Builder().url("https://speed.cloudflare.com/cdn-cgi/trace").build()
            ).execute().use { it.body?.string() ?: "" }
            val kv = trace.lines().mapNotNull { l ->
                val i = l.indexOf('=')
                if (i > 0) l.substring(0, i) to l.substring(i + 1) else null
            }.toMap()
            val colo = kv["colo"] ?: return@withContext null
            var city = ""
            try {
                val txt = http.newCall(
                    Request.Builder().url("https://speed.cloudflare.com/locations").build()
                ).execute().use { it.body?.string() ?: "[]" }
                val arr = JSONArray(txt)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.optString("iata") == colo) {
                        city = o.optString("city")
                        break
                    }
                }
            } catch (_: Exception) {
            }
            val info = CfInfo(colo, city, kv["loc"] ?: "", kv["ip"] ?: "")
            cfCache = info
            info
        } catch (e: Exception) {
            null
        }
    }

    /** Ookla server list. Unofficial endpoint, so it may stop working one day. */
    suspend fun search(query: String, loc: Pair<Double, Double>?): SearchResult = withContext(Dispatchers.IO) {
        try {
            val q = query.trim()
            var out = fetchList(q, loc)
            // If the server-side search returned nothing, load the general list and filter it here.
            if (out.isEmpty() && q.isNotEmpty()) {
                val all = fetchList("", loc)
                out = all.filter {
                    it.name.contains(q, true) || it.city.contains(q, true) || it.country.contains(q, true)
                }
            }
            out = out.sortedBy { if (it.distKm < 0) Int.MAX_VALUE else it.distKm }
            SearchResult(out, if (out.isEmpty()) "No server found." else null)
        } catch (e: Exception) {
            SearchResult(emptyList(), "Server list could not be loaded. Use Cloudflare (automatic) or a custom server.")
        }
    }

    private fun fetchList(q: String, loc: Pair<Double, Double>?): List<Server> {
        val b = "https://www.speedtest.net/api/js/servers".toHttpUrl().newBuilder()
            .addQueryParameter("engine", "js")
            .addQueryParameter("limit", "50")
        if (q.isNotEmpty()) b.addQueryParameter("search", q)
        if (loc != null) {
            b.addQueryParameter("lat", loc.first.toString())
            b.addQueryParameter("lon", loc.second.toString())
        }
        val req = Request.Builder().url(b.build())
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            .header("Accept", "application/json")
            .build()
        val body = http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.string()
        } ?: throw java.io.IOException("list unavailable")
        val arr = JSONArray(body)
        val out = ArrayList<Server>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val host = o.optString("host")
            val url = o.optString("url")
            if (host.isBlank() && url.isBlank()) continue
            val lat = o.optString("lat").toDoubleOrNull() ?: 0.0
            val lon = o.optString("lon").toDoubleOrNull() ?: 0.0
            val d = if (loc != null && (lat != 0.0 || lon != 0.0)) {
                km(loc.first, loc.second, lat, lon)
            } else {
                o.optInt("distance", -1)
            }
            val sponsor = o.optString("sponsor")
            val city = o.optString("name")
            out += Server(
                "ookla", o.optString("id"), sponsor.ifBlank { city }, city,
                o.optString("cc"), url, "", lat, lon, d, host
            )
        }
        return out
    }

    private fun km(la1: Double, lo1: Double, la2: Double, lo2: Double): Int {
        val r = 6371.0
        val dLa = Math.toRadians(la2 - la1)
        val dLo = Math.toRadians(lo2 - lo1)
        val a = sin(dLa / 2).pow(2) + cos(Math.toRadians(la1)) * cos(Math.toRadians(la2)) * sin(dLo / 2).pow(2)
        return (2 * r * asin(sqrt(a))).roundToInt()
    }

    private fun rtt(url: String, head: Boolean = false): Int {
        return try {
            val rb = Request.Builder().url(url).header("Cache-Control", "no-cache")
            if (head) rb.head()
            val t0 = SystemClock.elapsedRealtime()
            http.newCall(rb.build()).execute().use { r ->
                if (!r.isSuccessful) {
                    -1
                } else {
                    r.body?.bytes()
                    (SystemClock.elapsedRealtime() - t0).toInt().coerceAtLeast(1)
                }
            }
        } catch (e: Exception) {
            -1
        }
    }

    /** Turns the chosen server into URLs and checks that it answers. */
    suspend fun resolve(s: Server): Resolved = withContext(Dispatchers.IO) {
        when (s.kind) {
            "ookla" -> {
                val bases = ArrayList<String>()
                if (s.host.isNotBlank()) bases += "https://${s.host}/speedtest"
                if (s.url.isNotBlank()) {
                    val b0 = s.url.substringBeforeLast("/")
                    bases += b0.replace("http://", "https://")
                    bases += b0.replace("https://", "http://")
                }
                var found: Endpoints? = null
                for (b in bases.distinct()) {
                    if (rtt("$b/latency.txt") > 0) {
                        found = Endpoints(
                            "${s.name}, ${s.city}", listOf("$b/random4000x4000.jpg"),
                            "$b/upload.php", "$b/latency.txt", false, 4_000_000L
                        )
                        break
                    }
                }
                if (found == null) Resolved(null, "${s.name} did not respond. Pick another server.")
                else Resolved(found, null)
            }
            "custom" -> {
                if (rtt(s.url, head = true) < 0) {
                    Resolved(null, "${s.name} did not respond. Check the download URL.")
                } else {
                    Resolved(
                        Endpoints(s.name, listOf(s.url), s.upUrl.ifBlank { null }, s.url, true),
                        null
                    )
                }
            }
            else -> {
                val info = cf()
                val label = info?.label ?: "Cloudflare"
                Resolved(
                    Endpoints(
                        label,
                        listOf(CF_DOWN, "https://proof.ovh.net/files/100Mb.dat", "https://speed.hetzner.de/100MB.bin"),
                        CF_UP, CF_PING
                    ),
                    null
                )
            }
        }
    }
}

object Loc {
    fun granted(c: Context): Boolean =
        ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun enabled(c: Context): Boolean = try {
        LocationManagerCompat.isLocationEnabled(c.getSystemService(Context.LOCATION_SERVICE) as LocationManager)
    } catch (e: Exception) {
        false
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    suspend fun get(c: Context): Pair<Double, Double>? {
        if (!granted(c)) return null
        val lm = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var best: Location? = null
        for (p in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            try {
                val l = lm.getLastKnownLocation(p)
                if (l != null && (best == null || l.time > best.time)) best = l
            } catch (_: Exception) {
            }
        }
        val b = best
        if (b != null) return b.latitude to b.longitude
        return withTimeoutOrNull(7000) {
            suspendCancellableCoroutine<Pair<Double, Double>?> { cont ->
                val l = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (cont.isActive) cont.resume(location.latitude to location.longitude)
                    }

                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
                try {
                    lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, l, Looper.getMainLooper())
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
                cont.invokeOnCancellation {
                    try {
                        lm.removeUpdates(l)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }
}
