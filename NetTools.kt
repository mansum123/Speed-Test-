package com.smartnet.speedtest

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.random.Random

// ------------------------------------------------------------------ ping

/** Summary of a list of ping results. null in the list = lost packet. */
data class PStat(
    val cur: Float?,
    val avg: Float,
    val min: Float,
    val max: Float,
    val jitter: Float,
    val loss: Float,
    val sent: Int
)

fun pstat(l: List<Float?>): PStat {
    val ok = l.filterNotNull()
    if (l.isEmpty()) return PStat(null, 0f, 0f, 0f, 0f, 0f, 0)
    val jit = if (ok.size > 1) ok.zipWithNext { a, b -> abs(b - a) }.average().toFloat() else 0f
    val lost = l.count { it == null }
    return PStat(
        l.last(),
        if (ok.isEmpty()) 0f else ok.average().toFloat(),
        ok.minOrNull() ?: 0f, ok.maxOrNull() ?: 0f,
        jit, lost * 100f / l.size, l.size
    )
}

object PingTool {
    private val timeRx = Regex("time[=<]\\s*([0-9.]+)")

    /** One ping in ms, or null if there was no reply. Uses the system ping, then a TCP check as backup. */
    fun once(host: String, timeoutSec: Int = 2): Float? {
        return try {
            val p = ProcessBuilder("/system/bin/ping", "-c", "1", "-W", timeoutSec.toString(), host)
                .redirectErrorStream(true).start()
            if (!p.waitFor((timeoutSec + 2).toLong(), TimeUnit.SECONDS)) {
                p.destroyForcibly()
                return null
            }
            val out = p.inputStream.bufferedReader().readText()
            val m = timeRx.find(out)
            when {
                m != null -> m.groupValues[1].toFloatOrNull()
                p.exitValue() == 1 -> null
                else -> tcp(host)
            }
        } catch (e: Exception) {
            tcp(host)
        }
    }

    private fun tcp(host: String, timeoutMs: Int = 2000): Float? {
        val t0 = System.nanoTime()
        return try {
            Socket().use { it.connect(InetSocketAddress(host, 443), timeoutMs) }
            (System.nanoTime() - t0) / 1e6f
        } catch (e: ConnectException) {
            if (e.message?.contains("refused", true) == true) (System.nanoTime() - t0) / 1e6f else null
        } catch (e: Exception) {
            null
        }
    }

    /** Time of one uncached DNS lookup in ms (random name, so the cache cannot answer). -1 = failed. */
    fun dns(ctx: Context): Int {
        val name = "d" + Random.nextInt(100000, 999999) + ".google.com"
        val t0 = System.nanoTime()
        try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val net = cm?.activeNetwork
            if (net != null) net.getAllByName(name) else InetAddress.getAllByName(name)
        } catch (e: UnknownHostException) {
            // NXDOMAIN is the normal answer for a random name; the time is still the lookup time.
        } catch (e: Exception) {
            return -1
        }
        return ((System.nanoTime() - t0) / 1_000_000L).toInt().coerceAtLeast(1)
    }
}

// ------------------------------------------------------------------ Wi-Fi

data class WifiNow(
    val rssi: Int,
    val freq: Int,
    val channel: Int,
    val band: String,
    val width: String,
    val down: Int,
    val up: Int,
    val std: String
)

data class Ap(
    val ssid: String,
    val bssid: String,
    val rssi: Int,
    val freq: Int,
    val channel: Int,
    val band: String,
    val width: String,
    val security: String,
    val connected: Boolean
)

data class ChanLoad(val channel: Int, val count: Int, val score: Float)

@android.annotation.SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
object WifiTool {
    private fun wm(ctx: Context) = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    fun channelOf(f: Int): Int = when {
        f == 2484 -> 14
        f in 2412..2472 -> (f - 2407) / 5
        f in 5000..5895 -> (f - 5000) / 5
        f in 5955..7115 -> (f - 5950) / 5
        else -> 0
    }

    fun bandOf(f: Int): String = when {
        f < 3000 -> "2.4 GHz"
        f < 5925 -> "5 GHz"
        else -> "6 GHz"
    }

