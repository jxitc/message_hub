package com.jxitc.messagehub.data.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 问答聊天记录（本地缓存，事实来源在服务器）。
 *
 * 读只有一种姿势：按时间**正序**整表取（聊天页就是从旧到新往下滚），
 * 所以没有分页/v1 那套 search —— 这次问的问题本来就少，加这些只是噪音。
 */
@Dao
interface ChatMessageDao {

    @Query("SELECT * FROM chat_messages ORDER BY createdAt ASC, turnId ASC")
    fun observeMessages(): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE turnId = :turnId LIMIT 1")
    suspend fun getById(turnId: String): ChatMessageEntity?

    /**
     * 拉服务器历史时用：窗口内的本地行，用来判断"服务器已经没有这条了"。
     * 上界**不含**（`<`）—— 提问的写入与历史拉取是并发的，刚落库的那条比任何
     * 拉取结果都新，不能被当成幽灵行删掉。
     */
    @Query("SELECT * FROM chat_messages WHERE createdAt >= :since AND createdAt < :before")
    suspend fun getWindow(since: String, before: String): List<ChatMessageEntity>

    @Upsert
    suspend fun upsert(message: ChatMessageEntity)

    @Query("UPDATE chat_messages SET rating = :rating, ratingNote = :note WHERE turnId = :turnId")
    suspend fun updateRating(turnId: String, rating: String?, note: String?)

    @Query("DELETE FROM chat_messages WHERE turnId IN (:turnIds)")
    suspend fun deleteByIds(turnIds: List<String>)

    @Query("SELECT COUNT(*) FROM chat_messages")
    suspend fun count(): Int
}
