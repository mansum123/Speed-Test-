@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.smartnet.speedtest

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** One step of the streaming ladder. mbps = typical bitrate of one stream at that resolution. */
data class Rung(val name: String, val pixels: String, val mbps: Float)

val LADDER = listOf(
    Rung("240p", "426 x 240", 0.4f),
    Rung("360p", "640 x 360", 0.8f),
    Rung("480p", "854 x 480", 1.5f),
    Rung("720p HD", "1280 x 720", 3f),
    Rung("1080p Full HD", "1920 x 1080", 6f),
    Rung("1440p 2K", "2560 x 1440", 12f),
    Rung("2160p 4K", "3840 x 2160", 25f)
)

/** 2 = smooth, 1 = may buffer, 0 = too slow */
data class RungResult(val rung: Rung, val grade: Int)

data class VideoState(
    val running: Boolean = false,
    val elapsed: Int = 0,
    val cur: Float = 0f,
    val server: String = "",
    val done: Boolean = false,
    val results: List<RungResult> = emptyList(),
    val best: Rung? = null,
    val avg: Float = 0f,
    val low: Float = 0f,
    val startMs: Int = 0,
    val dipSec: Int = 0,
    val error: String? = null
)

object VideoRunner {
    const val TEST_SEC = 15
    private const val SKIP_SEC = 3

    val state = MutableStateFlow(VideoState())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun stop() {
        job?.cancel()
        calls.forEach { it.cancel() }
    }

    fun start(ctx: Context) {
        stop()
        val app = ctx.applicationContext
        job = scope.launch {
            state.value = VideoState(running = true)
            try {
                val res = Servers.resolve(Prefs.server(app))
                val ep = res.ep
                if (ep == null) {
                    state.value = VideoState(error = res.err ?: "Server did not respond.")
                    return@launch
                }
                state.value = VideoState(running = true, server = ep.label)

                val bytes = AtomicLong(0)
                val firstByte = AtomicLong(-1)
                val t0 = SystemClock.elapsedRealtime()
                // A video player normally uses one or two connections, so two are used here.
                val workers = (1..2).map {
                    launch {
                        var idx = 0
                        val buf = ByteArray(65536)
                        while (isActive) {
                            val url = ep.downs[idx] + (if (ep.downs[idx].contains('?')) "&" else "?") + "x=" + System.nanoTime()
                            val call = client.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build())
                            calls.add(call)
                            try {
                                call.execute().use { r ->
                                    if (!r.isSuccessful) throw java.io.IOException("HTTP " + r.code)
                                    val s = r.body!!.byteStream()
                                    while (true) {
                                        val n = s.read(buf)
                                        if (n < 0) break
                                        if (firstByte.get() < 0) firstByte.compareAndSet(-1, SystemClock.elapsedRealtime() - t0)
                                        bytes.addAndGet(n.toLong())
                                    }
                                }
                            } catch (e: Exception) {
                                if (!isActive) return@launch
                                idx = (idx + 1) % ep.downs.size
                                delay(500)
                            } finally {
                                calls.remove(call)
                            }
                        }
                    }
                }

                val secs = ArrayList<Float>()
                var last = 0L
                var lastT = SystemClock.elapsedRealtime()
                for (sec in 1..TEST_SEC) {
                    delay(1000)
                    val now = SystemClock.elapsedRealtime()
                    val b = bytes.get()
                    val mbps = (b - last) * 8f / ((now - lastT) / 1000f) / 1_000_000f
                    last = b
                    lastT = now
                    secs += mbps
                    state.value = state.value.copy(elapsed = sec, cur = mbps)
                }
                workers.forEach { it.cancel() }
                calls.forEach { it.cancel() }

                val steadySecs = secs.drop(SKIP_SEC)
                val avg = steadySecs.average().toFloat()
                val sorted = steadySecs.sorted()
                val low = sorted[(sorted.size * 0.1f).toInt().coerceAtMost(sorted.size - 1)]
                val results = LADDER.map { r ->
                    RungResult(
                        r,
                        when {
                            low >= r.mbps * 1.5f -> 2
                            avg >= r.mbps * 1.2f -> 1
                            else -> 0
                        }
                    )
                }
                val best = results.lastOrNull { it.grade == 2 }?.rung ?: results.lastOrNull { it.grade == 1 }?.rung
                val dips = if (best == null) 0 else steadySecs.count { it < best.mbps }
                val startMs = if (best == null || avg <= 0f) 0 else
                    (firstByte.get().coerceAtLeast(0) + best.mbps * 2f / avg * 1000f).toInt()
                state.value = VideoState(
                    done = true, server = state.value.server, elapsed = TEST_SEC,
                    results = results, best = best, avg = avg, low = low, startMs = startMs, dipSec = dips
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    state.value = state.value.copy(running = false)
                    throw e
                }
                state.value = VideoState(error = "Test failed. Check the internet connection.")
            }
        }
    }
}

