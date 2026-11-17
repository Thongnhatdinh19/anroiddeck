package dev.mydeck.client

import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Serializable
data class Envelope(
    val protocolVersion: Int = 1,
    val kind: String,
    val type: String? = null,
    val id: String? = null,
    val correlationId: String? = null,
    val payload: JsonElement? = null,
    val error: JsonElement? = null,
)

class HostConnection(private val log: (String) -> Unit) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val http = OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).build()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonElement?>>()
    private var socket: WebSocket? = null
    private val seq = AtomicInteger(0)

    private fun enc(e: Envelope) = json.encodeToString(Envelope.serializer(), e)

    fun disconnect() {
        socket?.let { runCatching { it.send(enc(Envelope(kind = "goodbye"))) }; it.close(1000, null) }
        socket = null
    }

    suspend fun connect(host: String, port: Int, https: Boolean, token: String, clientId: String): Boolean =
        withContext(Dispatchers.IO) {
            disconnect()
            val base = (if (https) "https://" else "http://") + "$host:$port"

            // 1) Mint a WebSocket ticket
            val ticket = runCatching {
                val resp = http.newCall(
                    Request.Builder().url("$base/api/ui-websocket/tickets")
                        .post("{}".toRequestBody("application/json".toMediaType()))
                        .header("Authorization", "Bearer $token")
                        .header("X-MacroDeck-Ui-Protocol", "1").build()
                ).execute()
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) { log("Ticket: HTTP ${resp.code}"); return@withContext false }
                json.parseToJsonElement(body).jsonObject["value"]?.jsonPrimitive?.contentOrNull
            }.getOrElse { log("Ticket error: ${it.message}"); null }
            if (ticket.isNullOrEmpty()) { log("No ticket received"); return@withContext false }
            log("Ticket OK")

            // 2) WebSocket + hello/welcome
            val welcome = CompletableDeferred<Boolean>()
            val url = (if (https) "wss://" else "ws://") + "$host:$port/ws/ui?ticket=" +
                URLEncoder.encode(ticket, "UTF-8")
            val ws = http.newWebSocket(
                Request.Builder().url(url).header("Sec-WebSocket-Protocol", "macrodeck.ui.v1").build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        log("Socket open → hello")
                        webSocket.send(enc(Envelope(kind = "hello")))
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val e = runCatching { json.decodeFromString(Envelope.serializer(), text) }.getOrNull() ?: return
                        when (e.kind) {
                            "welcome" -> welcome.complete(true)
                            "ping" -> webSocket.send(enc(Envelope(kind = "pong", correlationId = e.id)))
                            "response" -> e.correlationId?.let { pending.remove(it)?.complete(e.payload ?: JsonNull) }
                            "error" -> {
                                log("Host error: ${e.error}")
                                e.correlationId?.let { pending.remove(it)?.complete(null) }
                            }
                            "message" -> log("Event: ${e.type}")
                        }
                    }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        log("Socket failure: ${t.message}"); welcome.complete(false)
                        pending.values.forEach { it.complete(null) }; pending.clear()
                    }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { log("Socket closed ($code)") }
                })
            socket = ws
            if (withTimeoutOrNull(10_000) { welcome.await() } != true) { log("Handshake failed"); return@withContext false }
            log("Welcome ✅ protocol v1")

            // 3) RegisterClient
            if (request(ws, "RegisterClient", buildJsonArray { add(clientId) }) == null) {
                log("RegisterClient failed"); return@withContext false
            }
            log("Registered as $clientId 🎉")
            true
        }

    private suspend fun request(ws: WebSocket, type: String, payload: JsonElement?): JsonElement? {
        val id = "k${seq.incrementAndGet()}-${UUID.randomUUID().toString().take(8)}"
        val d = CompletableDeferred<JsonElement?>()
        pending[id] = d
        ws.send(enc(Envelope(kind = "request", type = type, id = id, payload = payload)))
        return withTimeoutOrNull(10_000) { d.await() }
    }
}
