package io.github.danielayer.stttts.terminal

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val vm: TerminalViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { App(vm) }
            }
        }
    }
}

@Composable
private fun App(vm: TerminalViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Doublet?>(null) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val d = pending
        pending = null
        if (ok && d != null) vm.attach(d)
    }
    fun attach(d: Doublet) {
        val has = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (has) vm.attach(d) else { pending = d; askMic.launch(Manifest.permission.RECORD_AUDIO) }
    }

    // Keep the screen on while attached, so the terminal stays live.
    val view = LocalView.current
    DisposableEffect(ui.attached) {
        view.keepScreenOn = ui.attached != null
        onDispose { view.keepScreenOn = false }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ui.error?.let { msg ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = vm::clearError) { Text("OK") }
                }
            }
        }
        when {
            !ui.connected -> { ConnectScreen(ui, vm); Spacer(Modifier.weight(1f)) }
            ui.attached == null -> DoubletList(ui, vm, ::attach)
            else -> TalkScreen(ui, vm, Modifier.weight(1f))
        }
        if (ui.attached == null) EventLog(ui.log, Modifier.fillMaxWidth().height(140.dp))
    }
}

@Composable
private fun ConnectScreen(ui: UiState, vm: TerminalViewModel) {
    Text("SaH terminal", style = MaterialTheme.typography.headlineSmall)
    OutlinedTextField(ui.endpoint, vm::setEndpoint, Modifier.fillMaxWidth(),
        label = { Text("Zenoh router (e.g. tcp/192.168.1.20:7447)") }, singleLine = true)
    OutlinedTextField(ui.prefix, vm::setPrefix, Modifier.fillMaxWidth(),
        label = { Text("Service prefix") }, singleLine = true)
    Button(onClick = vm::connect, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
        Text(if (ui.busy) "Connecting…" else "Connect")
    }
}

@Composable
private fun ColumnScope.DoubletList(ui: UiState, vm: TerminalViewModel, attach: (Doublet) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Doublets", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = vm::refresh, enabled = !ui.busy) { Text("Refresh") }
        Spacer(Modifier.size(8.dp))
        OutlinedButton(onClick = vm::disconnect) { Text("Disconnect") }
    }
    if (ui.doublets.isEmpty()) {
        Text("No open doublets. Start an app on the server, e.g. `sah test-app`.")
    }
    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(ui.doublets, key = { it.id }) { d ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(d.id, style = MaterialTheme.typography.titleMedium)
                        Text("${d.name} · ${d.state}" + (d.terminal?.let { " · in use by $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = { attach(d) }, enabled = d.terminal == null && !ui.busy) {
                        Text("Attach")
                    }
                }
            }
        }
    }
}

@Composable
private fun TalkScreen(ui: UiState, vm: TerminalViewModel, modifier: Modifier) {
    val d = ui.attached ?: return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(d.id, style = MaterialTheme.typography.titleLarge)
            Text(d.name, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = vm::detach) { Text("Detach") }
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (ui.transcript.isNotBlank() || ui.partial.isNotBlank()) {
            Text("You: " + ui.partial.ifBlank { ui.transcript }, Modifier.fillMaxWidth(),
                color = if (ui.partial.isNotBlank()) Color.Gray else Color.Unspecified)
        }
        if (ui.reply.isNotBlank()) Text("Reply: ${ui.reply}", Modifier.fillMaxWidth())
        Spacer(Modifier.weight(1f))
        val (label, color) = when (ui.state) {
            "listening" -> "Listening\ntap to send" to Color(0xFFC62828)
            "thinking" -> "Thinking…\ntap to cancel" to Color(0xFF6A1B9A)
            "speaking" -> "Speaking\ntap to interrupt" to Color(0xFF2E7D32)
            else -> "Tap to talk" to Color(0xFF1565C0)
        }
        Button(onClick = vm::press, shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = color),
            modifier = Modifier.size(240.dp)) {
            Text(label, fontSize = 22.sp, textAlign = TextAlign.Center, color = Color.White)
        }
        Spacer(Modifier.weight(1f))
        EventLog(ui.log, Modifier.fillMaxWidth().height(120.dp))
    }
}

@Composable
private fun EventLog(lines: List<String>, modifier: Modifier) {
    Box(modifier) {
        LazyColumn {
            items(lines) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color.Gray) }
        }
    }
}
