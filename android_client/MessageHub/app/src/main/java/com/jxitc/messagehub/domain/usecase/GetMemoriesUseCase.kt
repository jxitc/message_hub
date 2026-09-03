package com.jxitc.messagehub.domain.usecase

import com.jxitc.messagehub.domain.model.Memory
import com.jxitc.messagehub.domain.repository.MemoryRepository
import kotlinx.coroutines.flow.Flow

class GetMemoriesUseCase(
    private val memoryRepository: MemoryRepository
) {
    suspend fun getRecentMemories(limit: Int = 20): Flow<List<Memory>> {
        return memoryRepository.getRecentMemories(limit)
    }
    
    suspend fun getAllMemories(): Flow<List<Memory>> {
        return memoryRepository.getAllMemories()
    }
    
    suspend fun searchMemories(query: String): Flow<List<Memory>> {
        return if (query.isBlank()) {
            memoryRepository.getAllMemories()
        } else {
            memoryRepository.searchMemories(query)
        }
    }
}