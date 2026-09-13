package com.jxitc.messagehub.domain.repository

import com.jxitc.messagehub.domain.model.Memory
import com.jxitc.messagehub.domain.model.MemoryCreationRequest
import com.jxitc.messagehub.domain.model.MessageAttachmentDetail
import com.jxitc.messagehub.domain.model.ProcessingResult
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

    // ------------------------------------------------------------------
    // 附件与提取状态（离线可看：元信息 + 提取文本进 Room，字节不进）
    // ------------------------------------------------------------------

    /** 记下服务器返回的消息 id（附件状态要按它轮询）。 */
    suspend fun attachServerMessageId(localId: Long, serverMessageId: String): ProcessingResult<Unit>

    /**
     * 把一次 `GET /api/v1/messages/<id>` 的结果写回本地：
     * 附件列表 + 跳过列表 + （提取文本写进正文时的）最新 content。
     * 返回被更新的记录条数（0 表示本地没有这条 —— 例如消息不是本机上传的）。
     */
    suspend fun saveAttachmentDetail(detail: MessageAttachmentDetail): ProcessingResult<Int>

    /** 本地所有"还有附件在 pending"的消息：屏幕可见时按退避轮询它们。 */
    suspend fun getMemoriesAwaitingAttachments(): List<Memory>
}
