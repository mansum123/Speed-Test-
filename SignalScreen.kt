@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.smartnet.speedtest

import android.Manifest
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun rssiColor(r: Int): Color = when {
    r >= -55 -> GoodColor
    r >= -65 -> OkColor
    r >= -75 -> WarnColor
    else -> BadColor
}

fun rssiWord(r: Int): String = when {
    r >= -55 -> "Excellent"
    r >= -65 -> "Good"
    r >= -75 -> "Fair"
    else -> "Weak"
}

@Composable
fun SignalScreen() {
    val ctx = LocalContext.current
    var now by remember { mutableStateOf<WifiNow?>(null) }
    val hist = remember { mutableStateListOf<Float?>() }
    var granted by remember { mutableStateOf(Loc.granted(ctx)) }
    var room by remember { mutableStateOf("") }
    var rooms by remember { mutableStateOf(Prefs.rooms(ctx)) }
    val df = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = Loc.granted(ctx)
    }

    LaunchedEffect(granted) {
        while (true) {
            val w = withContext(Dispatchers.IO) { WifiTool.now(ctx) }
            now = w
            hist.add(w?.rssi?.toFloat())
            if (hist.size > 300) hist.removeAt(0)
            delay(1000)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Signal Strength", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

        val w = now
        if (w == null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Not connected to Wi-Fi", fontWeight = FontWeight.Bold)
                    Text("Connect to a Wi-Fi network to see its signal.", style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Cell("Band", w.band)
                        Cell("Channel", w.channel.toString())
                        Cell("Width", w.width.ifBlank { "-" })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("Signal", style = MaterialTheme.typography.bodySmall)
                            Text("${w.rssi} dBm", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = rssiColor(w.rssi))
                            Text(rssiWord(w.rssi), color = rssiColor(w.rssi), fontWeight = FontWeight.SemiBold)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("PHY speed", style = MaterialTheme.typography.bodySmall)
                            Text("Down ${w.down} Mbps", fontWeight = FontWeight.Bold, color = DownColor)
                            Text("Up ${w.up} Mbps", fontWeight = FontWeight.Bold, color = UpColor)
                            if (w.std.isNotBlank()) Text(w.std, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        LineChart(
            listOf(ChartSeries("Signal", rssiColor(now?.rssi ?: -60), hist.toList())),
            yMin = -100f, yMax = -20f, unitLabel = "dBm (last 2 min)"
        )
        Text(
            "Better than -55 dBm is excellent. Below -75 dBm is weak: expect slow or unstable Wi-Fi.",
            style = MaterialTheme.typography.bodySmall
        )

        if (!granted) {
            OutlinedButton(onClick = {
                permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }) { Text("Allow location (needed for channel width)") }
        }

        Text("Room readings", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Text(
            "Walk to each room, type its name and save the reading. Good for coverage checks at a customer's home.",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = room, onValueChange = { room = it.take(30) },
            label = { Text("Room name (e.g. Bedroom)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            enabled = now != null && room.isNotBlank(),
            onClick = {
                val cur = now ?: return@Button
                rooms = listOf(RoomReading(System.currentTimeMillis(), room.trim(), cur.rssi, cur.band, cur.down)) + rooms
                Prefs.setRooms(ctx, rooms)
                room = ""
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save reading") }

        rooms.forEach { r ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(r.label, fontWeight = FontWeight.Bold)
                        Text("${r.band}  |  link ${r.link} Mbps  |  ${df.format(Date(r.t))}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("${r.rssi} dBm", color = rssiColor(r.rssi), fontWeight = FontWeight.Bold)
                    TextButton(onClick = {
                        rooms = rooms.filter { it !== r }
                        Prefs.setRooms(ctx, rooms)
                    }) { Text("X") }
                }
            }
        }
        if (rooms.isNotEmpty()) {
            OutlinedButton(
                onClick = {
                    val text = "Wi-Fi signal by room\n" + rooms.reversed().joinToString("\n") {
                        "${it.label}: ${it.rssi} dBm (${rssiWord(it.rssi)}), ${it.band}, link ${it.link} Mbps"
                    }
                    ctx.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                            "Share readings"
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Share all readings") }
        }
    }
}
