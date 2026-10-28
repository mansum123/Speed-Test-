@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.smartnet.speedtest

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

sealed interface Screen {
    object Home : Screen
    object Hist : Screen
    object Servers : Screen
    data class Rep(val r: Report) : Screen
}

enum class Tab(val label: String, val icon: String) {
    SPEED("Speed", "⚡"), SIGNAL("Signal", "📶"), LATENCY("Latency", "⏱"), SCAN("Scan", "📡"), VIDEO("Video", "▶")
}

@Composable
fun App() {
    val live by TestState.live.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(Tab.SPEED) }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }

    LaunchedEffect(live.finished) {
        live.finished?.let {
            tab = Tab.SPEED
            screen = Screen.Rep(it)
            TestState.live.update { s -> s.copy(finished = null) }
        }
    }
    BackHandler(enabled = tab == Tab.SPEED && screen != Screen.Home) { screen = Screen.Home }
    BackHandler(enabled = tab != Tab.SPEED) { tab = Tab.SPEED }

    val showBar = tab != Tab.SPEED || screen == Screen.Home

    Scaffold(
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    Tab.values().forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Text(t.icon) },
                            label = { Text(t.label) }
                        )
                    }
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                Tab.SPEED -> when (val s = screen) {
                    Screen.Home -> HomeScreen(
                        live,
                        onHistory = { screen = Screen.Hist },
                        onServer = { screen = Screen.Servers }
                    )
                    Screen.Hist -> HistoryScreen(onOpen = { screen = Screen.Rep(it) }, onBack = { screen = Screen.Home })
                    Screen.Servers -> ServerScreen(onBack = { screen = Screen.Home })
                    is Screen.Rep -> ReportScreen(s.r, onBack = { screen = Screen.Home })
                }
                Tab.SIGNAL -> SignalScreen()
                Tab.LATENCY -> LatencyScreen()
                Tab.SCAN -> ScanScreen()
                Tab.VIDEO -> VideoScreen()
            }
        }
    }
}