    private fun widthOf(w: Int): String = when (w) {
        ScanResult.CHANNEL_WIDTH_20MHZ -> "20 MHz"
        ScanResult.CHANNEL_WIDTH_40MHZ -> "40 MHz"
        ScanResult.CHANNEL_WIDTH_80MHZ -> "80 MHz"
        ScanResult.CHANNEL_WIDTH_160MHZ -> "160 MHz"
        ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> "80+80 MHz"
        else -> ""
    }

    fun security(caps: String): String {
        val parts = ArrayList<String>()
        if (caps.contains("SAE")) parts += "WPA3"
        if (caps.contains("RSN") || caps.contains("WPA2")) parts += "WPA2"
        if (caps.contains("WPA-")) parts += "WPA"
        if (caps.contains("WEP")) parts += "WEP"
        return if (parts.isEmpty()) "Open" else parts.joinToString("/")
    }

    /** Current connection, or null if not on Wi-Fi. */
    fun now(ctx: Context): WifiNow? {
        return try {
            val w = wm(ctx)
            val info = w.connectionInfo ?: return null
            if (info.rssi <= -127 || info.frequency <= 0) return null
            val freq = info.frequency
            var down = info.linkSpeed
            var up = info.linkSpeed
            if (Build.VERSION.SDK_INT >= 29) {
                if (info.rxLinkSpeedMbps > 0) down = info.rxLinkSpeedMbps
                if (info.txLinkSpeedMbps > 0) up = info.txLinkSpeedMbps
            }
            val std = if (Build.VERSION.SDK_INT >= 30) {
                when (info.wifiStandard) {
                    4 -> "Wi-Fi 4 (n)"
                    5 -> "Wi-Fi 5 (ac)"
                    6 -> "Wi-Fi 6 (ax)"
                    8 -> "Wi-Fi 7 (be)"
                    else -> ""
                }
            } else ""
            val width = try {
                w.scanResults.firstOrNull { it.BSSID == info.bssid }?.let { widthOf(it.channelWidth) } ?: ""
            } catch (e: Exception) {
                ""
            }
            WifiNow(info.rssi, freq, channelOf(freq), bandOf(freq), width, down, up, std)
        } catch (e: Exception) {
            null
        }
    }

    /** Asks Android for a fresh scan. Android limits this to about 4 scans per 2 minutes. */
    fun startScan(ctx: Context): Boolean = try {
        wm(ctx).startScan()
    } catch (e: Exception) {
        false
    }

