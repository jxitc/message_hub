package com.jxitc.infoagent.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.jxitc.infoagent.domain.model.Memory
import com.jxitc.infoagent.domain.model.SourceType
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Entity(tableName = "memories")
@TypeConverters(Converters::class)
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val content: String,
    val sourceType: SourceType,
    val metadata: Map<String, String>,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val isUploaded: Boolean,
    val uploadRetryCount: Int
)

class Converters {
    private val gson = Gson()
    
    @TypeConverter
    fun fromStringMap(value: Map<String, String>): String {
        return gson.toJson(value)
    }
    
    @TypeConverter
    fun toStringMap(value: String): Map<String, String> {
        val mapType = object : TypeToken<Map<String, String>>() {}.type
        return gson.fromJson(value, mapType) ?: emptyMap()
    }
    
    @TypeConverter
    fun fromSourceType(sourceType: SourceType): String {
        return sourceType.name
    }
    
    @TypeConverter
    fun toSourceType(sourceType: String): SourceType {
        return SourceType.valueOf(sourceType)
    }
    
    @TypeConverter
    fun fromLocalDateTime(dateTime: LocalDateTime): String {
        return dateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    }
    
    @TypeConverter
    fun toLocalDateTime(dateTimeString: String): LocalDateTime {
        return LocalDateTime.parse(dateTimeString, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    }
}

fun MemoryEntity.toDomainModel(): Memory {
    return Memory(
        id = id,
        title = title,
        content = content,
        sourceType = sourceType,
        metadata = metadata,
        createdAt = createdAt,
        updatedAt = updatedAt,
        isUploaded = isUploaded,
        uploadRetryCount = uploadRetryCount
    )
}

fun Memory.toEntity(): MemoryEntity {
    return MemoryEntity(
        id = id,
        title = title,
        content = content,
        sourceType = sourceType,
        metadata = metadata,
        createdAt = createdAt,
        updatedAt = updatedAt,
        isUploaded = isUploaded,
        uploadRetryCount = uploadRetryCount
    )
}