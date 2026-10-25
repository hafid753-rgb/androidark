package com.arkhome.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * One Home Assistant entity. Attributes stay as raw JSON because HA's
 * attribute set is open-ended and differs per integration - the UI reads only
 * the keys it understands.
 */
data class HaEntity(
    val entityId: String,
    val state: String,
    val attributes: JSONObject = JSONObject(),
) {
    val domain: String get() = entityId.substringBefore('.')

    val friendlyName: String
        get() = attributes.optString("friendly_name")
            .ifBlank { entityId.substringAfter('.').replace('_', ' ') }

    val deviceClass: String? get() = attributes.optString("device_class").ifBlank { null }

    /** HA's "unavailable"/"unknown" sentinels mean there is nothing to act on. */
    val isAvailable: Boolean get() = state != "unavailable" && state != "unknown"

    val isOn: Boolean get() = state == "on" || state == "open" || state == "home" || state == "playing"

    val unit: String? get() = attributes.optString("unit_of_measurement").ifBlank { null }

    /** What the list shows on the right of a non-toggle row. */
    val displayState: String
        get() = unit?.let { "$state $it" } ?: state

    fun attributeStrings(key: String): List<String> {
        val raw = attributes.opt(key) as? JSONArray ?: return emptyList()
        return (0 until raw.length()).mapNotNull { raw.optString(it).ifBlank { null } }
    }

    companion object {
        /** Domains a plain tap can flip with `homeassistant.toggle`. */
        val TOGGLE_DOMAINS = setOf(
            "light", "switch", "fan", "input_boolean", "automation", "humidifier", "siren",
        )

        /** Domains that open a detail sheet rather than toggling. */
        val CLIMATE_DOMAINS = setOf("climate")

        fun fromStateObject(json: JSONObject): HaEntity? {
            val entityId = json.optString("entity_id").ifBlank { return null }
            val state = json.optString("state").ifBlank { return null }
            return HaEntity(
                entityId = entityId,
                state = state,
                attributes = json.optJSONObject("attributes") ?: JSONObject(),
            )
        }

        /**
         * `subscribe_entities` compact form: "s" = state, "a" = attributes.
         * Mirrors `HaClient.entity(entityId:compressed:)` in the iOS app.
         */
        fun fromCompact(entityId: String, compact: JSONObject): HaEntity? {
            val state = compact.optString("s").ifBlank { return null }
            return HaEntity(
                entityId = entityId,
                state = state,
                attributes = compact.optJSONObject("a") ?: JSONObject(),
            )
        }

        /**
         * Applies a `subscribe_entities` diff: "+" adds/overwrites state and
         * attributes, "-"."a" lists removed attribute keys.
         */
        fun applyDiff(diff: JSONObject, to: HaEntity): HaEntity {
            var state = to.state
            val attributes = JSONObject(to.attributes.toString())

            diff.optJSONObject("+")?.let { plus ->
                plus.optString("s").ifBlank { null }?.let { state = it }
                plus.optJSONObject("a")?.let { added ->
                    added.keys().forEach { key -> attributes.put(key, added.get(key)) }
                }
            }
            diff.optJSONObject("-")?.optJSONArray("a")?.let { removed ->
                for (i in 0 until removed.length()) {
                    attributes.remove(removed.optString(i))
                }
            }
            return to.copy(state = state, attributes = attributes)
        }
    }
}

/** A room as the box defines it, plus the entities that resolve into it. */
data class HaArea(
    val id: String,
    val name: String,
)

enum class ConnStatus { Disconnected, Connecting, Connected, AuthFailed }
