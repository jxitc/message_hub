package com.jxitc.infoagent.domain.repository

import com.jxitc.infoagent.domain.model.Memory
import com.jxitc.infoagent.domain.model.MemoryCreationRequest
import com.jxitc.infoagent.domain.model.ProcessingResult
import kotlinx.coroutines.flow.Flow

interface MemoryRepository {
    
    suspend fun createMemory(request: MemoryCreationRequest): ProcessingResult<Memory>
    
    suspend fun getMemory(id: Long): ProcessingResult<Memory>
    
    suspend fun getAllMemories(): Flow<List<Memory>>
    
    suspend fun getRecentMemories(limit: Int): Flow<List<Memory>>
    
    suspend fun deleteMemory(id: Long): ProcessingResult<Unit>
    
    suspend fun updateMemoryUploadStatus(id: Long, isUploaded: Boolean): ProcessingResult<Unit>
    
    suspend fun getPendingUploads(): Flow<List<Memory>>
    
    suspend fun incrementRetryCount(id: Long): ProcessingResult<Unit>
    
    suspend fun searchMemories(query: String): Flow<List<Memory>>
}