package com.jxitc.messagehub.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.jxitc.messagehub.domain.model.Memory
import com.jxitc.messagehub.domain.model.SourceType
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 本地记忆表。
 *
 * **附件只存元信息 + 提取文本，不存字节**（v2 起）：
 * 字节按需从 `{serverUrl}/api/v1/blobs/<key>` 拉，图片交给图片库的磁盘缓存，
 * DB 备份因此永远是小的（服务端同理，见 docs/attachments.md）。
 *
 * v1 → v2 新增的列全部可空（[serverMessageId] / [attachmentsJson] / [skippedAttachmentsJson] /
 * [attachmentsSyncedAt]）：SQLite 的 `ALTER TABLE ADD COLUMN` 对新列要求有默认值或用 NULL，
 * 可空 + NULL 表达"这条老记录还没同步过附件"，比 `DEFAULT '[]'` 更诚实，
 * 也避开了 Room 对列默认值的校验差异。迁移见 [MessageHubDatabase.MIGRATION_1_2]。
 */
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
    val uploadRetryCount: Int,
    /** 服务器消息 id（UUID），用于查询附件提取状态。 */
    val serverMessageId: String? = null,
    /** 服务器上的附件列表（JSON，见 [AttachmentMetadataCodec]）。**不含字节**。 */
    val attachmentsJson: String? = null,
    /** "有附件但没存下来"的列表（JSON）。 */
    val skippedAttachmentsJson: String? = null,
    /** 附件状态最后一次同步的时刻。 */
    val attachmentsSyncedAt: LocalDateTime? = null
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
    val attachmentMetadata = AttachmentMetadataCodec.decode(attachmentsJson, skippedAttachmentsJson)
    return Memory(
        id = id,
        title = title,
        content = content,
        sourceType = sourceType,
        metadata = metadata,
        createdAt = createdAt,
        updatedAt = updatedAt,
        isUploaded = isUploaded,
        uploadRetryCount = uploadRetryCount,
        serverMessageId = serverMessageId,
        attachments = attachmentMetadata.attachments,
        skippedAttachments = attachmentMetadata.skipped,
        attachmentsSyncedAt = attachmentsSyncedAt
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
        uploadRetryCount = uploadRetryCount,
        serverMessageId = serverMessageId,
        attachmentsJson = AttachmentMetadataCodec.encodeAttachments(attachments),
        skippedAttachmentsJson = AttachmentMetadataCodec.encodeSkipped(skippedAttachments),
        attachmentsSyncedAt = attachmentsSyncedAt
    )
}
