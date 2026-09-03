package com.jxitc.infoagent.data.repository

import com.jxitc.infoagent.data.database.MemoryDao
import com.jxitc.infoagent.data.database.toDomainModel
import com.jxitc.infoagent.data.database.toEntity
import com.jxitc.infoagent.domain.model.Memory
import com.jxitc.infoagent.domain.model.MemoryCreationRequest
import com.jxitc.infoagent.domain.model.ProcessingResult
import com.jxitc.infoagent.domain.repository.MemoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDateTime

class MemoryRepositoryImpl(
    private val memoryDao: MemoryDao
) : MemoryRepository {
    
    override suspend fun createMemory(request: MemoryCreationRequest): ProcessingResult<Memory> {
        return try {
            val memory = Memory(
                title = generateTitle(request.content),
                content = request.content,
                sourceType = request.sourceType,
                metadata = request.metadata,
                createdAt = LocalDateTime.now(),
                updatedAt = LocalDateTime.now(),
                isUploaded = false,
                uploadRetryCount = 0
            )
            
            android.util.Log.d("MemoryRepo", "Creating memory with title: '${memory.title}'")
            val id = memoryDao.insertMemory(memory.toEntity())
            android.util.Log.d("MemoryRepo", "Memory created with ID: $id")
            
            val savedMemory = memory.copy(id = id)
            ProcessingResult.Success(savedMemory)
        } catch (e: Exception) {
            android.util.Log.e("MemoryRepo", "Failed to create memory", e)
            ProcessingResult.Error("Failed to create memory", e)
        }
    }
    
    override suspend fun getMemory(id: Long): ProcessingResult<Memory> {
        return try {
            val entity = memoryDao.getMemoryById(id)
            if (entity != null) {
                ProcessingResult.Success(entity.toDomainModel())
            } else {
                ProcessingResult.Error("Memory not found")
            }
        } catch (e: Exception) {
            ProcessingResult.Error("Failed to get memory", e)
        }
    }
    
    override suspend fun getAllMemories(): Flow<List<Memory>> {
        return memoryDao.getAllMemories().map { entities ->
            entities.map { it.toDomainModel() }
        }
    }
    
    override suspend fun getRecentMemories(limit: Int): Flow<List<Memory>> {
        return memoryDao.getRecentMemories(limit).map { entities ->
            val memories = entities.map { it.toDomainModel() }
            android.util.Log.d("MemoryRepo", "getRecentMemories: Found ${memories.size} memories")
            memories.forEachIndexed { index, memory ->
                android.util.Log.d("MemoryRepo", "Memory ${index + 1}: ID=${memory.id}, title='${memory.title}', isUploaded=${memory.isUploaded}")
            }
            memories
        }
    }
    
    override suspend fun deleteMemory(id: Long): ProcessingResult<Unit> {
        return try {
            memoryDao.deleteMemoryById(id)
            ProcessingResult.Success(Unit)
        } catch (e: Exception) {
            ProcessingResult.Error("Failed to delete memory", e)
        }
    }
    
    override suspend fun updateMemoryUploadStatus(id: Long, isUploaded: Boolean): ProcessingResult<Unit> {
        return try {
            // Debug: Check current state before update
            val beforeUpdate = memoryDao.getMemoryById(id)
            android.util.Log.d("MemoryRepo", "BEFORE UPDATE: Memory ID $id, isUploaded = ${beforeUpdate?.isUploaded}")
            
            memoryDao.updateUploadStatus(id, isUploaded)
            
            // Debug: Check state after update  
            val afterUpdate = memoryDao.getMemoryById(id)
            android.util.Log.d("MemoryRepo", "AFTER UPDATE: Memory ID $id, isUploaded = ${afterUpdate?.isUploaded}")
            
            ProcessingResult.Success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("MemoryRepo", "Failed to update upload status for ID $id", e)
            ProcessingResult.Error("Failed to update upload status", e)
        }
    }
    
    override suspend fun getPendingUploads(): Flow<List<Memory>> {
        return memoryDao.getPendingUploads().map { entities ->
            entities.map { it.toDomainModel() }
        }
    }
    
    override suspend fun incrementRetryCount(id: Long): ProcessingResult<Unit> {
        return try {
            memoryDao.incrementRetryCount(id)
            ProcessingResult.Success(Unit)
        } catch (e: Exception) {
            ProcessingResult.Error("Failed to increment retry count", e)
        }
    }
    
    override suspend fun searchMemories(query: String): Flow<List<Memory>> {
        return memoryDao.searchMemories(query).map { entities ->
            entities.map { it.toDomainModel() }
        }
    }
    
    private fun generateTitle(content: String): String {
        val words = content.trim().split("\\s+".toRegex())
        return when {
            words.isEmpty() -> "Empty Memory"
            words.size <= 8 -> content.trim()
            else -> words.take(8).joinToString(" ") + "..."
        }
    }
}