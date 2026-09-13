package com.jxitc.messagehub.data.repository

import com.jxitc.messagehub.data.database.AttachmentMetadataCodec
import com.jxitc.messagehub.data.database.MemoryDao
import com.jxitc.messagehub.data.database.toDomainModel
import com.jxitc.messagehub.data.database.toEntity
import com.jxitc.messagehub.domain.model.Memory
import com.jxitc.messagehub.domain.model.MemoryCreationRequest
import com.jxitc.messagehub.domain.model.MessageAttachmentDetail
import com.jxitc.messagehub.domain.model.ProcessingResult
import com.jxitc.messagehub.domain.repository.MemoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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

    // ------------------------------------------------------------------
    // 附件与提取状态（元信息 + 文本进库，字节不进）
    // ------------------------------------------------------------------

    override suspend fun attachServerMessageId(localId: Long, serverMessageId: String): ProcessingResult<Unit> {
        return try {
            memoryDao.updateServerMessageId(localId, serverMessageId)
            ProcessingResult.Success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("MemoryRepo", "Failed to store server message id for $localId", e)
            ProcessingResult.Error("Failed to store server message id", e)
        }
    }

    /**
     * 把 `GET /api/v1/messages/<id>` 的结果写回本地。
     *
     * content 也一起更新：提取文本有可能被服务端写进了正文（`applied_to_content`），
     * 而本地这份正文是列表与详情的数据源 —— 不更新的话，用户看到的还是"【附件】xxx"占位。
     */
    override suspend fun saveAttachmentDetail(detail: MessageAttachmentDetail): ProcessingResult<Int> {
        return try {
            val existing = memoryDao.getMemoryByServerId(detail.serverMessageId)
                ?: return ProcessingResult.Success(0)
            val now = LocalDateTime.now()
            val content = if (detail.content.isNotBlank()) detail.content else existing.content
            val updated = memoryDao.updateAttachmentStatus(
                serverMessageId = detail.serverMessageId,
                attachmentsJson = AttachmentMetadataCodec.encodeAttachments(detail.attachments),
                skippedJson = AttachmentMetadataCodec.encodeSkipped(detail.skipped),
                syncedAt = now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                content = content,
                updatedAt = now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            )
            ProcessingResult.Success(updated)
        } catch (e: Exception) {
            android.util.Log.e("MemoryRepo", "Failed to save attachment status", e)
            ProcessingResult.Error("Failed to save attachment status", e)
        }
    }

    override suspend fun getMemoriesAwaitingAttachments(): List<Memory> {
        return try {
            memoryDao.getMemoriesWithServerAttachments()
                .map { it.toDomainModel() }
                .filter { it.hasPendingExtraction && !it.serverMessageId.isNullOrBlank() }
        } catch (e: Exception) {
            android.util.Log.e("MemoryRepo", "Failed to load memories awaiting attachments", e)
            emptyList()
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