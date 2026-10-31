package dev.eggmasonvalue.tracky.core.data

import androidx.room.TypeConverter
import org.json.JSONArray

class TrackyTypeConverters {
    @TypeConverter
    fun recordIdsToStorage(value: List<String>): String = JSONArray(value).toString()

    @TypeConverter
    fun storageToRecordIds(value: String): List<String> = JSONArray(value).let { array ->
        List(array.length()) { array.getString(it) }
    }

    @TypeConverter
    fun dispositionToStorage(value: ImportDisposition?): String? = value?.name

    @TypeConverter
    fun storageToDisposition(value: String?): ImportDisposition? =
        value?.let(ImportDisposition::valueOf)
}
