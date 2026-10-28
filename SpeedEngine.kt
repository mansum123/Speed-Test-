package com.smartnet.speedtest

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Keeps downloading and/or uploading non-stop against the chosen server and reports one sample per second.
 * Multi = many parallel connections (like Ookla), Single = one connection.
 */
class SpeedEngine {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val pingClient = OkHttpClient.Builder()
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val downBytes = AtomicLong(0)
    private val upBytes = AtomicLong(0)
    private val pingMs = AtomicInteger(0)
    private val jitterMs = AtomicInteger(0)

    suspend fun run(
        mode: Mode,
        plannedSec: Int,
        ep: Endpoints,
        conn: ConnType,
        onUpdate: (Sample, List<Sample>, Long, Int) -> Unit
    ): List<Sample> = coroutineScope {
        val samples = ArrayList<Sample>()
        val jobs = mutableListOf<Job>()
        try {
            if (mode != Mode.UP) repeat(conn.down) { jobs += launch(Dispatchers.IO) { downloadLoop(ep) } }
            if (mode != Mode.DOWN && ep.up != null) repeat(conn.up) { jobs += launch(Dispatchers.IO) { uploadLoop(ep) } }
            jobs += launch(Dispatchers.IO) { pingLoop(ep) }

            var lastD = 0L
            var lastU = 0L
            var last = SystemClock.elapsedRealtime()
            for (sec in 1..plannedSec) {
                delay(1000)
                val now = SystemClock.elapsedRealtime()
                val dt = (now - last) / 1000f
                last = now
                val d = downBytes.get()
                val u = upBytes.get()
                val s = Sample(
                    sec,
                    (d - lastD) * 8f / dt / 1_000_000f,
                    (u - lastU) * 8f / dt / 1_000_000f,
                    pingMs.get()
                )
                lastD = d
                lastU = u
                samples += s
                onUpdate(s, samples, d + u, jitterMs.get())
            }
        } finally {
            jobs.forEach { it.cancel() }
            calls.forEach { it.cancel() }
        }
        samples
    }

    private fun bust(url: String): String =
        url + (if (url.contains('?')) "&" else "?") + "x=" + System.nanoTime()

    private suspend fun CoroutineScope.downloadLoop(ep: Endpoints) {
        var idx = 0
        val buf = ByteArray(65536)
        while (isActive) {
            val call = client.newCall(
                Request.Builder().url(bust(ep.downs[idx])).header("Cache-Control", "no-cache").build()
            )
            calls.add(call)
            try {
                if (!isActive) call.cancel()
                call.execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP " + r.code)
                    val s = r.body!!.byteStream()
                    while (true) {
                        val n = s.read(buf)
                        if (n < 0) break
                        downBytes.addAndGet(n.toLong())
                    }
                }
            } catch (e: Exception) {
                if (!isActive) return
                idx = (idx + 1) % ep.downs.size
                delay(1000)
            } finally {
                calls.remove(call)
            }
        }
    }

    private suspend fun CoroutineScope.uploadLoop(ep: Endpoints) {
        val url = ep.up ?: return
        while (isActive) {
            val call = client.newCall(
                Request.Builder().url(url).post(CountingBody(ep.upChunk, upBytes)).build()
            )
            calls.add(call)
            try {
                if (!isActive) call.cancel()
                call.execute().use { r ->
                    r.body?.bytes()
                    if (!r.isSuccessful) throw IOException("HTTP " + r.code)
                }
            } catch (e: Exception) {
                if (!isActive) return
                delay(1000)
            } finally {
                calls.remove(call)
            }
        }
    }

    private suspend fun CoroutineScope.pingLoop(ep: Endpoints) {
        val recent = ArrayDeque<Int>()
        while (isActive) {
            val p = measurePing(ep)
            if (p > 0) {
                pingMs.set(p)
                recent.addLast(p)
                if (recent.size > 10) recent.removeFirst()
                if (recent.size > 1) {
                    jitterMs.set(recent.zipWithNext { a, b -> Math.abs(a - b) }.average().toInt())
                }
            }
            delay(2000)
        }
    }

    private fun measurePing(ep: Endpoints): Int {
        return try {
            val rb = Request.Builder().url(bust(ep.ping)).header("Cache-Control", "no-cache")
            if (ep.head) rb.head()
            val call = pingClient.newCall(rb.build())
            calls.add(call)
            val t0 = SystemClock.elapsedRealtime()
            try {
                call.execute().use { it.body?.bytes() }
                (SystemClock.elapsedRealtime() - t0).toInt().coerceAtLeast(1)
            } finally {
                calls.remove(call)
            }
        } catch (e: Exception) {
            0
        }
    }

    private class CountingBody(private val total: Long, private val counter: AtomicLong) : RequestBody() {
        override fun contentType() = "application/octet-stream".toMediaType()
        override fun contentLength() = total
        override fun writeTo(sink: BufferedSink) {
            val chunk = ByteArray(65536).also { Random.nextBytes(it) }
            var left = total
            while (left > 0) {
                val n = minOf(left, chunk.size.toLong()).toInt()
                sink.write(chunk, 0, n)
                sink.flush()
                counter.addAndGet(n.toLong())
                left -= n
            }
        }
    }
}
