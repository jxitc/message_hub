package com.jxitc.messagehub.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {
    
    @Query("SELECT * FROM memories WHERE id = :id")
    suspend fun getMemoryById(id: Long): MemoryEntity?
    
    @Query("SELECT * FROM memories ORDER BY createdAt DESC")
    fun getAllMemories(): Flow<List<MemoryEntity>>
    
    @Query("SELECT * FROM memories ORDER BY createdAt DESC LIMIT :limit")
    fun getRecentMemories(limit: Int): Flow<List<MemoryEntity>>
    
    @Query("SELECT * FROM memories WHERE isUploaded = 0 ORDER BY createdAt ASC")
    fun getPendingUploads(): Flow<List<MemoryEntity>>
    
    @Query("SELECT * FROM memories WHERE content LIKE '%' || :query || '%' OR title LIKE '%' || :query || '%' ORDER BY createdAt DESC")
    fun searchMemories(query: String): Flow<List<MemoryEntity>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: MemoryEntity): Long
    
    @Update
    suspend fun updateMemory(memory: MemoryEntity)
    
    @Query("UPDATE memories SET isUploaded = :isUploaded WHERE id = :id")
    suspend fun updateUploadStatus(id: Long, isUploaded: Boolean)
    
    @Query("UPDATE memories SET uploadRetryCount = uploadRetryCount + 1 WHERE id = :id")
    suspend fun incrementRetryCount(id: Long)
    
    @Delete
    suspend fun deleteMemory(memory: MemoryEntity)
    
    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteMemoryById(id: Long)
    
    @Query("SELECT COUNT(*) FROM memories")
    suspend fun getMemoryCount(): Int
    
    @Query("SELECT COUNT(*) FROM memories WHERE isUploaded = 0")
    suspend fun getPendingUploadCount(): Int

    // ------------------------------------------------------------------
    // 附件与提取状态
    // ------------------------------------------------------------------

    @Query("UPDATE memories SET serverMessageId = :serverMessageId WHERE id = :id")
    suspend fun updateServerMessageId(id: Long, serverMessageId: String?)

    @Query(
        """
        UPDATE memories
        SET attachmentsJson = :attachmentsJson,
            skippedAttachmentsJson = :skippedJson,
            attachmentsSyncedAt = :syncedAt,
            content = :content,
            updatedAt = :updatedAt
        WHERE serverMessageId = :serverMessageId
        """
    )
    suspend fun updateAttachmentStatus(
        serverMessageId: String,
        attachmentsJson: String,
        skippedJson: String,
        syncedAt: String,
        content: String,
        updatedAt: String
    ): Int

    /**
     * 所有"有服务器 id 且同步过附件"的记录，调用方据此挑出还有 pending 的那些。
     * 是否 pending 由 Kotlin 侧解析 JSON 判断（在 SQL 里对 JSON 字符串做 LIKE 太脆）。
     */
    @Query("SELECT * FROM memories WHERE serverMessageId IS NOT NULL AND attachmentsJson IS NOT NULL")
    suspend fun getMemoriesWithServerAttachments(): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE serverMessageId = :serverMessageId LIMIT 1")
    suspend fun getMemoryByServerId(serverMessageId: String): MemoryEntity?
}