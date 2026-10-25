package com.arkhome.app.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arkhome.app.data.ConnStatus
import com.arkhome.app.data.HaClient
import com.arkhome.app.data.HaEntity
import com.arkhome.app.data.Prefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** One room's worth of entities, as the Home list renders it. */
data class RoomSection(
    val name: String,
    val entities: List<HaEntity>,
)

class ArkViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)

    private var client: HaClient? = null

    /** Collectors bound to the current client, cancelled when it is replaced. */
    private val clientJobs = mutableListOf<Job>()

    private val _needsSetup = MutableStateFlow(!prefs.isConfigured)
    val needsSetup: StateFlow<Boolean> = _needsSetup.asStateFlow()

    private val _status = MutableStateFlow(ConnStatus.Disconnected)
    val status: StateFlow<ConnStatus> = _status.asStateFlow()

    private val _entities = MutableStateFlow<Map<String, HaEntity>>(emptyMap())
    val entities: StateFlow<Map<String, HaEntity>> = _entities.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** entity_id -> room name, resolved directly or through the device. */
    private val _areaByEntity = MutableStateFlow<Map<String, String>>(emptyMap())

    val savedUrl: String get() = prefs.baseUrl

    /** Entities worth showing, grouped by room, rooms sorted by name. */
    val rooms: StateFlow<List<RoomSection>> =
        combine(_entities, _areaByEntity) { entities, areas ->
            entities.values
                .filter { it.domain in VISIBLE_DOMAINS }
                .groupBy { areas[it.entityId] ?: UNASSIGNED }
                .map { (room, items) ->
                    RoomSection(
                        name = room,
                        entities = items.sortedWith(
                            compareBy(
                                { DOMAIN_ORDER.indexOf(it.domain).takeIf { i -> i >= 0 } ?: DOMAIN_ORDER.size },
                                { it.friendlyName.lowercase() },
                            )
                        ),
                    )
                }
                // "Other" always sinks to the bottom.
                .sortedWith(compareBy({ it.name == UNASSIGNED }, { it.name.lowercase() }))
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val cameras: StateFlow<List<HaEntity>> = _entities
        .map { map -> map.values.filter { it.domain == "camera" }.sortedBy { it.friendlyName.lowercase() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val climates: StateFlow<List<HaEntity>> = _entities
        .map { map -> map.values.filter { it.domain == "climate" }.sortedBy { it.friendlyName.lowercase() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        if (prefs.isConfigured) start()
    }

    // MARK: - Connection

    fun saveAndConnect(url: String, token: String) {
        prefs.baseUrl = HaClient.normalizeBaseUrl(url)
        prefs.token = token.trim()
        _needsSetup.value = false
        start()
    }

    fun signOut() {
        stopClient()
        prefs.clear()
        _entities.value = emptyMap()
        _areaByEntity.value = emptyMap()
        _status.value = ConnStatus.Disconnected
        _error.value = null
        _needsSetup.value = true
    }

    fun retry() {
        val current = client
        if (current != null) current.connect() else start()
    }

    /** The live client, for views that fetch their own data (camera cards). */
    fun client(): HaClient? = client

    private fun stopClient() {
        clientJobs.forEach { it.cancel() }
        clientJobs.clear()
        client?.dispose()
        client = null
    }

    private fun start() {
        stopClient()
        val fresh = HaClient(prefs.baseUrl, prefs.token)
        fresh.onConnected = {
            runCatching { fresh.refreshAllStates() }
            loadAreas(fresh)
        }
        client = fresh

        clientJobs += viewModelScope.launch { fresh.entities.collect { _entities.value = it } }
        clientJobs += viewModelScope.launch { fresh.status.collect { _status.value = it } }
        clientJobs += viewModelScope.launch { fresh.lastError.collect { _error.value = it } }

        fresh.connect()
    }

    // MARK: - Rooms

    /**
     * Resolves each entity's room: the entity registry's own `area_id` when it
     * has one, otherwise the area of the device it belongs to - the same
     * precedence Home Assistant's own frontend uses.
     */
    private suspend fun loadAreas(source: HaClient) {
        try {
            val areaNames = mutableMapOf<String, String>()
            source.areaRegistry().forEachObject { area ->
                val id = area.optString("area_id")
                if (id.isNotBlank()) {
                    areaNames[id] = area.optString("name").ifBlank { id }
                }
            }

            val deviceAreas = mutableMapOf<String, String>()
            source.deviceRegistry().forEachObject { device ->
                val id = device.optString("id")
                val areaId = device.optString("area_id")
                if (id.isNotBlank() && areaId.isNotBlank()) {
                    deviceAreas[id] = areaId
                }
            }

            val resolved = mutableMapOf<String, String>()
            source.entityRegistry().forEachObject { entry ->
                val entityId = entry.optString("entity_id")
                if (entityId.isNotBlank()) {
                    val direct = entry.optString("area_id").ifBlank { null }
                    val viaDevice = entry.optString("device_id").ifBlank { null }
                        ?.let { deviceAreas[it] }
                    val areaId = direct ?: viaDevice
                    val name = areaId?.let { areaNames[it] }
                    if (name != null) resolved[entityId] = name
                }
            }
            _areaByEntity.value = resolved
        } catch (e: Exception) {
            // A non-admin household account may not read the registries.
            // One flat list beats failing to load.
            Log.w(TAG, "Area registry unavailable; showing a flat list", e)
        }
    }

    // MARK: - Actions

    fun toggle(entity: HaEntity) {
        call(entity, "homeassistant", "toggle", null)
    }

    fun setHvacMode(entity: HaEntity, mode: String) {
        call(entity, "climate", "set_hvac_mode", JSONObject().put("hvac_mode", mode))
    }

    fun setTemperature(entity: HaEntity, temperature: Double) {
        call(entity, "climate", "set_temperature", JSONObject().put("temperature", temperature))
    }

    fun setFanMode(entity: HaEntity, mode: String) {
        call(entity, "climate", "set_fan_mode", JSONObject().put("fan_mode", mode))
    }

    private fun call(entity: HaEntity, domain: String, service: String, data: JSONObject?) {
        val current = client ?: return
        viewModelScope.launch {
            try {
                current.callService(domain, service, entity.entityId, data)
            } catch (e: Exception) {
                _error.value = e.message ?: "Command failed"
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    override fun onCleared() {
        stopClient()
        super.onCleared()
    }

    private companion object {
        const val TAG = "ArkViewModel"
        const val UNASSIGNED = "Other"

        /** Domains the simple build renders; everything else is noise here. */
        val VISIBLE_DOMAINS = setOf(
            "light", "switch", "fan", "climate", "cover", "lock", "binary_sensor",
            "sensor", "input_boolean", "media_player", "humidifier",
        )

        /** Controls first, then readings. */
        val DOMAIN_ORDER = listOf(
            "light", "switch", "fan", "climate", "cover", "lock", "media_player",
            "humidifier", "input_boolean", "binary_sensor", "sensor",
        )

        inline fun JSONArray.forEachObject(action: (JSONObject) -> Unit) {
            for (i in 0 until length()) {
                optJSONObject(i)?.let(action)
            }
        }
    }
}
