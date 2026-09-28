package com.jxitc.messagehub.data.remote

import com.jxitc.messagehub.domain.model.ChatMessage
import com.jxitc.messagehub.domain.model.ChatRating
import com.jxitc.messagehub.domain.model.ChatRatingUpdate
import com.jxitc.messagehub.domain.model.ProcessingResult

/**
 * 「问知识库」的四件事，从 [MessageHubApiClient] 里抽出来的接口。
 *
 * 存在的唯一理由是**可测**：仓储层要能在 JVM 单测里跑（Room 用假 DAO、网络用假实现），
 * 而 MessageHubApiClient 得先有 `Context` 才造得出来。业务逻辑一行都不在这里。
 */
interface QaRemoteSource {

    /** 提问。失败（502/503/400）时返回 [ProcessingResult.Error]，一次失败的提问也会写进聊天。 */
    suspend fun askQuestion(question: String): ProcessingResult<ChatMessage>

    /** 聊天历史（最新在前）。服务器返回的是**没有 steps/sources** 的简版。 */
    suspend fun fetchTurns(limit: Int = 30): ProcessingResult<List<ChatMessage>>

    /** 单条 turn 的完整过程。 */
    suspend fun fetchTurn(turnId: String): ProcessingResult<ChatMessage>

    /** 评价；[rating] 传 null 表示取消评价。返回值以服务器为准。 */
    suspend fun rateTurn(
        turnId: String,
        rating: ChatRating?,
        note: String? = null
    ): ProcessingResult<ChatRatingUpdate>
}
