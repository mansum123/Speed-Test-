@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.smartnet.speedtest

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import kotlinx.coroutines.delay

@Composable
fun ServerScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var list by remember { mutableStateOf<List<Server>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var granted by remember { mutableStateOf(Loc.granted(ctx)) }
    var loc by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var cf by remember { mutableStateOf<CfInfo?>(null) }
    var showCustom by remember { mutableStateOf(false) }
    val current = remember { Prefs.server(ctx) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = Loc.granted(ctx)
    }

    LaunchedEffect(Unit) { cf = Servers.cf() }
    LaunchedEffect(granted) { loc = if (granted) Loc.get(ctx) else null }
    LaunchedEffect(query, loc) {
        if (query.isNotBlank()) delay(450)
        loading = true
        val r = Servers.search(query, loc)
        list = r.list
        msg = r.error
        loading = false
    }

    fun pick(s: Server) {
        Prefs.setServer(ctx, s)
        onBack()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Change server", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search server, company or city") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Card(
            Modifier.fillMaxWidth().clickable { pick(AUTO_SERVER) },
            colors = CardDefaults.cardColors(
                containerColor = if (current.kind == "cf") MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("Select automatically", fontWeight = FontWeight.Bold)
                Text(
                    (cf?.label ?: "Cloudflare") + " - Cloudflare chooses the nearest data centre for you",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (!granted) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Allow location to list the nearest servers first.", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Without location, the list is based on your internet connection's approximate area.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(onClick = {
                        permLauncher.launch(
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                        )
                    }) { Text("Allow location") }
                }
            }
        } else if (!Loc.enabled(ctx)) {
            Text("Location is switched off on this phone. Turn it on for nearby servers.", style = MaterialTheme.typography.bodySmall)
        }

        if (loading) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.padding(2.dp))
                Text("Loading servers...")
            }
        }
        msg?.let { if (!loading) Text(it, color = MaterialTheme.colorScheme.error) }

        list.forEach { s ->
            val selected = current.kind == "ookla" && current.id == s.id
            Card(
                Modifier.fillMaxWidth().clickable { pick(s) },
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, fontWeight = FontWeight.Bold)
                        Text(
                            listOf(s.city, s.country).filter { it.isNotBlank() }.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (s.distKm >= 0) Text("${s.distKm} km")
                }
            }
        }

        if (current.kind == "custom") {
            Text("Current custom server: ${current.name}", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = { showCustom = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Add custom server (your own URL)")
        }
    }

    if (showCustom) {
        var name by remember { mutableStateOf(if (current.kind == "custom") current.name else "") }
        var down by remember { mutableStateOf(if (current.kind == "custom") current.url else "") }
        var up by remember { mutableStateOf(if (current.kind == "custom") current.upUrl else "") }
        val valid = name.isNotBlank() && down.trim().startsWith("http")
        AlertDialog(
            onDismissRequest = { showCustom = false },
            title = { Text("Custom server") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(
                        down, { down = it }, label = { Text("Download file URL (large file)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        up, { up = it }, label = { Text("Upload URL (optional)") },
                        singleLine = true
                    )
                    Text(
                        "The download URL should be a file of 50 MB or more on your own server. " +
                            "Without an upload URL, upload tests are skipped.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = valid,
                    onClick = {
                        showCustom = false
                        pick(Server("custom", "custom", name.trim(), "", "", down.trim(), up.trim()))
                    }
                ) { Text("Use this server") }
            },
            dismissButton = { TextButton(onClick = { showCustom = false }) { Text("Cancel") } }
        )
    }
}
