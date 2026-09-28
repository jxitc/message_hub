package com.jxitc.messagehub.data.remote

import com.google.gson.annotations.SerializedName
import com.jxitc.messagehub.domain.model.ChatMessage
import com.jxitc.messagehub.domain.model.ChatRating
import com.jxitc.messagehub.domain.model.ChatRatingUpdate
import com.jxitc.messagehub.domain.model.QaEntity
import com.jxitc.messagehub.domain.model.QaSource
import com.jxitc.messagehub.domain.model.QaStep
import com.jxitc.messagehub.domain.model.RecallCounts

// ============================================================================
// POST /api/v1/qa/ask  ·  GET /api/v1/qa/turns  ·  POST /api/v1/qa/turns/<id>/rate
//
// 契约见仓库 docs 与 api/v1/qa.py。**所有字段可空**：Gson 走的是 Unsafe 分配、
// 不调用构造函数，缺字段时能给非空 Kotlin 属性塞进 null（见 AttachmentMetadataCodec
// 的同款注释）。可空 + 在 toDomain() 里收敛默认值，坏数据就停在映射层。
// ============================================================================

data class QaAskRequest(
    @SerializedName("question")
    val question: String,
    @SerializedName("conversation_id")
    val conversationId: String,
    @SerializedName("source")
    val source: String
)

/** `POST /api/v1/qa/ask`：`{turn, citations, cost, currency}`。 */
data class QaAskResponse(
    @SerializedName("turn")
    val turn: QaTurnDto? = null,
    /** 只含被引用到的 source（与 `turn.sources` 同结构）。 */
    @SerializedName("citations")
    val citations: List<QaSourceDto>? = null,
    @SerializedName("cost")
    val cost: Double? = null,
    @SerializedName("currency")
    val currency: String? = null
)

data class QaTurnsResponse(
    @SerializedName("count")
    val count: Int? = null,
    @SerializedName("turns")
    val turns: List<QaTurnDto>? = null
)

data class QaTurnResponse(
    @SerializedName("turn")
    val turn: QaTurnDto? = null
)

data class QaRateRequest(
    /** `""` 表示取消评价（服务器契约：空 / none / clear / null 都归一成"没评"）。 */
    @SerializedName("rating")
    val rating: String,
    @SerializedName("note")
    val note: String? = null
)

/** `POST /api/v1/qa/turns/<id>/rate` → `{id, rating, rating_note}`。 */
data class QaRateResponse(
    @SerializedName("id")
    val id: String? = null,
    @SerializedName("rating")
    val rating: String? = null,
    @SerializedName("rating_note")
    val ratingNote: String? = null
)

/**
 * 一个 turn。
 *
 * 历史接口（`GET /qa/turns`）**故意不带 `steps` / `sources`**（见 `to_dict(with_steps=False)`），
 * 所以这两个字段在这里必须可空 —— 缺它们表示"详情还没拉"，不是"这次没有过程"。
 * 点开「过程」时按 id 单独 `GET /qa/turns/<id>` 补齐。
 */
data class QaTurnDto(
    @SerializedName("id")
    val id: String? = null,
    @SerializedName("question")
    val question: String? = null,
    @SerializedName("rewritten")
    val rewritten: String? = null,
    @SerializedName("answer")
    val answer: String? = null,
    @SerializedName("keywords")
    val keywords: List<String>? = null,
    @SerializedName("entities")
    val entities: List<QaEntityDto>? = null,
    @SerializedName("cited")
    val cited: List<Int>? = null,
    @SerializedName("source_count")
    val sourceCount: Int? = null,
    @SerializedName("elapsed_ms")
    val elapsedMs: Int? = null,
    @SerializedName("cost")
    val cost: Double? = null,
    /** 顶层 tokens 用 `prompt` / `completion`（steps 里的那份用 `prompt_tokens`）。 */
    @SerializedName("tokens")
    val tokens: QaTurnTokensDto? = null,
    @SerializedName("source")
    val source: String? = null,
    @SerializedName("conversation_id")
    val conversationId: String? = null,
    @SerializedName("rating")
    val rating: String? = null,
    @SerializedName("rating_note")
    val ratingNote: String? = null,
    @SerializedName("error")
    val error: String? = null,
    @SerializedName("created_at")
    val createdAt: String? = null,
    @SerializedName("steps")
    val steps: List<QaStepDto>? = null,
    @SerializedName("sources")
    val sources: List<QaSourceDto>? = null
)