@Composable
fun HomeScreen(live: Live, onHistory: () -> Unit, onServer: () -> Unit) {
    val ctx = LocalContext.current
    var preset by remember { mutableStateOf(15) }   // minutes, -1 = custom
    var custom by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(Mode.BOTH) }
    var unit by remember { mutableStateOf(Prefs.unit(ctx)) }
    var scale by remember { mutableStateOf(Prefs.scale(ctx)) }
    var conn by remember { mutableStateOf(Prefs.conn(ctx)) }
    val server = remember { Prefs.server(ctx) }
    var cfLabel by remember { mutableStateOf("") }
    val minutes = if (preset == -1) (custom.toIntOrNull() ?: 0) else preset

    LaunchedEffect(server.kind) {
        if (server.kind == "cf") cfLabel = Servers.cf()?.label ?: ""
    }

    val startTest = {
        ContextCompat.startForegroundService(
            ctx,
            Intent(ctx, TestService::class.java)
                .putExtra("mode", mode.name)
                .putExtra("sec", minutes * 60)
        )
        Unit
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        startTest()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Sustained Speed Test", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            TextButton(onClick = onHistory, enabled = !live.running) { Text("History") }
        }

        if (live.running) {
            val showD = live.mode != Mode.UP
            val showU = live.mode != Mode.DOWN
            if (live.status.isNotBlank()) {
                Text(live.status, fontWeight = FontWeight.SemiBold)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (live.server.isNotBlank()) {
                Text("Server: ${live.server}   |   ${live.conn.label} connection", style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (showD) BigSpeed("Download", live.down, DownColor, unit, Modifier.weight(1f))
                if (showU) BigSpeed("Upload", live.up, UpColor, unit, Modifier.weight(1f))
            }
            LinearProgressIndicator(
                progress = { if (live.plannedSec == 0) 0f else live.elapsed.toFloat() / live.plannedSec },
                modifier = Modifier.fillMaxWidth()
            )
            Text("${fmtTime(live.elapsed)} of ${fmtDur(live.plannedSec)}   |   Data used: ${fmtBytes(live.totalBytes)}")
            SpeedChart(live.samples.takeLast(120), showD, showU, unit, scale)
            StatsBlock(live.samples, live.mode, unit, live.down, live.up)
            Text("Ping (under load): ${live.ping} ms    Jitter: ${live.jitter} ms")
            Button(
                onClick = {
                    ctx.startService(Intent(ctx, TestService::class.java).setAction(TestService.ACTION_STOP))
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) { Text("Stop test") }
        } else {
            live.error?.let {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(it, Modifier.padding(14.dp), fontWeight = FontWeight.SemiBold)
                }
            }

            Card(Modifier.fillMaxWidth().clickable { onServer() }) {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Test server", style = MaterialTheme.typography.bodySmall)
                        if (server.kind == "cf") {
                            Text(cfLabel.ifBlank { "Cloudflare (automatic)" }, fontWeight = FontWeight.Bold)
                            Text("Automatic: Cloudflare picks the nearest data centre", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text(server.name, fontWeight = FontWeight.Bold)
                            Text(
                                listOf(server.city, server.country).filter { it.isNotBlank() }.joinToString(", ") +
                                    if (server.distKm >= 0) "  ·  ${server.distKm} km" else "",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Text("Change", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
            }

            Text("Duration", fontWeight = FontWeight.SemiBold)
            Chips(
                listOf(5, 15, 30, 60, -1), preset,
                { if (it == -1) "Custom" else fmtMin(it) },
                { preset = it }
            )
            if (preset == -1) {
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it.filter { c -> c.isDigit() }.take(4) },
                    label = { Text("Minutes (minimum 1)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (minutes >= 1) Text("Selected: ${fmtMin(minutes)}", style = MaterialTheme.typography.bodySmall)

            Text("Test type", fontWeight = FontWeight.SemiBold)
            Chips(Mode.values().toList(), mode, { it.label }, { mode = it })

            Text("Connection type", fontWeight = FontWeight.SemiBold)
            Chips(ConnType.values().toList(), conn, { it.label }, { conn = it; Prefs.setConn(ctx, it) })
            Text(
                if (conn == ConnType.MULTI) "Multi: ${ConnType.MULTI.down} parallel connections, like Ookla. Shows the full line speed."
                else "Single: one connection, like a normal file download. Usually lower on fast lines.",
                style = MaterialTheme.typography.bodySmall
            )

            Text("Units", fontWeight = FontWeight.SemiBold)
            Chips(SpeedUnit.values().toList(), unit, { it.label }, { unit = it; Prefs.setUnit(ctx, it) })

            Text("Scale", fontWeight = FontWeight.SemiBold)
            Chips(listOf(0, 100, 500, 1000), scale, { if (it == 0) "Auto" else it.toString() }, { scale = it; Prefs.setScale(ctx, it) })

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Data usage warning", fontWeight = FontWeight.Bold)
                    Text(
                        "This test transfers data non-stop. At 50 Mbps, one hour of download uses about 22 GB. " +
                            "Upload and Both modes use even more. Check your package before starting."
                    )
                }
            }

            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED
                    ) {
                        permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        startTest()
                    }
                },
                enabled = minutes >= 1,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Start test") }

            Text(
                "Keep Wi-Fi/mobile data on. The test keeps running with the screen off.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun ReportScreen(r: Report, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var unit by remember { mutableStateOf(Prefs.unit(ctx)) }
    val scale = remember { Prefs.scale(ctx) }
    val showD = r.mode != Mode.UP
    val showU = r.mode != Mode.DOWN
    val b = r.boost()
    val df = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Report", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Text(df.format(Date(r.id)) + "  |  " + r.mode.label + "  |  " + r.conn.label + " connection")
        if (r.server.isNotBlank()) Text("Server: ${r.server}", fontWeight = FontWeight.SemiBold)
        Text("Ran ${fmtDur(r.ranSec)} of ${fmtDur(r.plannedSec)} planned   |   Data: ${fmtBytes(r.totalBytes)}")
        Chips(SpeedUnit.values().toList(), unit, { it.label }, { unit = it; Prefs.setUnit(ctx, it) })

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Boost check", fontWeight = FontWeight.Bold)
                if (b == null) {
                    Text("Test was too short for a boost check. Run at least 2 min.")
                } else {
                    Text("First 30 seconds average: ${unit.fmt(b.first)} ${unit.label}")
                    Text("After 40 seconds average: ${unit.fmt(b.rest)} ${unit.label}")
                    val pct = b.dropPct.toInt()
                    Text(
                        when {
                            b.dropPct >= 25f -> "Speed fell by $pct% after the first 30 seconds. This pattern matches a temporary boost."
                            b.dropPct >= 10f -> "Speed fell by $pct%. This may be a boost or normal congestion. Repeat the test at another time."
                            else -> "Speed stayed steady. No sign of a temporary boost."
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Results depend on the test server. Repeat at different times for confidence.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        r.snap?.let { sn ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Network conditions at start", fontWeight = FontWeight.Bold)
                    if (sn.band.isNotBlank()) {
                        Text("Wi-Fi: ${sn.band}, channel ${sn.channel}, ${sn.rssi} dBm")
                        Text("Link speed: down ${sn.linkDown} / up ${sn.linkUp} Mbps")
                    } else {
                        Text("Not connected to Wi-Fi")
                    }
                    Text("Gateway ping: ${msOrDash(sn.gwMs)}    Internet ping: ${msOrDash(sn.netMs)}")
                    Text("Jitter: ${msOrDash(sn.jitter)}    Packet loss: ${if (sn.loss < 0) "-" else sn.loss.toString() + " %"}")
                    Text("DNS lookup: ${msOrDash(sn.dnsMs)}")
                }
            }
        }

        SpeedChart(r.samples, showD, showU, unit, scale, mark30 = true)
        Text("Dashed line marks 30 seconds.", style = MaterialTheme.typography.bodySmall)
        StatsBlock(r.samples, r.mode, unit)
        val avgPing = r.samples.map { it.ping }.filter { it > 0 }.let { if (it.isEmpty()) 0 else it.average().toInt() }
        Text("Average ping under load: $avgPing ms")

        Button(
            onClick = { ReportExporter.share(ctx, ReportExporter.pdf(ctx, r), "application/pdf", "PDF report") },
            modifier = Modifier.fillMaxWidth()
        ) { Text("PDF report (save / share)") }
        Button(
            onClick = { ReportExporter.share(ctx, ReportExporter.summaryPng(ctx, r), "image/png", "Summary image") },
            modifier = Modifier.fillMaxWidth()
        ) { Text("PNG summary (save / share)") }
        OutlinedButton(onClick = { exportCsv(ctx, r) }, modifier = Modifier.fillMaxWidth()) {
            Text("Export CSV")
        }
    }
}

fun msOrDash(v: Int): String = if (v < 0) "-" else "$v ms"

fun exportCsv(ctx: Context, r: Report) {
    val f = File(ctx.cacheDir, "speedtest_${r.id}.csv").apply { writeText(r.toCsv()) }
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/csv")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, "Export report"))
}

@Composable
fun HistoryScreen(onOpen: (Report) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var items by remember { mutableStateOf(History.load(ctx)) }
    val unit = remember { Prefs.unit(ctx) }
    val df = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("History", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            TextButton(onClick = { History.clear(ctx); items = emptyList() }) { Text("Clear") }
        }
        if (items.isEmpty()) Text("No tests saved yet.")
        items.forEach { r ->
            Card(Modifier.fillMaxWidth().clickable { onOpen(r) }) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(df.format(Date(r.id)), fontWeight = FontWeight.SemiBold)
                    if (r.server.isNotBlank()) Text(r.server, style = MaterialTheme.typography.bodySmall)
                    Text("${r.mode.label}  |  ${r.conn.label}  |  ${fmtDur(r.ranSec)}  |  ${fmtBytes(r.totalBytes)}")
                    if (r.mode != Mode.UP) {
                        val s = r.downStats()
                        Text("Down avg ${unit.fmt(s.avg)}  min ${unit.fmt(s.min)}  max ${unit.fmt(s.max)} ${unit.label}")
                    }
                    if (r.mode != Mode.DOWN) {
                        val s = r.upStats()
                        Text("Up avg ${unit.fmt(s.avg)}  min ${unit.fmt(s.min)}  max ${unit.fmt(s.max)} ${unit.label}")
                    }
                }
            }
        }
    }
}
