package dev.mydeck.client

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface { App() } } }
    }
}

@Composable
fun App() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var https by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Disconnected") }
    val logs = remember { mutableStateListOf<String>() }

    val clientId = remember {
        val prefs = context.getSharedPreferences("mydeck", Context.MODE_PRIVATE)
        prefs.getString("client_id", null) ?: ("mydeck-" + UUID.randomUUID().toString().take(8)).also {
            prefs.edit().putString("client_id", it).apply()
        }
    }

    val conn = remember {
        HostConnection { m ->
            scope.launch(Dispatchers.Main) {
                logs.add(0, m)
                if (logs.size > 200) logs.removeAt(logs.lastIndex)
            }
        }
    }
    DisposableEffect(conn) { onDispose { conn.disconnect() } }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("MyDeck", style = MaterialTheme.typography.headlineMedium)
        Text("Client ID: $clientId", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(host, { host = it }, label = { Text("Host IP (see Network panel)") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(port, { port = it }, label = { Text("Port") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(token, { token = it }, label = { Text("Access token (temporary)") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(https, { https = it }); Text("  HTTPS")
        }
        Button(enabled = !busy && host.isNotBlank() && port.toIntOrNull() != null, onClick = {
            busy = true; status = "Connecting…"
            scope.launch {
                val ok = conn.connect(host.trim(), port.toInt(), https, token.trim(), clientId)
                status = if (ok) "Connected ✅" else "Failed ❌"
                busy = false
            }
        }) { Text(if (busy) "Connecting…" else "Connect") }
        Text(status)
        logs.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