private fun gradeText(g: Int) = when (g) {
    2 -> "Smooth"
    1 -> "May buffer"
    else -> "Too slow"
}

@Composable
fun VideoScreen() {
    val ctx = LocalContext.current
    val st by VideoRunner.state.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Video Test", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "Finds the highest video quality (resolution) your connection can play smoothly. " +
                "It uses about ${VideoRunner.TEST_SEC} seconds of data from the server you selected on the Speed tab.",
            style = MaterialTheme.typography.bodyMedium
        )

        if (st.running) {
            if (st.server.isNotBlank()) Text("Server: ${st.server}")
            LinearProgressIndicator(
                progress = { st.elapsed.toFloat() / VideoRunner.TEST_SEC },
                modifier = Modifier.fillMaxWidth()
            )
            Text("Testing... ${f1(st.cur)} Mbps", fontWeight = FontWeight.SemiBold)
            Button(
                onClick = { VideoRunner.stop() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) { Text("Stop") }
        } else {
            Button(onClick = { VideoRunner.start(ctx) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (st.done) "Run again" else "Start video test")
            }
        }

        st.error?.let {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(it, Modifier.padding(14.dp), fontWeight = FontWeight.SemiBold)
            }
        }

        if (st.done) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Your connection can play", style = MaterialTheme.typography.bodySmall)
                    Text(st.best?.name ?: "Below 240p", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    st.best?.let { Text(it.pixels + " pixels", style = MaterialTheme.typography.bodyMedium) }
                    Text(
                        "Average ${f1(st.avg)} Mbps  |  slowest moments ${f1(st.low)} Mbps",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (st.best != null) {
                        Text(
                            "Video start: about ${st.startMs} ms  |  seconds below needed speed: ${st.dipSec}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Quality ladder", fontWeight = FontWeight.Bold)
                    st.results.reversed().forEach { r ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(r.rung.name, fontWeight = FontWeight.SemiBold)
                                Text("${r.rung.pixels}  |  needs ~${f1(r.rung.mbps)} Mbps", style = MaterialTheme.typography.bodySmall)
                            }
                            Text(
                                gradeText(r.grade),
                                fontWeight = FontWeight.Bold,
                                color = when (r.grade) { 2 -> GoodColor; 1 -> WarnColor; else -> BadColor }
                            )
                        }
                    }
                }
            }

            Text(
                "This is an estimate for ONE video stream. It does not play a real video: it measures sustained speed " +
                    "and compares it with typical streaming bitrates. Other people using the Wi-Fi at the same time will lower the result.",
                style = MaterialTheme.typography.bodySmall
            )

            OutlinedButton(
                onClick = {
                    val t = "Video test: ${st.best?.name ?: "below 240p"}" +
                        (st.best?.let { " (${it.pixels})" } ?: "") + "\n" +
                        "Server: ${st.server}\n" +
                        "Average ${f1(st.avg)} Mbps, slowest moments ${f1(st.low)} Mbps\n" +
                        st.results.reversed().joinToString("\n") { "${it.rung.name}: ${gradeText(it.grade)}" }
                    ctx.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t),
                            "Share result"
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Share result") }
        }
    }
}
