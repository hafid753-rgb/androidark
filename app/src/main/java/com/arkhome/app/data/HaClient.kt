package com.arkhome.app.data

import android.util.Log
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min
import kotlin.math.pow

class HaException(message: String) : Exception(message)

/**
 * Home Assistant WebSocket client: auth handshake, command correlation,
 * `subscribe_entities` diffs and automatic reconnect with backoff. A direct
 * port of the iOS app's HaClient, minus the features the simple build drops.
 */
class HaClient(
    baseUrl: String,
    private var token: String,
) {
    val baseUrlString: String = normalizeBaseUrl(baseUrl)

    private val http = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow(ConnStatus.Disconnected)
    val status: StateFlow<ConnStatus> = _status.asStateFlow()

    private val _entities = MutableStateFlow<Map<String, HaEntity>>(emptyMap())
    val entities: StateFlow<Map<String, HaEntity>> = _entities.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** Runs after each successful auth, so the store can reload registries. */
    var onConnected: (suspend () -> Unit)? = null

    private var socket: WebSocket? = null
    private val nextId = AtomicInteger(1)
    private var entitiesSubId: Int? = null
    private val pending = ConcurrentHashMap<Int, CancellableContinuation<Any?>>()
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private var authInvalidAttempts = 0
    @Volatile private var disposed = false

    // MARK: - Lifecycle

    fun connect() {
        if (disposed) return
        reconnectJob?.cancel()
        reconnectJob = null
        teardown()

        val wsUrl = webSocketUrl()
        if (wsUrl == null) {
            _lastError.value = "Invalid server URL"
            _status.value = ConnStatus.Disconnected
            return
        }

        _status.value = ConnStatus.Connecting
        _lastError.value = null
        Log.i(TAG, "ws connecting")

        val request = Request.Builder().url(wsUrl).build()
        socket = http.newWebSocket(request, Listener())
    }

    fun dispose() {
        disposed = true
        reconnectJob?.cancel()
        rejectPending()
        teardown()
        scope.cancel()
    }

    private fun teardown() {
        val current = socket
        socket = null
        current?.cancel()
    }

    private fun scheduleReconnect() {
        if (disposed) return
        _status.value = ConnStatus.Disconnected
        reconnectJob?.cancel()
        reconnectAttempt += 1
        // Exponential backoff (2, 4, 8, 15, 15...s), matching the iOS client.
        val delaySeconds = min(2.0.pow(reconnectAttempt), 15.0)
        Log.i(TAG, "reconnect in ${delaySeconds.toInt()}s (attempt $reconnectAttempt)")
        reconnectJob = scope.launch {
            delay((delaySeconds * 1000).toLong())
            connect()
        }
    }

    private fun handleDisconnect(error: String? = null) {
        if (disposed) return
        rejectPending()
        if (_status.value == ConnStatus.AuthFailed) return
        if (error != null) _lastError.value = error
        Log.i(TAG, "disconnected${error?.let { ": $it" }.orEmpty()}")
        scheduleReconnect()
    }

    // MARK: - Socket callbacks

    private inner class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== socket) return
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                return
            }
            handleMessage(json)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket !== socket) return
            handleDisconnect(t.message ?: "Connection failed")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket !== socket) return
            handleDisconnect()
        }
    }

    private fun handleMessage(json: JSONObject) {
        when (json.optString("type")) {
            "auth_required" -> sendRaw(
                JSONObject().put("type", "auth").put("access_token", token)
            )

            "auth_ok" -> {
                reconnectAttempt = 0
                authInvalidAttempts = 0
                _status.value = ConnStatus.Connected
                Log.i(TAG, "auth ok - connected")
                scope.launch {
                    afterAuth()
                    onConnected?.invoke()
                }
            }

            "auth_invalid" -> {
                authInvalidAttempts += 1
                Log.i(TAG, "auth_invalid (attempt $authInvalidAttempts/3)")
                // HA can transiently reject a valid token right after a
                // restart, so don't strand the user on an error over a blip.
                if (authInvalidAttempts <= 3) {
                    _lastError.value = null
                    scheduleReconnect()
                } else {
                    _status.value = ConnStatus.AuthFailed
                    _lastError.value = "The access token was rejected."
                }
            }

            "result" -> handleResult(json)

            "event" -> {
                val eventId = if (json.has("id")) json.optInt("id") else null
                val event = json.optJSONObject("event") ?: return
                if (entitiesSubId != null && eventId == entitiesSubId) {
                    handleEntitiesDelta(event)
                } else {
                    handleEvent(event)
                }
            }
        }
    }

    private suspend fun afterAuth() {
        // subscribe_entities sends compact diffs - far less data than full
        // state_changed objects. Fall back only if the server rejects it.
        val subId = nextId.get()
        entitiesSubId = subId
        val subscribed = runCatching {
            command(JSONObject().put("type", "subscribe_entities"))
        }.isSuccess
        if (!subscribed) {
            entitiesSubId = null
            runCatching {
                command(
                    JSONObject()
                        .put("type", "subscribe_events")
                        .put("event_type", "state_changed")
                )
            }
        }
    }

    private fun handleEntitiesDelta(event: JSONObject) {
        _entities.update { current ->
            val next = current.toMutableMap()

            event.optJSONObject("a")?.let { added ->
                added.keys().forEach { entityId ->
                    added.optJSONObject(entityId)
                        ?.let { HaEntity.fromCompact(entityId, it) }
                        ?.let { next[entityId] = it }
                }
            }
            event.optJSONObject("c")?.let { changed ->
                changed.keys().forEach { entityId ->
                    val diff = changed.optJSONObject(entityId) ?: return@forEach
                    val existing = next[entityId]
                    if (existing != null) {
                        next[entityId] = HaEntity.applyDiff(diff, existing)
                    } else {
                        diff.optJSONObject("+")
                            ?.let { HaEntity.fromCompact(entityId, it) }
                            ?.let { next[entityId] = it }
                    }
                }
            }
            event.optJSONArray("r")?.let { removed ->
                for (i in 0 until removed.length()) {
                    next.remove(removed.optString(i))
                }
            }
            next
        }
    }

    private fun handleEvent(event: JSONObject) {
        if (event.optString("event_type") != "state_changed") return
        val data = event.optJSONObject("data") ?: return
        val newState = data.optJSONObject("new_state") ?: return
        val entity = HaEntity.fromStateObject(newState) ?: return
        _entities.update { it + (entity.entityId to entity) }
    }

    private fun handleResult(message: JSONObject) {
        val id = if (message.has("id")) message.optInt("id") else return
        val continuation = pending.remove(id) ?: return
        if (message.optBoolean("success")) {
            continuation.resume(message.opt("result").takeUnless { it == JSONObject.NULL })
        } else {
            val error = message.optJSONObject("error")?.optString("message")
                ?.ifBlank { null }
                ?: "HA command failed"
            continuation.resumeWithException(HaException(error))
        }
    }

    private fun rejectPending() {
        val continuations = pending.values.toList()
        pending.clear()
        continuations.forEach {
            if (it.isActive) it.resumeWithException(HaException("Connection to ARK HOME lost"))
        }
    }

    // MARK: - Commands

    private fun sendRaw(payload: JSONObject) {
        socket?.send(payload.toString())
    }

    private suspend fun command(payload: JSONObject): Any? {
        val current = socket
        if (_status.value != ConnStatus.Connected && payload.optString("type") != "subscribe_entities") {
            throw HaException("Not connected")
        }
        if (current == null) throw HaException("Not connected")

        val id = nextId.getAndIncrement()
        payload.put("id", id)

        return try {
            withTimeout(COMMAND_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    pending[id] = continuation
                    continuation.invokeOnCancellation { pending.remove(id) }
                    if (!current.send(payload.toString())) {
                        pending.remove(id)
                        if (continuation.isActive) {
                            continuation.resumeWithException(HaException("Not connected"))
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            pending.remove(id)
            throw HaException("HA command timed out: ${payload.optString("type")}")
        }
    }

    /** Raw escape hatch for callers that need an uncommon command. */
    suspend fun send(payload: JSONObject): Any? = command(payload)

    suspend fun callService(
        domain: String,
        service: String,
        entityId: String? = null,
        data: JSONObject? = null,
    ) {
        val message = JSONObject()
            .put("type", "call_service")
            .put("domain", domain)
            .put("service", service)
        if (entityId != null) {
            message.put("target", JSONObject().put("entity_id", entityId))
        }
        if (data != null) {
            message.put("service_data", data)
        }
        command(message)
    }

    suspend fun refreshAllStates() {
        val result = command(JSONObject().put("type", "get_states")) as? JSONArray ?: return
        val parsed = buildMap<String, HaEntity> {
            for (i in 0 until result.length()) {
                val item = result.optJSONObject(i) ?: continue
                HaEntity.fromStateObject(item)?.let { put(it.entityId, it) }
            }
        }
        _entities.update { it + parsed }
    }

    suspend fun areaRegistry(): JSONArray =
        command(JSONObject().put("type", "config/area_registry/list")) as? JSONArray ?: JSONArray()

    suspend fun entityRegistry(): JSONArray =
        command(JSONObject().put("type", "config/entity_registry/list")) as? JSONArray ?: JSONArray()

    suspend fun deviceRegistry(): JSONArray =
        command(JSONObject().put("type", "config/device_registry/list")) as? JSONArray ?: JSONArray()

    // MARK: - REST

    /**
     * Current camera frame via HA's authenticated camera proxy. Returns null
     * rather than throwing - a card that cannot load a frame keeps the last one.
     */
    suspend fun cameraSnapshot(entityId: String): ByteArray? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrlString/api/camera_proxy/$entityId")
            .header("Authorization", "Bearer $token")
            .build()
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body?.bytes()
            }
        } catch (e: IOException) {
            null
        }
    }

    // MARK: - URLs

    private fun webSocketUrl(): String? {
        val base = baseUrlString
        return when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://") + "/api/websocket"
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://") + "/api/websocket"
            else -> null
        }
    }

    companion object {
        private const val TAG = "ArkHaClient"
        private const val COMMAND_TIMEOUT_MS = 15_000L

        /**
         * Accepts what people actually type ("192.168.1.10:8123", a ws:// URL,
         * a trailing slash) and returns a bare http(s) origin.
         */
        fun normalizeBaseUrl(input: String): String {
            var raw = input.trim()
            val lower = raw.lowercase()
            if (!lower.startsWith("http://") && !lower.startsWith("https://") &&
                !lower.startsWith("ws://") && !lower.startsWith("wss://")
            ) {
                raw = "http://$raw"
            }
            raw = raw
                .replace(Regex("^wss://", RegexOption.IGNORE_CASE), "https://")
                .replace(Regex("^ws://", RegexOption.IGNORE_CASE), "http://")

            // Drop any path, query or fragment - only the origin is wanted.
            val schemeEnd = raw.indexOf("://") + 3
            val authorityEnd = raw.indexOfFirst(schemeEnd) { it == '/' || it == '?' || it == '#' }
            if (authorityEnd > 0) raw = raw.substring(0, authorityEnd)
            return raw.trimEnd('/')
        }

        private inline fun String.indexOfFirst(startIndex: Int, predicate: (Char) -> Boolean): Int {
            for (i in startIndex until length) {
                if (predicate(this[i])) return i
            }
            return -1
        }
    }
}
