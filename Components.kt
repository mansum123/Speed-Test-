@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.smartnet.speedtest

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val DownColor = Color(0xFF2E7DFF)
val UpColor = Color(0xFF22B573)
val GoodColor = Color(0xFF22B573)
val OkColor = Color(0xFF9BC53D)
val WarnColor = Color(0xFFE08A00)
val BadColor = Color(0xFFD32F2F)

/** Row of selectable chips that wraps onto the next line instead of being cut off. */
@Composable
fun <T> Chips(items: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items.forEach { item ->
            FilterChip(selected = item == selected, onClick = { onSelect(item) }, label = { Text(label(item)) })
        }
    }
}

@Composable
fun BigSpeed(label: String, value: Float, color: Color, unit: SpeedUnit, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, color = color, fontWeight = FontWeight.SemiBold)
            Text(unit.fmt(value), fontSize = 44.sp, fontWeight = FontWeight.Bold)
            Text(unit.label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun Legend(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

/** Download / upload chart. scale = 0 means automatic, otherwise 100 / 500 / 1000 Mbps full scale. */
@Composable
fun SpeedChart(
    samples: List<Sample>,
    showDown: Boolean,
    showUp: Boolean,
    unit: SpeedUnit = SpeedUnit.MBPS,
    scale: Int = 0,
    mark30: Boolean = false
) {
    val auto = (samples.maxOfOrNull {
        maxOf(if (showDown) unit.conv(it.down) else 0f, if (showUp) unit.conv(it.up) else 0f)
    } ?: 1f).coerceAtLeast(1f) * 1.1f
    val maxV = if (scale > 0) unit.conv(scale.toFloat()) else auto
    val grid = MaterialTheme.colorScheme.outlineVariant

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showDown) Legend("Download", DownColor)
            if (showUp) Legend("Upload", UpColor)
            Text(
                "Scale 0 - ${maxV.toInt()} ${unit.label}" + if (scale == 0) " (auto)" else "",
                style = MaterialTheme.typography.bodySmall
            )
        }
        Canvas(Modifier.fillMaxWidth().height(200.dp)) {
            val w = size.width
            val h = size.height
            for (i in 0..4) {
                val y = h * i / 4f
                drawLine(grid, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
            }
            val n = samples.size
            if (n < 2) return@Canvas

            fun line(sel: (Sample) -> Float, c: Color) {
                val p = Path()
                samples.forEachIndexed { i, s ->
                    val x = w * i / (n - 1)
                    val y = h - h * (unit.conv(sel(s)) / maxV).coerceIn(0f, 1f)
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                drawPath(
                    p, c,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
            if (showDown) line({ it.down }, DownColor)
            if (showUp) line({ it.up }, UpColor)

            if (mark30 && n > 31) {
                val x = w * 29f / (n - 1)
                drawLine(
                    Color.Gray, Offset(x, 0f), Offset(x, h), strokeWidth = 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 12f))
                )
            }
        }
    }
}

data class ChartSeries(val name: String, val color: Color, val values: List<Float?>)

/** Generic fixed-range line chart (used for signal dBm and latency ms). Null values leave a gap. */
@Composable
fun LineChart(
    series: List<ChartSeries>,
    yMin: Float,
    yMax: Float,
    unitLabel: String,
    height: Dp = 180.dp,
    maxPoints: Int = 120
) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            series.forEach { Legend(it.name, it.color) }
            Text(unitLabel, style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth().height(height), verticalAlignment = Alignment.Top) {
            Canvas(Modifier.weight(1f).height(height)) {
                val w = size.width
                val h = size.height
                for (i in 0..4) {
                    val y = h * i / 4f
                    drawLine(grid, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
                }
                series.forEach { s ->
                    val v = s.values.takeLast(maxPoints)
                    if (v.size < 2) return@forEach
                    val p = Path()
                    var pen = false
                    v.forEachIndexed { i, y0 ->
                        if (y0 == null) {
                            pen = false
                        } else {
                            val x = w * i / (v.size - 1)
                            val y = h - h * ((y0 - yMin) / (yMax - yMin)).coerceIn(0f, 1f)
                            if (!pen) {
                                p.moveTo(x, y)
                                pen = true
                            } else {
                                p.lineTo(x, y)
                            }
                        }
                    }
                    drawPath(
                        p, s.color,
                        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
            }
            Column(
                Modifier.height(height).padding(start = 6.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                for (i in 0..4) {
                    val v = yMax - (yMax - yMin) * i / 4f
                    Text(v.toInt().toString(), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
fun Cell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    }
}

@Composable
private fun StatRow(title: String, color: Color, s: Stats, cur: Float?, unit: SpeedUnit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = color, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (cur != null) Cell("Current", unit.fmt(cur))
                Cell("Average", unit.fmt(s.avg))
                Cell("Min", unit.fmt(s.min))
                Cell("Max", unit.fmt(s.max))
            }
        }
    }
}

@Composable
fun StatsBlock(
    samples: List<Sample>,
    mode: Mode,
    unit: SpeedUnit,
    curDown: Float? = null,
    curUp: Float? = null
) {
    val body = steady(samples)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (mode != Mode.UP) StatRow("Download (${unit.label})", DownColor, stats(body.map { it.down }), curDown, unit)
        if (mode != Mode.DOWN) StatRow("Upload (${unit.label})", UpColor, stats(body.map { it.up }), curUp, unit)
        Text(
            "Average, min and max skip the first $WARMUP_SEC seconds (TCP warm-up).",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
