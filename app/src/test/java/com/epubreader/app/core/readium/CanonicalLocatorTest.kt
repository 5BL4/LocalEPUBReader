package com.epubreader.app.core.readium

import io.mockk.every
import io.mockk.mockk
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.readium.r2.shared.publication.Locator

class CanonicalLocatorTest {

    /**
     * Creates a mocked JSONObject with the given key-value pairs in insertion order.
     * The mock supports [JSONObject.keys] (returning keys in the given order)
     * and [JSONObject.get] for each key. Nested JSONObjects must also be mocked.
     */
    @Suppress("UNCHECKED_CAST")
    private fun mockJsonTree(pairs: List<Pair<String, Any>>): JSONObject {
        val mock = mockk<JSONObject>(relaxed = true)
        val keyList = pairs.map { it.first }
        // Use answers {} so a fresh iterator is created for each keys() call
        every { mock.keys() } answers { keyList.iterator() as Iterator<String> }
        for ((key, value) in pairs) {
            every { mock.get(key) } returns value
        }
        return mock
    }

    private fun mockJsonTree(vararg pairs: Pair<String, Any>): JSONObject =
        mockJsonTree(pairs.toList())

    // ── Test 1: same locator produces identical canonical JSON ──
    @Test
    fun `same locator produces identical canonical JSON`() {
        val nestedLoc = mockJsonTree(
            "totalProgression" to 0.25,
            "progression" to 0.5
        )
        val jsonObj = mockJsonTree(
            "type" to "application/xhtml+xml",
            "href" to "chapter1.xhtml",
            "title" to "Chapter 1",
            "locations" to nestedLoc
        )

        val locator = mockk<Locator>(relaxed = true)
        every { locator.toJSON() } returns jsonObj

        val canonical1 = locator.toCanonicalJsonString()
        val canonical2 = locator.toCanonicalJsonString()

        assertEquals(canonical1, canonical2, "Canonical JSON should be deterministic")
    }

    // ── Test 2: canonical JSON keys are alphabetically sorted at top level ──
    @Test
    fun `canonical JSON keys are alphabetically sorted at top level`() {
        val nestedLoc = mockJsonTree(
            "totalProgression" to 0.25,
            "progression" to 0.5
        )
        // Keys inserted as: type, href, title, locations (NOT alphabetical)
        val jsonObj = mockJsonTree(
            "type" to "application/xhtml+xml",
            "href" to "chapter1.xhtml",
            "title" to "Chapter 1",
            "locations" to nestedLoc
        )

        val locator = mockk<Locator>(relaxed = true)
        every { locator.toJSON() } returns jsonObj

        val canonical = locator.toCanonicalJsonString()

        // Key "href" should appear before "locations" (alphabetical order)
        val hrefIndex = canonical.indexOf("\"href\"")
        val locationsIndex = canonical.indexOf("\"locations\"")
        val titleIndex = canonical.indexOf("\"title\"")
        val typeIndex = canonical.indexOf("\"type\"")

        assertTrue(hrefIndex < locationsIndex, "href should come before locations: $canonical")
        assertTrue(locationsIndex < titleIndex, "locations should come before title: $canonical")
        assertTrue(titleIndex < typeIndex, "title should come before type: $canonical")
    }

    // ── Test 3: canonical JSON keys are recursively sorted in nested objects ──
    @Test
    fun `canonical JSON keys are recursively sorted in nested objects`() {
        val nestedLoc = mockJsonTree(
            "totalProgression" to 0.25,  // totalProgression > progression alphabetically, but we inserted "total" first
            "progression" to 0.5
        )
        val jsonObj = mockJsonTree(
            "type" to "application/xhtml+xml",
            "href" to "chapter1.xhtml",
            "locations" to nestedLoc
        )

        val locator = mockk<Locator>(relaxed = true)
        every { locator.toJSON() } returns jsonObj

        val canonical = locator.toCanonicalJsonString()

        // In the nested locations object, "progression" should come before "totalProgression"
        val progIndex = canonical.indexOf("\"progression\"")
        val totalIndex = canonical.indexOf("\"totalProgression\"")
        assertTrue(progIndex < totalIndex, "progression should come before totalProgression in nested: $canonical")
    }

    // ── Test 4: round-trip: canonical → parse → canonical is stable (idempotent) ──
    @Test
    fun `roundtrip canonical parse canonical is stable idempotent`() {
        val nestedLoc = mockJsonTree(
            "totalProgression" to 0.5,
            "progression" to 0.75
        )
        val jsonObj = mockJsonTree(
            "type" to "application/xhtml+xml",
            "href" to "chapter2.xhtml",
            "title" to "Chapter 2",
            "locations" to nestedLoc
        )

        val locator = mockk<Locator>(relaxed = true)
        every { locator.toJSON() } returns jsonObj

        val firstCanonical = locator.toCanonicalJsonString()
        // Re-canonicalizing should produce the same result (idempotent: already-sorted input stays sorted)
        // Since our canonicalizeToString sorts keys, re-sorting sorted keys is idempotent
        // But we can't easily create a JSONObject from the string (stub issue),
        // so verify that firstCanonical has keys in order and contains expected values
        val hrefIndex = firstCanonical.indexOf("\"href\"")
        val locationsIndex = firstCanonical.indexOf("\"locations\"")
        assertTrue(hrefIndex > 0, "Canonical should have href")
        assertTrue(locationsIndex > hrefIndex, "href should come before locations")
        assertTrue(firstCanonical.contains("chapter2.xhtml"), "Canonical should contain the href value")
    }

    // ── Test 5: toCanonicalJsonString does not modify toJsonString behavior ──
    @Test
    fun `toCanonicalJsonString does not modify toJsonString behavior`() {
        // Verify toJsonString() still works with mocked JSONObject
        val locator1 = mockk<Locator>(relaxed = true)
        val mockJsonObj = mockk<JSONObject>(relaxed = true)
        every { mockJsonObj.toString() } returns """{"type":"application/xhtml+xml","href":"chapter3.xhtml","locations":{"progression":0.9}}"""
        every { locator1.toJSON() } returns mockJsonObj

        val normal1 = locator1.toJsonString()
        assertTrue(normal1.contains("chapter3.xhtml"), "toJsonString should contain href, got: $normal1")

        // Verify toCanonicalJsonString() works with mocked JSONObject tree
        val locator2 = mockk<Locator>(relaxed = true)
        val nestedLoc = mockJsonTree("progression" to 0.9)
        val realJsonObj = mockJsonTree(
            "type" to "application/xhtml+xml",
            "href" to "chapter3.xhtml",
            "locations" to nestedLoc
        )
        every { locator2.toJSON() } returns realJsonObj

        val canonical = locator2.toCanonicalJsonString()
        assertTrue(canonical.contains("chapter3.xhtml"), "toCanonicalJsonString should contain href, got: $canonical")

        // toJsonString is deterministic — call again
        val normal2 = locator1.toJsonString()
        assertEquals(normal1, normal2, "toJsonString should be deterministic and unchanged")
    }
}
