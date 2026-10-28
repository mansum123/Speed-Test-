@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.smartnet.speedtest

import android.content.Intent
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.ceil

private val GwColor = Color(0xFF4FC3F7)
private val NetColor = Color(0xFF1E66F5)

private fun msColor(ms: Float): Color = when {
    ms <= 0f -> Color.Gray
    ms < 50f -> GoodColor
    ms < 100f -> WarnColor
    else -> BadColor
}

@Composable
private fun PingCard(title: String, host: String, s: PStat) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(title, fontWeight = FontWeight.Bold)
                    Text(host, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    s.cur?.let { "${it.toInt()} ms" } ?: "lost",
                    fontWeight = FontWeight.Bold, color = msColor(s.cur ?: 0f),
                    style = MaterialTheme.typography.titleLarge
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Cell("Avg", "${s.avg.toInt()} ms")
                Cell("Min", "${s.min.toInt()} ms")
                Cell("Max", "${s.max.toInt()} ms")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Cell("Jitter", f1(s.jitter) + " ms")
                Cell("Packet loss", f1(s.loss) + " %")
                Cell("Sent", s.sent.toString())
            }
        }
    }
}

@Composable
fun LatencyScreen() {
    val ctx = LocalContext.current
    val st by LatencyRunner.state.collectAsStateWithLifecycle()
    var target by remember { mutableStateOf("8.8.8.8") }
    var custom by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf(1) }

    val host = if (target == "custom") custom.trim() else target
    val validHost = host.isNotEmpty() && host.all { it.isLetterOrDigit() || it == '.' || it == '-' }

    val gw = pstat(st.gw)
    val net = pstat(st.net)
    val dnsAvg = if (st.dns.isEmpty()) 0 else st.dns.average().toInt()
    val dnsLast = st.dns.lastOrNull() ?: 0
    val dnsMax = st.dns.maxOrNull() ?: 0

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Latency", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

        if (!st.running) {
            Text("Internet target", fontWeight = FontWeight.SemiBold)
            Chips(listOf("8.8.8.8", "1.1.1.1", "custom"), target, { if (it == "custom") "Custom" else it }, { target = it })
            if (target == "custom") {
                OutlinedTextField(
                    value = custom, onValueChange = { custom = it },
                    label = { Text("Host or IP (e.g. google.com)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Text("Duration", fontWeight = FontWeight.SemiBold)
            Chips(listOf(1, 5, 15, 60), minutes, { fmtMin(it) }, { minutes = it })
            Button(
                enabled = validHost,
                onClick = { LatencyRunner.start(ctx, host, minutes * 60) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Start latency test") }
            Text(
                "Keep the app open and the screen on during this test.",
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            LinearProgressIndicator(
                progress = { if (st.planned == 0) 0f else (st.elapsed.toFloat() / st.planned).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
            Text("${fmtTime(st.elapsed)} of ${fmtDur(st.planned)}")
            Button(
                onClick = { LatencyRunner.stop() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) { Text("Stop") }
        }

        if (st.net.isNotEmpty()) {
            if (st.gwHost.isNotBlank()) PingCard("Network gateway", st.gwHost, gw)
            else Text("No Wi-Fi gateway found (mobile data?). Only the internet is tested.", style = MaterialTheme.typography.bodySmall)
            PingCard("Internet", st.netHost, net)

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("DNS latency", fontWeight = FontWeight.Bold)
                    if (st.dns.isEmpty()) {
                        Text("Measuring...", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Cell("Last", "$dnsLast ms")
                            Cell("Avg", "$dnsAvg ms")
                            Cell("Max", "$dnsMax ms")
                        }
                        Text(
                            if (dnsAvg > 100 && net.avg in 1f..80f)
                                "DNS is slow compared to the ping. Websites may open slowly even though the internet is fast. Try another DNS server."
                            else "Time for one uncached name lookup.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            val top = maxOf(
                st.gw.filterNotNull().maxOrNull() ?: 0f,
                st.net.filterNotNull().maxOrNull() ?: 0f, 50f
            )
            val yMax = (ceil(top / 25f) * 25f).coerceAtMost(1000f)
            val series = buildList {
                if (st.gwHost.isNotBlank()) add(ChartSeries("Gateway", GwColor, st.gw))
                add(ChartSeries("Internet", NetColor, st.net))
            }
            LineChart(series, 0f, yMax, "ms", maxPoints = 150)

            OutlinedButton(
                onClick = {
                    val t = buildString {
                        append("Latency test (${fmtDur(st.elapsed)})\n")
                        if (st.gwHost.isNotBlank()) {
                            append("Gateway ${st.gwHost}: avg ${gw.avg.toInt()} ms, max ${gw.max.toInt()} ms, jitter ${f1(gw.jitter)} ms, loss ${f1(gw.loss)} %\n")
                        }
                        append("Internet ${st.netHost}: avg ${net.avg.toInt()} ms, max ${net.max.toInt()} ms, jitter ${f1(net.jitter)} ms, loss ${f1(net.loss)} %\n")
                        append("DNS lookup: avg $dnsAvg ms, max $dnsMax ms")
                    }
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
