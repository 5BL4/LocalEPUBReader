package com.epubreader.app.ui.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [AndroidNativeApi] — JS Bridge security (NEVER #8).
 *
 * Verifies that only allowed Readium WebViewServer origins can invoke
 * native callbacks, and that malicious origins are blocked.
 */
class AndroidNativeApiTest {

    private lateinit var holder: BridgeCallbackHolder
    private lateinit var api: AndroidNativeApi

    @BeforeEach
    fun setUp() {
        holder = BridgeCallbackHolder()
        api = AndroidNativeApi(holder)
    }

    @Test
    fun `allowed origin readium_package invokes onCenterTap callback`() {
        var called = false
        holder.callback = object : BridgeCallback {
            override fun onCenterTap() { called = true }
            override fun onSelectionChanged(text: String) {}
            override fun onSentencesExtracted(json: String) {}
        }
        api.onCenterTap("https://readium_package")
        assertTrue(called)
    }

    @Test
    fun `allowed origin readium_assets invokes onSelectionChanged callback`() {
        var receivedText = ""
        holder.callback = object : BridgeCallback {
            override fun onCenterTap() {}
            override fun onSelectionChanged(text: String) { receivedText = text }
            override fun onSentencesExtracted(json: String) {}
        }
        api.onSelectionChanged("https://readium_assets", "selected text")
        assertTrue(receivedText == "selected text")
    }

    @Test
    fun `blocked origin evil does not invoke onCenterTap callback`() {
        var called = false
        holder.callback = object : BridgeCallback {
            override fun onCenterTap() { called = true }
            override fun onSelectionChanged(text: String) {}
            override fun onSentencesExtracted(json: String) {}
        }
        api.onCenterTap("https://evil.com")
        assertFalse(called)
    }

    @Test
    fun `blocked origin does not invoke onSelectionChanged callback`() {
        var called = false
        holder.callback = object : BridgeCallback {
            override fun onCenterTap() {}
            override fun onSelectionChanged(text: String) { called = true }
            override fun onSentencesExtracted(json: String) {}
        }
        api.onSelectionChanged("https://malicious.org", "hello")
        assertFalse(called)
    }

    @Test
    fun `null callback does not crash`() {
        holder.callback = null
        // Should not throw NullPointerException
        api.onCenterTap("https://readium_package")
        api.onSelectionChanged("https://readium_package", "test")
    }

    @Test
    fun `isAllowedOrigin validates correctly`() {
        assertTrue(AndroidNativeApi.isAllowedOrigin("https://readium_package"))
        assertTrue(AndroidNativeApi.isAllowedOrigin("https://readium_assets"))
        assertFalse(AndroidNativeApi.isAllowedOrigin("https://evil.com"))
        assertFalse(AndroidNativeApi.isAllowedOrigin(""))
        assertFalse(AndroidNativeApi.isAllowedOrigin("null"))
    }

    @Test
    fun `allowed origin invokes onSentencesExtracted callback`() {
        var receivedJson = ""
        holder.callback = object : BridgeCallback {
            override fun onCenterTap() {}
            override fun onSelectionChanged(text: String) {}
            override fun onSentencesExtracted(json: String) { receivedJson = json }
        }
        val testJson = """[{"id":0}]"""
        api.onSentencesExtracted("https://readium_package", testJson)
        assertEquals(testJson, receivedJson)
    }

    @Test
    fun `blocked origin does not invoke onSentencesExtracted callback`() {
        var called = false
        holder.callback = object : BridgeCallback {
            override fun onCenterTap() {}
            override fun onSelectionChanged(text: String) {}
            override fun onSentencesExtracted(json: String) { called = true }
        }
        api.onSentencesExtracted("https://evil.com", """[{"id":0}]""")
        assertFalse(called)
    }

    @Test
    fun `null callback does not crash on onSentencesExtracted`() {
        holder.callback = null
        // Should not throw NullPointerException
        api.onSentencesExtracted("https://readium_package", "{}")
    }
}
