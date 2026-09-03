package com.jxitc.infoagent.data.database

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
}