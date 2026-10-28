package com.smartnet.speedtest

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

/** Builds the PDF report and the PNG summary card, and shares them. */
object ReportExporter {

    private val NAVY = 0xFF0B3D66.toInt()
    private val LIGHT = 0xFFF5F8FA.toInt()
    private val WHITE = 0xFFFFFFFF.toInt()
    private val GREY = 0xFF666666.toInt()
    private val DOWN = 0xFF2E7DFF.toInt()
    private val UP = 0xFF22B573.toInt()
    private val RED = 0xFFD32F2F.toInt()
    private val AMBER = 0xFFE08A00.toInt()
    private val GREEN = 0xFF2E9E5B.toInt()
    private val LINE = 0xFFE1E8EE.toInt()

    private fun text(color: Int, size: Float, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            textAlign = align
        }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }

    private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = width
    }

    private fun date(id: Long): String =
        SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(id))

    private fun wrapped(c: Canvas, s: String, x: Float, y: Float, width: Int, paint: Paint) {
        val layout = StaticLayout.Builder.obtain(s, 0, s.length, TextPaint(paint), width).build()
        c.save()
        c.translate(x, y)
        layout.draw(c)
        c.restore()
    }

    private fun cut(s: String, n: Int) = if (s.length <= n) s else s.take(n - 1) + "..."

    private data class Verdict(val title: String, val text: String, val color: Int)

    private fun verdict(r: Report, u: SpeedUnit): Verdict {
        val b = r.boost()
            ?: return Verdict("Boost check", "Test was too short for a boost check. Run at least 2 min.", GREY)
        val pct = b.dropPct.toInt()
        val a = "${u.fmt(b.first)} to ${u.fmt(b.rest)} ${u.label}"
        return when {
            b.dropPct >= 25f -> Verdict(
                "Temporary boost likely",
                "Speed fell by $pct% after the first 30 seconds ($a).", RED
            )
            b.dropPct >= 10f -> Verdict(
                "Moderate drop",
                "Speed fell by $pct% after the first 30 seconds ($a). Repeat the test at another time.",
                AMBER
            )
            else -> Verdict(
                "Speed is steady",
                "No sign of a temporary boost: ${u.fmt(b.first)} ${u.label} in the first 30 s, ${u.fmt(b.rest)} later.", GREEN
            )
        }
    }

    private fun niceStep(maxRaw: Float): Float {
        val target = maxRaw.coerceAtLeast(1f) / 4f
        val opts = listOf(0.5f, 1f, 2f, 2.5f, 5f, 10f, 20f, 25f, 50f, 100f, 200f, 250f, 500f)
        return opts.firstOrNull { it >= target } ?: (ceil(target / 100f) * 100f)
    }

    private fun axis(v: Float): String = if (v % 1f == 0f) v.toInt().toString() else f1(v)

    private fun pingStats(r: Report): Triple<Int, Int, Int> {
        val p = r.samples.map { it.ping }.filter { it > 0 }
        if (p.isEmpty()) return Triple(0, 0, 0)
        return Triple(p.average().toInt(), p.min(), p.max())
    }

    /** Line chart with grid, scale in the chosen unit, time labels, soft fill and optional 30 s marker. */
    private fun drawChart(
        c: Canvas, r: RectF, s: List<Sample>, showD: Boolean, showU: Boolean,
        mark30: Boolean, fs: Float, sw: Float, u: SpeedUnit, scale: Int
    ) {
        val maxRaw = s.maxOfOrNull {
            maxOf(if (showD) u.conv(it.down) else 0f, if (showU) u.conv(it.up) else 0f)
        } ?: 1f
        val step = if (scale > 0) u.conv(scale.toFloat()) / 4f else niceStep(maxRaw)
        val maxV = step * 4f
        val grid = stroke(0xFFDDE3E8.toInt(), sw * 0.5f)
        val yLabel = text(GREY, fs, align = Paint.Align.RIGHT)
        for (i in 0..4) {
            val y = r.bottom - r.height() * i / 4f
            c.drawLine(r.left, y, r.right, y, grid)
            c.drawText(axis(step * i), r.left - fs * 0.5f, y + fs * 0.35f, yLabel)
        }
        val n = s.size
        if (n < 2) return
        val xLabel = text(GREY, fs, align = Paint.Align.CENTER)
        for (i in 0..5) {
            val x = r.left + r.width() * i / 5f
            c.drawText(fmtTime((n * i / 5f).toInt()), x, r.bottom + fs * 1.5f, xLabel)
        }

        fun series(sel: (Sample) -> Float, color: Int) {
            val line = Path()
            s.forEachIndexed { i, smp ->
                val x = r.left + r.width() * i / (n - 1)
                val y = r.bottom - r.height() * (u.conv(sel(smp)) / maxV).coerceIn(0f, 1f)
                if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
            }
            val area = Path(line)
            area.lineTo(r.right, r.bottom)
            area.lineTo(r.left, r.bottom)
            area.close()
            c.drawPath(area, fill(color).apply { alpha = 35 })
            c.drawPath(line, stroke(color, sw).apply {
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            })
        }
        if (showD) series({ it.down }, DOWN)
        if (showU) series({ it.up }, UP)

        if (mark30 && n > 31) {
            val x = r.left + r.width() * 29f / (n - 1)
            c.drawLine(x, r.top, x, r.bottom, stroke(GREY, sw * 0.6f).apply {
                pathEffect = DashPathEffect(floatArrayOf(sw * 4, sw * 4), 0f)
            })
            c.drawText("30 s", x + fs * 0.3f, r.top + fs, text(GREY, fs))
        }
    }

    // ---------------------------------------------------------------- PNG

    private fun statCard(c: Canvas, rect: RectF, label: String, color: Int, s: Stats, u: SpeedUnit) {
        c.drawRoundRect(rect, 28f, 28f, fill(WHITE))
        c.drawRoundRect(rect, 28f, 28f, stroke(LINE, 2f))
        val x = rect.left + 36f
        c.drawText(label, x, rect.top + 66f, text(color, 36f, true))
        c.drawText(u.fmt(s.avg), x, rect.top + 182f, text(NAVY, 92f, true))
        c.drawText("${u.label} average", x, rect.top + 228f, text(GREY, 28f))
        val x2 = rect.left + rect.width() / 2f
        c.drawText("Min", x, rect.top + 290f, text(GREY, 26f))
        c.drawText(u.fmt(s.min), x, rect.top + 334f, text(NAVY, 40f, true))
        c.drawText("Max", x2, rect.top + 290f, text(GREY, 26f))
        c.drawText(u.fmt(s.max), x2, rect.top + 334f, text(NAVY, 40f, true))
    }

    private fun infoCard(c: Canvas, rect: RectF, label: String, value: String) {
        c.drawRoundRect(rect, 24f, 24f, fill(WHITE))
        c.drawRoundRect(rect, 24f, 24f, stroke(LINE, 2f))
        c.drawText(label, rect.left + 28f, rect.top + 46f, text(GREY, 26f))
        c.drawText(value, rect.left + 28f, rect.top + 100f, text(NAVY, 38f, true))
    }

    fun summaryPng(ctx: Context, r: Report): File {
        val u = Prefs.unit(ctx)
        val scale = Prefs.scale(ctx)
        val w = 1080
        val h = 1500
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(LIGHT)
        val showD = r.mode != Mode.UP
        val showU = r.mode != Mode.DOWN

        c.drawRect(0f, 0f, w.toFloat(), 215f, fill(NAVY))
        c.drawText("Sustained Speed Test", 50f, 90f, text(WHITE, 56f, true))
        c.drawText(
            "${date(r.id)}  |  ${r.mode.label}  |  ${fmtDur(r.ranSec)} of ${fmtDur(r.plannedSec)}",
            50f, 140f, text(0xFFCFE0EE.toInt(), 30f)
        )
        val srv = if (r.server.isBlank()) "-" else cut(r.server, 38)
        c.drawText("Server: $srv  |  ${r.conn.label} connection", 50f, 186f, text(0xFFCFE0EE.toInt(), 28f))

        val v = verdict(r, u)
        c.drawRoundRect(RectF(40f, 240f, 1040f, 440f), 28f, 28f, fill(v.color))
        c.drawText(v.title, 80f, 306f, text(WHITE, 44f, true))
        wrapped(c, v.text, 80f, 326f, 920, text(WHITE, 30f))

        if (showD && showU) {
            statCard(c, RectF(40f, 470f, 530f, 830f), "Download", DOWN, r.downStats(), u)
            statCard(c, RectF(550f, 470f, 1040f, 830f), "Upload", UP, r.upStats(), u)
        } else if (showD) {
            statCard(c, RectF(40f, 470f, 1040f, 830f), "Download", DOWN, r.downStats(), u)
        } else {
            statCard(c, RectF(40f, 470f, 1040f, 830f), "Upload", UP, r.upStats(), u)
        }

        val (avgP, minP, maxP) = pingStats(r)
        infoCard(c, RectF(40f, 855f, 360f, 985f), "Data used", fmtBytes(r.totalBytes))
        infoCard(c, RectF(380f, 855f, 700f, 985f), "Avg ping (loaded)", "$avgP ms")
        infoCard(c, RectF(720f, 855f, 1040f, 985f), "Ping range", "$minP-$maxP ms")

        c.drawRoundRect(RectF(40f, 1010f, 1040f, 1445f), 28f, 28f, fill(WHITE))
        c.drawRoundRect(RectF(40f, 1010f, 1040f, 1445f), 28f, 28f, stroke(LINE, 2f))
        c.drawText("Speed over time", 70f, 1068f, text(NAVY, 34f, true))
        var lx = 560f
        if (showD) {
            c.drawCircle(lx, 1056f, 9f, fill(DOWN))
            c.drawText("Download", lx + 20f, 1066f, text(GREY, 26f))
            lx += 210f
        }
        if (showU) {
            c.drawCircle(lx, 1056f, 9f, fill(UP))
            c.drawText("Upload", lx + 20f, 1066f, text(GREY, 26f))
        }
        drawChart(c, RectF(140f, 1110f, 1000f, 1345f), r.samples, showD, showU, true, 24f, 4f, u, scale)
        c.drawText(
            "${u.label}  |  dashed line = 30 seconds",
            540f, 1425f, text(GREY, 22f, align = Paint.Align.CENTER)
        )
        c.drawText("Generated by Sustained Speed Test", 540f, 1482f, text(GREY, 24f, align = Paint.Align.CENTER))

        val f = File(ctx.cacheDir, "speedtest_${r.id}_summary.png")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return f
    }

    // ---------------------------------------------------------------- PDF

    private fun segAvg(r: Report, u: SpeedUnit, sel: (Sample) -> Float, from: Int, to: Int): String {
        val l = r.samples.filter { it.t in from..to }.map(sel)
        return if (l.size < 10) "-" else u.fmt(l.average().toFloat())
    }

    fun pdf(ctx: Context, r: Report): File {
        val u = Prefs.unit(ctx)
        val scale = Prefs.scale(ctx)
        val pw = 595
        val ph = 842
        val doc = PdfDocument()
        var pageNo = 0
        lateinit var page: PdfDocument.Page
        lateinit var c: Canvas
        val showD = r.mode != Mode.UP
        val showU = r.mode != Mode.DOWN

        fun newPage(first: Boolean) {
            if (pageNo > 0) doc.finishPage(page)
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, pageNo).create())
            c = page.canvas
            if (first) {
                c.drawRect(0f, 0f, pw.toFloat(), 70f, fill(NAVY))
                c.drawText("Internet Speed Test Report", 30f, 33f, text(WHITE, 22f, true))
                c.drawText(
                    "${date(r.id)}  |  ${r.mode.label}  |  Sustained Speed Test",
                    30f, 55f, text(0xFFCFE0EE.toInt(), 11f)
                )
            } else {
                c.drawRect(0f, 0f, pw.toFloat(), 44f, fill(NAVY))
                c.drawText("Network conditions and detailed speed log", 30f, 28f, text(WHITE, 15f, true))
            }
            c.drawText("Page $pageNo", pw / 2f, 825f, text(GREY, 9f, align = Paint.Align.CENTER))
        }

        newPage(true)
        val h1 = text(NAVY, 14f, true)
        val lab = text(GREY, 10f)
        val value = text(0xFF111111.toInt(), 12f, true)

        // summary grid
        c.drawText("Test summary", 30f, 100f, h1)
        val (avgP, minP, maxP) = pingStats(r)
        val items = listOf(
            "Test type" to r.mode.label, "Connection type" to r.conn.label,
            "Server" to (if (r.server.isBlank()) "-" else cut(r.server, 36)), "Data transferred" to fmtBytes(r.totalBytes),
            "Planned duration" to fmtDur(r.plannedSec), "Actual duration" to fmtDur(r.ranSec),
            "Average ping (under load)" to "$avgP ms", "Ping min / max" to "$minP / $maxP ms"
        )
        items.forEachIndexed { i, (k, v) ->
            val x = if (i % 2 == 0) 30f else 310f
            val y = 124f + (i / 2) * 34f
            c.drawText(k, x, y, lab)
            c.drawText(v, x, y + 15f, value)
        }

        // statistics table
        c.drawText("Speed statistics (${u.label})", 30f, 272f, h1)
        c.drawRoundRect(RectF(30f, 282f, 565f, 308f), 6f, 6f, fill(LIGHT))
        val xs = floatArrayOf(38f, 150f, 232f, 314f, 396f, 480f)
        listOf("", "Average", "Min", "Max", "First 30 s", "After 40 s").forEachIndexed { i, s ->
            c.drawText(s, xs[i], 299f, text(GREY, 10f, true))
        }
        var ty = 330f
        fun row(name: String, color: Int, st: Stats, sel: (Sample) -> Float) {
            c.drawText(name, xs[0], ty, text(color, 12f, true))
            c.drawText(u.fmt(st.avg), xs[1], ty, value)
            c.drawText(u.fmt(st.min), xs[2], ty, value)
            c.drawText(u.fmt(st.max), xs[3], ty, value)
            c.drawText(segAvg(r, u, sel, 1, 30), xs[4], ty, value)
            c.drawText(segAvg(r, u, sel, 41, Int.MAX_VALUE), xs[5], ty, value)
            ty += 26f
        }
        if (showD) row("Download", DOWN, r.downStats()) { it.down }
        if (showU) row("Upload", UP, r.upStats()) { it.up }

        // verdict box
        val v = verdict(r, u)
        c.drawRoundRect(RectF(30f, 386f, 565f, 458f), 10f, 10f, fill(v.color))
        c.drawText(v.title, 44f, 410f, text(WHITE, 14f, true))
        wrapped(c, v.text, 44f, 418f, 505, text(WHITE, 11f))

        // graph
        c.drawText("Speed graph", 30f, 492f, h1)
        var lx = 400f
        if (showD) {
            c.drawCircle(lx, 487f, 4f, fill(DOWN)); c.drawText("Download", lx + 8f, 491f, lab); lx += 80f
        }
        if (showU) {
            c.drawCircle(lx, 487f, 4f, fill(UP)); c.drawText("Upload", lx + 8f, 491f, lab)
        }
        drawChart(c, RectF(70f, 512f, 555f, 752f), r.samples, showD, showU, true, 9f, 1.6f, u, scale)
        c.drawText(
            "Speed in ${u.label} over time (min:sec). Dashed line marks 30 seconds. Average/min/max skip the first $WARMUP_SEC s.",
            30f, 794f, text(GREY, 9f)
        )
        c.drawText(
            "Results depend on the test server. Repeat the test at different times for confidence.",
            30f, 809f, text(GREY, 9f)
        )

        // page 2: network conditions + detailed log
        val interval = when {
            r.ranSec <= 300 -> 10
            r.ranSec <= 1800 -> 30
            else -> 60
        }
        val cols = mutableListOf<Pair<String, (List<Sample>) -> String>>()
        fun col(name: String, f: (List<Sample>) -> String) { cols.add(name to f) }
        col("Time") { ch -> fmtTime(ch.first().t - 1) + " - " + fmtTime(ch.last().t) }
        if (showD) {
            col("Down avg") { ch -> u.fmt(ch.map { it.down }.average().toFloat()) }
            col("Down min") { ch -> u.fmt(ch.minOf { it.down }) }
            col("Down max") { ch -> u.fmt(ch.maxOf { it.down }) }
        }
        if (showU) {
            col("Up avg") { ch -> u.fmt(ch.map { it.up }.average().toFloat()) }
            col("Up min") { ch -> u.fmt(ch.minOf { it.up }) }
            col("Up max") { ch -> u.fmt(ch.maxOf { it.up }) }
        }
        col("Ping ms") { ch ->
            val p = ch.map { it.ping }.filter { it > 0 }
            if (p.isEmpty()) "-" else p.average().toInt().toString()
        }
        val cw = 535f / cols.size

        fun tableHeader(y: Float) {
            c.drawRoundRect(RectF(30f, y - 14f, 565f, y + 6f), 4f, 4f, fill(LIGHT))
            cols.forEachIndexed { i, (name, _) -> c.drawText(name, 36f + i * cw, y, text(GREY, 9f, true)) }
        }

        newPage(false)
        var y = 72f
        r.snap?.let { sn ->
            c.drawText("Network conditions at start of test", 30f, y, h1)
            val rows = ArrayList<Pair<String, String>>()
            if (sn.band.isNotBlank()) {
                rows += "Wi-Fi band" to "${sn.band}, channel ${sn.channel}"
                rows += "Signal" to "${sn.rssi} dBm"
                rows += "Link speed" to "Down ${sn.linkDown} / Up ${sn.linkUp} Mbps"
            } else {
                rows += "Connection" to "Not on Wi-Fi"
            }
            rows += "Gateway ping" to msOrDash(sn.gwMs)
            rows += "Internet ping" to msOrDash(sn.netMs)
            rows += "Jitter" to msOrDash(sn.jitter)
            rows += "Packet loss" to (if (sn.loss < 0) "-" else "${sn.loss} %")
            rows += "DNS lookup" to msOrDash(sn.dnsMs)
            rows.forEachIndexed { i, (k, vv) ->
                val x = if (i % 2 == 0) 30f else 310f
                val yy = y + 24f + (i / 2) * 34f
                c.drawText(k, x, yy, lab)
                c.drawText(vv, x, yy + 15f, value)
            }
            y += 24f + ((rows.size + 1) / 2) * 34f + 14f
        }

        c.drawText("Average of every $interval seconds (${u.label})", 30f, y, h1)
        y += 24f
        tableHeader(y)
        y += 20f
        val body = text(0xFF111111.toInt(), 9f)
        r.samples.chunked(interval).forEachIndexed { idx, chunk ->
            if (y > 800f) {
                newPage(false)
                y = 70f
                tableHeader(y)
                y += 20f
            }
            if (idx % 2 == 1) c.drawRect(30f, y - 11f, 565f, y + 5f, fill(0xFFFAFBFC.toInt()))
            cols.forEachIndexed { i, (_, f) -> c.drawText(f(chunk), 36f + i * cw, y, body) }
            y += 16f
        }
        doc.finishPage(page)

        val f = File(ctx.cacheDir, "speedtest_${r.id}.pdf")
        FileOutputStream(f).use { doc.writeTo(it) }
        doc.close()
        return f
    }

    fun share(ctx: Context, f: File, mime: String, title: String) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val i = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri("", uri)
        ctx.startActivity(Intent.createChooser(i, title))
    }
}
