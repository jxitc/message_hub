package com.jxitc.infoagent.domain.model

import java.time.LocalDateTime

data class Memory(
    val id: Long = 0,
    val title: String,
    val content: String,
    val sourceType: SourceType,
    val metadata: Map<String, String> = emptyMap(),
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val updatedAt: LocalDateTime = LocalDateTime.now(),
    val isUploaded: Boolean = false,
    val uploadRetryCount: Int = 0
)

enum class SourceType {
    SMS,
    SCREENSHOT,
    SHARE_INTENT,
    NOTIFICATION,
    MANUAL
}

data class MemoryCreationRequest(
    val content: String,
    val sourceType: SourceType,
    val metadata: Map<String, String> = emptyMap()
)