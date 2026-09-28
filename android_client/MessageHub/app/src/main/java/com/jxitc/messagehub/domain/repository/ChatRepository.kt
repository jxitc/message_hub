package com.jxitc.messagehub.domain.repository

import com.jxitc.messagehub.domain.model.ChatMessage
import com.jxitc.messagehub.domain.model.ChatRating
import com.jxitc.messagehub.domain.model.ProcessingResult
import kotlinx.coroutines.flow.Flow

/**
 * 「问知识库」的聊天记录。
 *
 * **事实来源是服务器**（`qa_turns` 表），本地 Room 只是缓存：
 * 打开页面先拿缓存立刻显示（断网也能翻历史），同时拉 `/qa/turns` 覆盖本地。
 * 不做"本地优先再同步"—— 那样两边各有一份真相，冲突了就没人知道谁对。
 */
interface ChatRepository {

    /** 本地缓存的问答，按时间正序（旧 → 新）。UI 直接订阅它，写入后自动刷新。 */
    fun observeMessages(): Flow<List<ChatMessage>>

    /**
     * 以服务器为准刷新本地缓存。返回被写入的条数。
     *
     * 只在网络失败时返回 [ProcessingResult.Error]，此时本地缓存原样保留。
     */
    suspend fun refreshFromServer(limit: Int = DEFAULT_HISTORY_LIMIT): ProcessingResult<Int>

    /** 提问并落进本地缓存。 */
    suspend fun ask(question: String): ProcessingResult<ChatMessage>

    /** 按需补一条历史记录的完整过程（历史接口不带 steps/sources）。 */
    suspend fun loadDetail(turnId: String): ProcessingResult<ChatMessage>

    /** 评价；[rating] 传 null 表示取消评价。服务器确认后才写本地。 */
    suspend fun rate(
        turnId: String,
        rating: ChatRating?,
        note: String? = null
    ): ProcessingResult<Unit>

    companion object {
        /** 一次拉多少条历史。聊天页不需要翻更早的（服务器有，网页端能查）。 */
        const val DEFAULT_HISTORY_LIMIT = 30
    }
}
