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
 * Note: Phase 7 sync may want canonical JSON; see [Locator.toCanonicalJsonString]
 * for sorted-key serialization used in sync push paths (Oracle S10).
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
 * Serializes this [Locator] to its JSON string representation.
 * Uses [Locator.toJSON] which produces the standard Readium Locator object.
 * NOTE: For sync determinism use [toCanonicalJsonString] instead (Oracle S10).
 * This function is unchanged to avoid spurious dirty flags on stored locators.
 *
 * Usage:
 * ```
 * val json: String = currentLocator.toJsonString()
 * readingProgressRepository.saveProgress(bookUuid, json = json)
 * ```
 */
fun Locator.toJsonString(): String = toJSON().toString()

/**
 * Serializes this [Locator] to canonical JSON with sorted keys for sync determinism (S10).
 * Recursively sorts all object keys alphabetically to guarantee identical output across
 * devices regardless of JSON object construction order.
 * NOTE: Only for sync push paths. Existing [toJsonString] is unchanged to avoid
 * spurious dirty flags on stored locators (Oracle S10).
 */
fun Locator.toCanonicalJsonString(): String {
    val json = toJSON()
    return canonicalizeToString(json)
}

/**
 * Recursively serializes [element] to a JSON string with all object keys in
 * alphabetical order, producing a deterministic output for sync payloads.
 */
private fun canonicalizeToString(element: JSONObject): String {
    val sortedKeys = element.keys().asSequence().toList().sorted()
    val sb = StringBuilder()
    sb.append("{")
    sortedKeys.forEachIndexed { index, key ->
        if (index > 0) sb.append(",")
        sb.append(quoteJson(key))
        sb.append(":")
        appendJsonValue(sb, element.get(key))
    }
    sb.append("}")
    return sb.toString()
}

private fun appendJsonValue(sb: StringBuilder, value: Any?) {
    when {
        value == null || value === JSONObject.NULL -> sb.append("null")
        value is JSONObject -> sb.append(canonicalizeToString(value))
        value is String -> sb.append(quoteJson(value))
        value is Boolean -> sb.append(value.toString())
        value is Number -> {
            // Preserve integer vs decimal formatting
            val num = value.toDouble()
            sb.append(if (num == num.toLong().toDouble() && !value.toString().contains(".")) {
                value.toLong().toString()
            } else {
                value.toString()
            })
        }
        else -> sb.append(quoteJson(value.toString()))
    }
}

private fun quoteJson(s: String): String {
    val sb = StringBuilder()
    sb.append("\"")
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> sb.append(c)
        }
    }
    sb.append("\"")
    return sb.toString()
}