data class QaTurnTokensDto(
    @SerializedName("prompt")
    val prompt: Int? = null,
    @SerializedName("completion")
    val completion: Int? = null
)

/** steps[].tokens：LLM 原样回的 usage。 */
data class QaStepTokensDto(
    @SerializedName("prompt_tokens")
    val promptTokens: Int? = null,
    @SerializedName("completion_tokens")
    val completionTokens: Int? = null
)

data class QaEntityDto(
    /* 实体的**稳定 id**：由实体名派生的 16 位十六进制字符串，不是自增整数。
       服务端原本是自增整数，但那是按抽取批次完成顺序分配的，每重建一次索引所有
       id 全部重排（点进去会看到另一个人）。改成派生 id 之后重建不再变化。
       手机端目前不拿它做跳转（没有实体页），但类型必须对：Gson 把字符串塞进
       Long 会抛异常，而它是整份响应一起解析的，一个字段错 = 每句提问都失败。 */
    @SerializedName("id")
    val id: String? = null,
    @SerializedName("name")
    val name: String? = null,
    @SerializedName("kind")
    val kind: String? = null,
    @SerializedName("mentions")
    val mentions: Int? = null,
    @SerializedName("asked_as")
    val askedAs: String? = null
)

data class QaSourceDto(
    @SerializedName("id")
    val id: String? = null,
    @SerializedName("timestamp")
    val timestamp: String? = null,
    @SerializedName("type")
    val type: String? = null,
    @SerializedName("sender")
    val sender: String? = null,
    @SerializedName("source_device")
    val sourceDevice: String? = null,
    @SerializedName("text")
    val text: String? = null,
    @SerializedName("routes")
    val routes: List<String>? = null,
    @SerializedName("why")
    val why: List<String>? = null
)

data class QaStepDto(
    @SerializedName("step")
    val step: String? = null,
    @SerializedName("elapsed_ms")
    val elapsedMs: Int? = null,
    @SerializedName("input")
    val input: String? = null,
    @SerializedName("output")
    val output: String? = null,
    @SerializedName("tokens")
    val tokens: QaStepTokensDto? = null,
    @SerializedName("cost")
    val cost: Double? = null,
    @SerializedName("model")
    val model: String? = null,
    @SerializedName("note")
    val note: String? = null,
    @SerializedName("entities")
    val entities: List<QaEntityDto>? = null,
    @SerializedName("keywords")
    val keywords: List<String>? = null,
    @SerializedName("entities_hit")
    val entitiesHit: List<QaEntityDto>? = null,
    @SerializedName("counts")
    val counts: QaCountsDto? = null,
    /* 逐词命中数（哪几个检索词各捞回多少条）与"是否走了日历路由"。
       服务端一直在返回，只是以前没声明 —— 没声明的字段会随缓存一起丢掉，
       以后想在手机上看"是哪个词把这条捞回来的"就没数据了。 */
    @SerializedName("by_text")
    val byText: List<QaTermHitsDto>? = null,
    @SerializedName("schedule_route")
    val scheduleRoute: Boolean? = null,
    @SerializedName("cited")
    val cited: List<Int>? = null,
    @SerializedName("context_chars")
    val contextChars: Int? = null
)

data class QaTermHitsDto(
    @SerializedName("term")
    val term: String? = null,
    @SerializedName("messages")
    val messages: Int? = null
)

