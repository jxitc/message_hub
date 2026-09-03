package com.jxitc.infoagent.domain.usecase

import com.jxitc.infoagent.domain.model.Memory
import com.jxitc.infoagent.domain.model.MemoryCreationRequest
import com.jxitc.infoagent.domain.model.ProcessingResult
import com.jxitc.infoagent.domain.repository.MemoryRepository

class CreateMemoryUseCase(
    private val memoryRepository: MemoryRepository
) {
    suspend fun execute(request: MemoryCreationRequest): ProcessingResult<Memory> {
        return when {
            request.content.isBlank() -> {
                ProcessingResult.Error("Content cannot be empty")
            }
            request.content.length > 10000 -> {
                ProcessingResult.Error("Content too long (max 10000 characters)")
            }
            else -> {
                memoryRepository.createMemory(request)
            }
        }
    }
}