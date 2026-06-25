package com.epubreader.app.core.readium

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.readium.r2.shared.publication.Locator

class LocatorMapperTest {

    @AfterEach
    fun tearDown() {
        try {
            unmockkObject(Locator.Companion)
        } catch (_: Exception) {
            // may not have been mocked; safe to ignore
        }
    }

    // ── String.toLocator() tests ──

    @Test
    fun `null and blank string return null`() {
        assertNull("".toLocator())
        assertNull("   ".toLocator())
        assertNull("\t\n".toLocator())
    }

    @Test
    fun `invalid JSON returns null`() {
        assertNull("not json".toLocator())
        assertNull("{bad json".toLocator())
        assertNull("12345".toLocator())
        assertNull("[1,2,3]".toLocator())
    }

    @Test
    fun `valid JSON string parses to Locator`() {
        val jsonStr = """{"href":"chapter1","type":"text/html","locations":{"progression":0.5}}"""
        val expectedLocator = mockk<Locator>(relaxed = true)

        mockkObject(Locator.Companion)
        every { Locator.fromJSON(any<JSONObject>()) } returns expectedLocator

        val result = jsonStr.toLocator()
        assertNotNull(result)
        assertEquals(expectedLocator, result)
    }

    // ── Locator.toJsonString() tests ──

    @Test
    fun `locator serializes to JSON string containing expected fields`() {
        val locator = mockk<Locator>(relaxed = true)

        // Mock both toJSON() and JSONObject.toString() to avoid Android mock issues
        val jsonObj = mockk<JSONObject>(relaxed = true)
        every { jsonObj.toString() } returns """{"href":"chapter1","type":"text/html","locations":{"progression":0.5}}"""
        every { locator.toJSON() } returns jsonObj

        val result = locator.toJsonString()
        assertTrue(result.contains("chapter1"), "Should contain href: chapter1, got: $result")
        assertTrue(result.contains("text/html"), "Should contain type: text/html, got: $result")
        assertTrue(result.contains("0.5"), "Should contain progression: 0.5, got: $result")
    }

    // ── Round-trip test ──

    @Test
    fun `round-trip locator to JSON and back`() {
        val jsonStr = """{"href":"chapter2","type":"application/xhtml+xml","locations":{"progression":0.75}}"""
        val mockLocator = mockk<Locator>(relaxed = true)

        // Mock toJSON() with a mock JSONObject that has mocked toString()
        val jsonObj = mockk<JSONObject>(relaxed = true)
        every { jsonObj.toString() } returns jsonStr
        every { mockLocator.toJSON() } returns jsonObj

        mockkObject(Locator.Companion)
        every { Locator.fromJSON(any<JSONObject>()) } returns mockLocator

        // Parse JSON string to Locator
        val parsed = jsonStr.toLocator()
        assertNotNull(parsed)

        // Serialize back to JSON
        val serialized = parsed!!.toJsonString()
        assertTrue(serialized.contains("chapter2"), "Round-trip should preserve href: $serialized")
        assertTrue(serialized.contains("0.75"), "Round-trip should preserve progression: $serialized")
    }
}