data class QaCountsDto(
    @SerializedName("entities_hit")
    val entitiesHit: Int? = null,
    @SerializedName("text_terms")
    val textTerms: Int? = null,
    @SerializedName("candidates")
    val candidates: Int? = null,
    @SerializedName("used")
    val used: Int? = null
)

// ============================================================================
// DTO → 领域模型
// ============================================================================

/**
 * turn → [ChatMessage]。
 *
 * 只在**结构上没法用**时返回 null（没有 id 就没有主键，缓存也无从去重）；
 * `answer` 为空（服务器记了一次失败）**照样映射** —— 要不要留这条是仓储层的判断
 * （见 ChatRepositoryImpl），映射层只负责如实转换。
 */
fun QaTurnDto.toDomain(): ChatMessage? {
    val turnId = id?.trim().orEmpty()
    if (turnId.isEmpty()) return null
    return ChatMessage(
        turnId = turnId,
        question = question.orEmpty(),
        answer = answer.orEmpty(),
        rewritten = rewritten?.takeIf { it.isNotBlank() },
        keywords = keywords.orEmpty().filter { it.isNotBlank() },
        entities = entities.orEmpty().mapNotNull { it.toDomain() },
        sources = sources.orEmpty().mapNotNull { it.toDomain() },
        steps = steps.orEmpty().mapNotNull { it.toDomain() },
        cited = cited.orEmpty(),
        cost = cost,
        tokensPrompt = tokens?.prompt,
        tokensCompletion = tokens?.completion,
        elapsedMs = elapsedMs,
        rating = ChatRating.fromWire(rating),
        ratingNote = ratingNote?.takeIf { it.isNotBlank() },
        createdAt = createdAt.orEmpty(),
        error = error?.takeIf { it.isNotBlank() }
    )
}

fun QaEntityDto.toDomain(): QaEntity? {
    val entityName = name?.trim().orEmpty()
    if (entityName.isEmpty()) return null
    return QaEntity(
        name = entityName,
        kind = kind?.trim()?.takeIf { it.isNotEmpty() },
        mentions = mentions,
        askedAs = askedAs?.trim()?.takeIf { it.isNotEmpty() }
    )
}

fun QaSourceDto.toDomain(): QaSource? {
    val sourceId = id?.trim().orEmpty()
    if (sourceId.isEmpty()) return null
    return QaSource(
        id = sourceId,
        text = text.orEmpty(),
        why = why.orEmpty().filter { it.isNotBlank() },
        routes = routes.orEmpty().filter { it.isNotBlank() },
        sender = sender?.takeIf { it.isNotBlank() },
        type = type?.takeIf { it.isNotBlank() },
        timestamp = timestamp?.takeIf { it.isNotBlank() }
    )
}

fun QaStepDto.toDomain(): QaStep? {
    val stepName = step?.trim().orEmpty()
    if (stepName.isEmpty()) return null
    return QaStep(
        name = stepName,
        elapsedMs = elapsedMs,
        input = input?.takeIf { it.isNotBlank() },
        output = output?.takeIf { it.isNotBlank() },
        tokensPrompt = tokens?.promptTokens,
        tokensCompletion = tokens?.completionTokens,
        cost = cost,
        model = model?.takeIf { it.isNotBlank() },
        note = note?.takeIf { it.isNotBlank() },
        keywords = keywords.orEmpty().filter { it.isNotBlank() },
        entitiesHit = entitiesHit.orEmpty().mapNotNull { it.toDomain() },
        counts = counts?.toDomain()
    )
}

fun QaCountsDto.toDomain(): RecallCounts = RecallCounts(
    entitiesHit = entitiesHit ?: 0,
    textTerms = textTerms ?: 0,
    candidates = candidates ?: 0,
    used = used ?: 0
)

fun QaRateResponse.toDomain(): ChatRatingUpdate? {
    val turnId = id?.trim().orEmpty()
    if (turnId.isEmpty()) return null
    return ChatRatingUpdate(
        turnId = turnId,
        rating = ChatRating.fromWire(rating),
        note = ratingNote?.takeIf { it.isNotBlank() }
    )
}
