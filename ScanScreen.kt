@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.smartnet.speedtest

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
private fun ApRow(a: Ap) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(a.ssid, fontWeight = FontWeight.Bold)
            Text(
                "Ch ${a.channel}  |  ${a.band}" + (if (a.width.isNotBlank()) "  |  ${a.width}" else "") + "  |  ${a.security}",
                style = MaterialTheme.typography.bodySmall
            )
        }
        Text("${a.rssi} dBm", fontWeight = FontWeight.Bold, color = rssiColor(a.rssi))
    }
}

@Composable
private fun ChannelBars(title: String, loads: List<ChanLoad>) {
    val maxScore = (loads.maxOfOrNull { it.score } ?: 1f).coerceAtLeast(1f)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold)
        Row(
            Modifier.fillMaxWidth().height(120.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            loads.forEach { l ->
                val frac = (l.score / maxScore).coerceIn(0f, 1f)
                val color = when {
                    l.score < 0.6f -> GoodColor
                    l.score < 1.8f -> WarnColor
                    else -> BadColor
                }
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom
                ) {
                    Text(if (l.count > 0) l.count.toString() else "", style = MaterialTheme.typography.labelSmall)
                    Box(Modifier.fillMaxWidth(0.7f).height((4f + 80f * frac).dp).background(color))
                    Text(l.channel.toString(), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
fun ScanScreen() {
    val ctx = LocalContext.current
    var aps by remember { mutableStateOf<List<Ap>>(emptyList()) }
    var granted by remember { mutableStateOf(Loc.granted(ctx)) }
    var filter by remember { mutableStateOf("All") }
    var view by remember { mutableStateOf("SSID Scan") }
    var stamp by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }
    val df = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = Loc.granted(ctx)
    }

    LaunchedEffect(granted, tick) {
        if (granted) {
            withContext(Dispatchers.IO) { WifiTool.startScan(ctx) }
            delay(2500)
            aps = withContext(Dispatchers.IO) { WifiTool.scan(ctx) }
            stamp = df.format(Date())
            delay(25_000)
            tick++
        }
    }

    val shown = if (filter == "All") aps else aps.filter { it.band == filter }
    val connected = aps.firstOrNull { it.connected }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Wi-Fi Scan", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = { tick++ }, enabled = granted) { Text("Scan now") }
        }

        if (!granted) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Location permission is needed to scan Wi-Fi networks.", fontWeight = FontWeight.SemiBold)
                    Text("This is an Android rule. The app does not store or send your location.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = {
                        permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }) { Text("Allow location") }
                }
            }
        } else {
            if (!Loc.enabled(ctx)) {
                Text("Location is switched off on this phone. Turn it on, otherwise Android returns an empty list.", color = MaterialTheme.colorScheme.error)
            }
            if (stamp.isNotBlank()) {
                Text(
                    "Updated $stamp. Android allows only about 4 scans every 2 minutes, so the list refreshes slowly.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            val n24 = aps.count { it.band == "2.4 GHz" }
            val n5 = aps.count { it.band == "5 GHz" }
            val n6 = aps.count { it.band == "6 GHz" }
            val filters = buildList {
                add("All")
                add("2.4 GHz")
                add("5 GHz")
                if (n6 > 0) add("6 GHz")
            }
            Chips(
                filters, filter,
                {
                    when (it) {
                        "All" -> "All (${aps.size})"
                        "2.4 GHz" -> "2.4 GHz ($n24)"
                        "5 GHz" -> "5 GHz ($n5)"
                        else -> "6 GHz ($n6)"
                    }
                },
                { filter = it }
            )

            connected?.let { c ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                        Text("CONNECTED", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        ApRow(c)
                    }
                }
            }

            Chips(listOf("SSID Scan", "Channel Health"), view, { it }, { view = it })

            if (view == "SSID Scan") {
                if (shown.isEmpty()) {
                    Text("No networks found yet. Wait a few seconds or tap Scan now.")
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                        shown.filter { !it.connected }.forEach { ApRow(it) }
                    }
                }
            } else {
                val l24 = WifiTool.load24(aps)
                val best24 = listOf(1, 6, 11).minByOrNull { ch -> l24.first { it.channel == ch }.score } ?: 1
                ChannelBars("2.4 GHz: networks overlapping each channel", l24)
                Text(
                    "Best 2.4 GHz channel right now: $best24 (only 1, 6 and 11 do not overlap each other).",
                    fontWeight = FontWeight.SemiBold
                )
                val l5 = WifiTool.load5(aps)
                if (l5.isNotEmpty()) {
                    ChannelBars("5 GHz: networks on each channel", l5)
                    val cand = listOf(36, 40, 44, 48, 149, 153, 157, 161)
                    val best5 = cand.minByOrNull { ch -> l5.firstOrNull { it.channel == ch }?.score ?: 0f } ?: 36
                    Text("Least busy 5 GHz channel: $best5.", fontWeight = FontWeight.SemiBold)
                }
                Text(
                    "Bar height shows how crowded a channel is (more and stronger networks = higher). " +
                        "Green is clear, orange is busy, red is crowded. Wide channels are not counted, so treat this as a guide.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
