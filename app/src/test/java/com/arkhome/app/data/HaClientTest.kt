package com.arkhome.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `normalizeBaseUrl` takes whatever someone types into the setup form and has
 * to produce a bare http(s) origin — every later URL (the WebSocket endpoint
 * and the camera proxy) is built by string concatenation on top of it, so a
 * stray trailing slash or leftover path breaks both.
 */
class HaClientTest {

    private fun normalize(input: String) = HaClient.normalizeBaseUrl(input)

    @Test
    fun `bare host and port gets http`() {
        assertEquals("http://192.168.1.10:8123", normalize("192.168.1.10:8123"))
    }

    @Test
    fun `bare hostname gets http`() {
        assertEquals("http://homeassistant.local", normalize("homeassistant.local"))
    }

    @Test
    fun `existing schemes are preserved`() {
        assertEquals("http://192.168.1.10:8123", normalize("http://192.168.1.10:8123"))
        assertEquals("https://ha.example.com", normalize("https://ha.example.com"))
    }

    @Test
    fun `websocket schemes map to their http equivalents`() {
        assertEquals("http://192.168.1.10:8123", normalize("ws://192.168.1.10:8123"))
        assertEquals("https://ha.example.com", normalize("wss://ha.example.com"))
    }

    @Test
    fun `scheme matching is case insensitive`() {
        assertEquals("https://ha.example.com", normalize("HTTPS://ha.example.com"))
        assertEquals("https://ha.example.com", normalize("WSS://ha.example.com"))
    }

    @Test
    fun `trailing slashes are stripped`() {
        assertEquals("http://192.168.1.10:8123", normalize("http://192.168.1.10:8123/"))
        assertEquals("http://192.168.1.10:8123", normalize("192.168.1.10:8123///"))
    }

    @Test
    fun `paths are dropped`() {
        assertEquals(
            "http://192.168.1.10:8123",
            normalize("http://192.168.1.10:8123/lovelace/dashboard-ark-home"),
        )
    }

    @Test
    fun `queries and fragments are dropped`() {
        assertEquals("https://ha.example.com", normalize("https://ha.example.com?foo=bar"))
        assertEquals("https://ha.example.com", normalize("https://ha.example.com#tab"))
        assertEquals("http://ha.example.com:8123", normalize("http://ha.example.com:8123/x?y=1#z"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        // Pasting a URL on Android very often brings a trailing newline.
        assertEquals("http://192.168.1.10:8123", normalize("  192.168.1.10:8123  "))
        assertEquals("http://192.168.1.10:8123", normalize("http://192.168.1.10:8123\n"))
    }

    @Test
    fun `a tailnet address survives intact`() {
        assertEquals("http://100.101.102.103:8123", normalize("100.101.102.103:8123"))
    }

    @Test
    fun `the result never ends in a slash, so concatenation is safe`() {
        val inputs = listOf(
            "192.168.1.10:8123/",
            "http://ha.example.com/",
            "wss://ha.example.com/api/websocket",
            "  https://ha.example.com/lovelace/  ",
        )
        inputs.forEach { input ->
            val normalized = normalize(input)
            assertEquals("no trailing slash for '$input'", false, normalized.endsWith("/"))
        }
    }
}
