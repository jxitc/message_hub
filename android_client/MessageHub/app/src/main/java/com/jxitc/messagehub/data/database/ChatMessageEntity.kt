package com.jxitc.messagehub.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.jxitc.messagehub.domain.model.ChatMessage
import com.jxitc.messagehub.domain.model.ChatRating

/**
 * 问答聊天记录的**本地缓存**表（v3 起）。
 *
 * 事实来源是服务器的 `qa_turns` 表，这张表只为了两件事：离线能翻历史、打开页面
 * 立刻有东西看（不用等网络）。所以它**不承担任何"本地优先"的职责**：服务器拉回什么
 * 就覆盖什么，服务器上没有的（在拉取窗口内的）就删掉。
 *
 * 结构化字段一律以 JSON 字符串入列（[ChatPayloadCodec]）：这些是"一条记录的附件式细节"
 * （关键词/实体/召回原文/每步耗时），拆成 5 张关联表换不来任何查询能力 ——
 * 唯一的查询是"按时间倒序取全部"。
 *
 * 失败的那次提问（服务器记了 turn，但 `answer` 为空 / 带 `error`）**不进这张表**：
 * 界面上一句没有回答的气泡没有意义，而服务器那边已经留痕了。
 */
@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey
    val turnId: String,
    val question: String,
    val answer: String,
    val rewritten: String?,
    val keywordsJson: String?,
    val entitiesJson: String?,
    val sourcesJson: String?,
    val stepsJson: String?,
    val citedJson: String?,
    val cost: Double?,
    val tokensPrompt: Int?,
    val tokensCompletion: Int?,
    val elapsedMs: Int?,
    /** `good` / `bad` / NULL（没评）。存字符串而不是序号：加了新取值也不会错位。 */
    val rating: String?,
    val ratingNote: String?,
    /**
     * 服务器给的 ISO 时间原样存（naive UTC）。**同时当排序键**：
     * 服务器保证同一种格式，字典序即时间序，比再存一个 epoch 列少一份可能不一致的数据。
     * 显示时才转本地时区，见 ChatFormat.timestamp。
     */
    val createdAt: String
)

fun ChatMessageEntity.toDomain(): ChatMessage = ChatMessage(
    turnId = turnId,
    question = question,
    answer = answer,
    rewritten = rewritten,
    keywords = ChatPayloadCodec.decodeKeywords(keywordsJson),
    entities = ChatPayloadCodec.decodeEntities(entitiesJson),
    sources = ChatPayloadCodec.decodeSources(sourcesJson),
    steps = ChatPayloadCodec.decodeSteps(stepsJson),
    cited = ChatPayloadCodec.decodeCited(citedJson),
    cost = cost,
    tokensPrompt = tokensPrompt,
    tokensCompletion = tokensCompletion,
    elapsedMs = elapsedMs,
    rating = ChatRating.fromWire(rating),
    ratingNote = ratingNote,
    createdAt = createdAt
)

fun ChatMessage.toEntity(): ChatMessageEntity = ChatMessageEntity(
    turnId = turnId,
    question = question,
    answer = answer,
    rewritten = rewritten,
    keywordsJson = ChatPayloadCodec.encodeKeywords(keywords),
    entitiesJson = ChatPayloadCodec.encodeEntities(entities),
    sourcesJson = ChatPayloadCodec.encodeSources(sources),
    stepsJson = ChatPayloadCodec.encodeSteps(steps),
    citedJson = ChatPayloadCodec.encodeCited(cited),
    cost = cost,
    tokensPrompt = tokensPrompt,
    tokensCompletion = tokensCompletion,
    elapsedMs = elapsedMs,
    rating = rating?.wire,
    ratingNote = ratingNote,
    createdAt = createdAt
)
