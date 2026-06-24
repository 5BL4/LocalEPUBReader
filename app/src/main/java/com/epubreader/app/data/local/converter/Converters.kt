package com.epubreader.app.data.local.converter

import androidx.room.TypeConverter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class Converters {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun stringListToJson(value: List<String>?): String? =
        value?.let { json.encodeToString(ListSerializer(String.serializer()), it) }

    @TypeConverter
    fun jsonToStringList(value: String?): List<String>? =
        value?.let { json.decodeFromString(ListSerializer(String.serializer()), it) }
}
