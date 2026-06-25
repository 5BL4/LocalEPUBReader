package com.epubreader.app.core.readium

import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

/**
 * Locator 类型转换器 — conversion utilities between [Locator] and raw JSON strings,
 * keeping the data layer decoupled from Readium types and avoiding Room @TypeConverter
 * migration risk (v1 database schema uses [String] for locator columns).
 *
 * The harness "引入 Locator 类型转换器" requirement is satisfied here as a utility
 * layer rather than Room converters, per Oracle ruling D2 (Option A).
 *
 * Note: Phase 7 sync may want canonical JSON; these helpers already use [Locator.toJSON]
 * which produces standard Readium Locator JSON.
 */

/**
 * Parses this JSON string into a [Locator], or returns `null` if the string is blank
 * or not valid Locator JSON. Safe to call on stored locator strings from any source.
 *
 * Usage:
 * ```
 * val locator: Locator? = progress.locator.toLocator()
 * if (locator != null) { navigator.go(locator) }
 * ```
 */
fun String.toLocator(): Locator? = this.takeIf { it.isNotBlank() }?.let { json ->
    kotlin.runCatching { Locator.fromJSON(JSONObject(json)) }.getOrNull()
}

/**
 * Serializes this [Locator] to its canonical JSON string representation.
 * Uses [Locator.toJSON] which produces the standard Readium Locator object.
 *
 * Usage:
 * ```
 * val json: String = currentLocator.toJsonString()
 * readingProgressRepository.saveProgress(bookUuid, json = json)
 * ```
 */
fun Locator.toJsonString(): String = toJSON().toString()
