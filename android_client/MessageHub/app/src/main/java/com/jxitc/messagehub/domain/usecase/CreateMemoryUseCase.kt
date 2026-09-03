package com.jxitc.messagehub.domain.usecase

import com.jxitc.messagehub.domain.model.Memory
import com.jxitc.messagehub.domain.model.MemoryCreationRequest
import com.jxitc.messagehub.domain.model.ProcessingResult
import com.jxitc.messagehub.domain.repository.MemoryRepository

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