    fun scan(ctx: Context): List<Ap> {
        return try {
            val w = wm(ctx)
            val cur = try { w.connectionInfo?.bssid } catch (e: Exception) { null }
            w.scanResults.map { r ->
                Ap(
                    r.SSID?.takeIf { it.isNotBlank() } ?: "(hidden)",
                    r.BSSID ?: "", r.level, r.frequency, channelOf(r.frequency), bandOf(r.frequency),
                    widthOf(r.channelWidth), security(r.capabilities ?: ""),
                    cur != null && cur.equals(r.BSSID, ignoreCase = true)
                )
            }.sortedByDescending { it.rssi }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun weight(rssi: Int): Float = ((rssi + 100).coerceIn(0, 60)) / 60f

    /** 2.4 GHz channels 1-13: how crowded each one is, counting overlapping neighbours. */
    fun load24(aps: List<Ap>): List<ChanLoad> {
        val a = aps.filter { it.band == "2.4 GHz" }
        return (1..13).map { ch ->
            val near = a.filter { abs(it.channel - ch) <= 4 }
            ChanLoad(ch, near.size, near.sumOf { ((if (it.channel == ch) 1f else 0.5f) * weight(it.rssi)).toDouble() }.toFloat())
        }
    }

    /** 5 GHz: only same-channel networks are counted. */
    fun load5(aps: List<Ap>): List<ChanLoad> {
        val a = aps.filter { it.band == "5 GHz" }
        val chans = (listOf(36, 40, 44, 48, 149, 153, 157, 161, 165) + a.map { it.channel }).distinct().sorted()
        return chans.map { ch ->
            val same = a.filter { it.channel == ch }
            ChanLoad(ch, same.size, same.sumOf { weight(it.rssi).toDouble() }.toFloat())
        }
    }
}

// ------------------------------------------------------------------ network

object NetTools {
    fun gateway(ctx: Context): String? {
        try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val lp = cm?.getLinkProperties(cm.activeNetwork)
            val g = lp?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
            if (g != null) return g
        } catch (_: Exception) {
        }
        return try {
            @Suppress("DEPRECATION")
            val g = (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).dhcpInfo.gateway
            if (g == 0) null
            else "${g and 0xff}.${(g shr 8) and 0xff}.${(g shr 16) and 0xff}.${(g shr 24) and 0xff}"
        } catch (e: Exception) {
            null
        }
    }

    /** Quick reading of Wi-Fi, gateway/internet ping, jitter, loss and DNS before a speed test (about 3 s). */
    suspend fun snapshot(ctx: Context): NetSnap = withContext(Dispatchers.IO) {
        val w = WifiTool.now(ctx)
        val gw = gateway(ctx)
        coroutineScope {
            val a = async { burst(gw, 4) }
            val b = async { burst("8.8.8.8", 4) }
            val d = async { PingTool.dns(ctx) }
            val ga = a.await()
            val nb = b.await()
            val ns = pstat(nb)
            val gs = pstat(ga)
            NetSnap(
                band = w?.band ?: "", channel = w?.channel ?: 0, rssi = w?.rssi ?: 0,
                linkDown = w?.down ?: 0, linkUp = w?.up ?: 0,
                gwMs = if (gw != null && gs.avg > 0f) gs.avg.toInt() else -1,
                netMs = if (ns.avg > 0f) ns.avg.toInt() else -1,
                jitter = if (ns.avg > 0f) ns.jitter.toInt() else -1,
                loss = if (nb.isNotEmpty()) ns.loss.toInt() else -1,
                dnsMs = d.await()
            )
        }
    }

    private suspend fun burst(host: String?, n: Int): List<Float?> {
        if (host == null) return emptyList()
        val out = ArrayList<Float?>()
        repeat(n) {
            out += PingTool.once(host)
            delay(250)
        }
        return out
    }
}

// ------------------------------------------------------------------ latency test (runs in the background of the app)

data class LatState(
    val running: Boolean = false,
    val gwHost: String = "",
    val netHost: String = "",
    val gw: List<Float?> = emptyList(),
    val net: List<Float?> = emptyList(),
    val dns: List<Int> = emptyList(),
    val elapsed: Int = 0,
    val planned: Int = 0
)

object LatencyRunner {
    val state = MutableStateFlow(LatState())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun start(ctx: Context, netHost: String, sec: Int) {
        job?.cancel()
        val app = ctx.applicationContext
        job = scope.launch {
            val gw = NetTools.gateway(app)
            var s = LatState(running = true, gwHost = gw ?: "", netHost = netHost, planned = sec)
            state.value = s
            val t0 = SystemClock.elapsedRealtime()
            var i = 0
            try {
                while (isActive && SystemClock.elapsedRealtime() - t0 < sec * 1000L) {
                    val tick = SystemClock.elapsedRealtime()
                    val (a, b) = coroutineScope {
                        val x = async { if (gw != null) PingTool.once(gw) else null }
                        val y = async { PingTool.once(netHost) }
                        x.await() to y.await()
                    }
                    var dns = s.dns
                    if (i % 5 == 0) {
                        val d = PingTool.dns(app)
                        if (d > 0) dns = dns + d
                    }
                    i++
                    s = s.copy(
                        gw = if (gw != null) s.gw + listOf(a) else s.gw,
                        net = s.net + listOf(b),
                        dns = dns,
                        elapsed = ((SystemClock.elapsedRealtime() - t0) / 1000L).toInt()
                    )
                    state.value = s
                    val wait = 1000L - (SystemClock.elapsedRealtime() - tick)
                    if (wait > 0) delay(wait)
                }
            } finally {
                state.value = state.value.copy(running = false)
            }
        }
    }

    fun stop() {
        job?.cancel()
    }
}
