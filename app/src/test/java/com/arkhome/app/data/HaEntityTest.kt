package com.arkhome.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the `subscribe_entities` parsing. `applyDiff` matters most: a wrong
 * diff does not crash, it quietly shows stale state, which is the hardest kind
 * of bug to notice in a smart-home app.
 */
class HaEntityTest {

    private fun entity(
        id: String = "light.kitchen",
        state: String = "off",
        attributes: String = "{}",
    ) = HaEntity(id, state, JSONObject(attributes))

    // MARK: - fromCompact

    @Test
    fun `fromCompact reads state and attributes`() {
        val entity = HaEntity.fromCompact(
            "light.kitchen",
            JSONObject("""{"s":"on","a":{"friendly_name":"Kitchen","brightness":200}}"""),
        )

        assertEquals("light.kitchen", entity?.entityId)
        assertEquals("on", entity?.state)
        assertEquals("Kitchen", entity?.friendlyName)
        assertEquals(200, entity?.attributes?.optInt("brightness"))
    }

    @Test
    fun `fromCompact without attributes yields an empty bag, not null`() {
        val entity = HaEntity.fromCompact("switch.plug", JSONObject("""{"s":"on"}"""))

        assertEquals("on", entity?.state)
        assertEquals(0, entity?.attributes?.length())
    }

    @Test
    fun `fromCompact returns null when state is missing`() {
        // HA never omits "s" for a real entity; if it is absent the payload is
        // something else and must not become a half-built entity.
        assertNull(HaEntity.fromCompact("light.kitchen", JSONObject("""{"a":{"x":1}}""")))
        assertNull(HaEntity.fromCompact("light.kitchen", JSONObject("""{"s":""}""")))
    }

    // MARK: - applyDiff

    @Test
    fun `applyDiff updates state`() {
        val before = entity(state = "off")
        val after = HaEntity.applyDiff(JSONObject("""{"+":{"s":"on"}}"""), before)

        assertEquals("on", after.state)
    }

    @Test
    fun `applyDiff adds and overwrites attributes while keeping untouched ones`() {
        val before = entity(attributes = """{"friendly_name":"Kitchen","brightness":10}""")
        val after = HaEntity.applyDiff(
            JSONObject("""{"+":{"a":{"brightness":255,"color_temp":370}}}"""),
            before,
        )

        assertEquals(255, after.attributes.optInt("brightness"))
        assertEquals(370, after.attributes.optInt("color_temp"))
        assertEquals("Kitchen", after.attributes.optString("friendly_name"))
    }

    @Test
    fun `applyDiff removes attributes listed under minus`() {
        val before = entity(attributes = """{"friendly_name":"Kitchen","brightness":10}""")
        val after = HaEntity.applyDiff(JSONObject("""{"-":{"a":["brightness"]}}"""), before)

        assertFalse(after.attributes.has("brightness"))
        assertEquals("Kitchen", after.attributes.optString("friendly_name"))
    }

    @Test
    fun `applyDiff handles an add and a removal in one message`() {
        val before = entity(state = "off", attributes = """{"a":1,"b":2}""")
        val after = HaEntity.applyDiff(
            JSONObject("""{"+":{"s":"on","a":{"c":3}},"-":{"a":["a"]}}"""),
            before,
        )

        assertEquals("on", after.state)
        assertFalse(after.attributes.has("a"))
        assertEquals(2, after.attributes.optInt("b"))
        assertEquals(3, after.attributes.optInt("c"))
    }

    @Test
    fun `applyDiff leaves the original entity untouched`() {
        // The map in HaClient holds the previous entity and hands it to
        // onStateChange as "old"; mutating it in place would corrupt that.
        val before = entity(state = "off", attributes = """{"brightness":10}""")
        HaEntity.applyDiff(JSONObject("""{"+":{"s":"on","a":{"brightness":255}}}"""), before)

        assertEquals("off", before.state)
        assertEquals(10, before.attributes.optInt("brightness"))
    }

    @Test
    fun `applyDiff with an empty diff changes nothing`() {
        val before = entity(state = "on", attributes = """{"brightness":10}""")
        val after = HaEntity.applyDiff(JSONObject("{}"), before)

        assertEquals("on", after.state)
        assertEquals(10, after.attributes.optInt("brightness"))
    }

    @Test
    fun `applyDiff keeps state when only attributes change`() {
        val before = entity(state = "cool", attributes = """{"temperature":22.0}""")
        val after = HaEntity.applyDiff(
            JSONObject("""{"+":{"a":{"temperature":25.5}}}"""),
            before,
        )

        assertEquals("cool", after.state)
        assertEquals(25.5, after.attributes.optDouble("temperature"), 0.001)
    }

    // MARK: - Derived properties

    @Test
    fun `friendlyName falls back to a humanised entity id`() {
        assertEquals("living room lamp", entity("light.living_room_lamp").friendlyName)
        assertEquals(
            "Kitchen Spots",
            entity("light.kitchen", attributes = """{"friendly_name":"Kitchen Spots"}""").friendlyName,
        )
    }

    @Test
    fun `domain is the part before the dot`() {
        assertEquals("binary_sensor", entity("binary_sensor.front_door").domain)
    }

    @Test
    fun `displayState appends the unit when there is one`() {
        val withUnit = entity(
            "sensor.temp",
            state = "21.5",
            attributes = """{"unit_of_measurement":"C"}""",
        )
        assertEquals("21.5 C", withUnit.displayState)
        assertEquals("21.5", entity("sensor.temp", state = "21.5").displayState)
    }

    @Test
    fun `isAvailable is false for HA's sentinels`() {
        assertFalse(entity(state = "unavailable").isAvailable)
        assertFalse(entity(state = "unknown").isAvailable)
        assertTrue(entity(state = "off").isAvailable)
    }

    @Test
    fun `isOn covers the on-ish states across domains`() {
        assertTrue(entity(state = "on").isOn)
        assertTrue(entity("cover.blind", state = "open").isOn)
        assertTrue(entity("person.me", state = "home").isOn)
        assertTrue(entity("media_player.tv", state = "playing").isOn)
        assertFalse(entity(state = "off").isOn)
    }

    @Test
    fun `attributeStrings reads a list and tolerates a missing key`() {
        val climate = entity(
            "climate.ac",
            attributes = """{"hvac_modes":["off","cool","heat"]}""",
        )
        assertEquals(listOf("off", "cool", "heat"), climate.attributeStrings("hvac_modes"))
        assertEquals(emptyList<String>(), climate.attributeStrings("fan_modes"))
    }
}
