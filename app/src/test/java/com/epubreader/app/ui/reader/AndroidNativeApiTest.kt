package com.epubreader.app.ui.reader

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
    fun `allowed origin readium_package invokes onAutoScrollStopped callback`() {
        var called = false
        holder.callback = object : BridgeCallback {
            override fun onAutoScrollStopped() { called = true }
            override fun onSelectionChanged(text: String) {}
        }
        api.onAutoScrollStopped("https://readium_package")
        assertTrue(called)
    }

    @Test
    fun `allowed origin readium_assets invokes onSelectionChanged callback`() {
        var receivedText = ""
        holder.callback = object : BridgeCallback {
            override fun onAutoScrollStopped() {}
            override fun onSelectionChanged(text: String) { receivedText = text }
        }
        api.onSelectionChanged("https://readium_assets", "selected text")
        assertTrue(receivedText == "selected text")
    }

    @Test
    fun `blocked origin evil does not invoke onAutoScrollStopped callback`() {
        var called = false
        holder.callback = object : BridgeCallback {
            override fun onAutoScrollStopped() { called = true }
            override fun onSelectionChanged(text: String) {}
        }
        api.onAutoScrollStopped("https://evil.com")
        assertFalse(called)
    }

    @Test
    fun `blocked origin does not invoke onSelectionChanged callback`() {
        var called = false
        holder.callback = object : BridgeCallback {
            override fun onAutoScrollStopped() {}
            override fun onSelectionChanged(text: String) { called = true }
        }
        api.onSelectionChanged("https://malicious.org", "hello")
        assertFalse(called)
    }

    @Test
    fun `null callback does not crash`() {
        holder.callback = null
        // Should not throw NullPointerException
        api.onAutoScrollStopped("https://readium_package")
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
}
