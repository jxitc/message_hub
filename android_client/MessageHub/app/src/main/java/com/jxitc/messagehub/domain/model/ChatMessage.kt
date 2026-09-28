package com.jxitc.messagehub.domain.model


/**
 * 一次「问知识库」的问答（服务器叫 turn）。
 *
 * 为什么把「过程」也放在这一层：答错之后要能回答"是哪一步错了"—— 改写丢了主语？
 * 实体没抽到？召回是空的？这些字段（[rewritten] / [entities] / [keywords] / [steps] /
 * [sources]）就是那条链路，UI 的「过程」面板直接照它们渲染，不再二次加工。
 *
 * [turnId] 是服务器的 turn id，也是本地缓存表的主键 —— **服务器才是聊天记录的事实来源**，
 * 本地这份只是缓存（见 ChatRepository）。
 */
data class ChatMessage(
    val turnId: String,
    val question: String,
    val answer: String,
    val rewritten: String? = null,
    val keywords: List<String> = emptyList(),
    /** 问句里抽出的实体（`{name, kind}`）。 */
    val entities: List<QaEntity> = emptyList(),
    /** 召回原文，顺序即回答里 `[1] [2]` 的编号顺序。 */
    val sources: List<QaSource> = emptyList(),
    val steps: List<QaStep> = emptyList(),
    /** 回答真正引用了的编号（sources 的 1-based 下标）。 */
    val cited: List<Int> = emptyList(),
    val cost: Double? = null,
    val tokensPrompt: Int? = null,
    val tokensCompletion: Int? = null,
    val elapsedMs: Int? = null,
    val rating: ChatRating? = null,
    val ratingNote: String? = null,
    /** 服务器原样给的 ISO 时间（naive UTC），显示时再转本地时区，见 [ChatFormat]. */
    val createdAt: String = "",
    /**
     * 服务器记下的失败原因。**只用于"这次提问失败了"的当场提示**：
     * 这种记录不进本地缓存（一条没有回答的气泡没有意义，服务器那边已经留痕）。
     */
    val error: String? = null
) {
    /** 详情（steps/sources）还没拉过。历史接口 `GET /qa/turns` 故意不带这两样。 */
    val needsDetail: Boolean get() = steps.isEmpty() && sources.isEmpty()
}

/** 抽出的实体。召回步骤里的 `entities_hit` 会多带 [mentions] / [askedAs]。 */
data class QaEntity(
    val name: String,
    val kind: String? = null,
    val mentions: Int? = null,
    val askedAs: String? = null
)

/**
 * 一条被召回的原文。
 *
 * [why] 是这次召回**唯一值得给用户看的东西**（"实体「简单心理」"、"正文含「退款」"）——
 * 没有它，用户看到一堆原文也不知道为什么是这些。
 */
data class QaSource(
    val id: String,
    val text: String,
    val why: List<String> = emptyList(),
    val routes: List<String> = emptyList(),
    val sender: String? = null,
    val type: String? = null,
    val timestamp: String? = null
)

/** pipeline 里的一步：rewrite / extract / recall / answer。 */
data class QaStep(
    val name: String,
    val elapsedMs: Int? = null,
    val input: String? = null,
    val output: String? = null,
    val tokensPrompt: Int? = null,
    val tokensCompletion: Int? = null,
    val cost: Double? = null,
    val model: String? = null,
    val note: String? = null,
    // ---- 只有 recall 这一步有 ----
    val keywords: List<String> = emptyList(),
    val entitiesHit: List<QaEntity> = emptyList(),
    val counts: RecallCounts? = null
)

/** recall 步骤的统计：`{"entities_hit":5,"text_terms":13,"candidates":96,"used":40}`。 */
data class RecallCounts(
    val entitiesHit: Int = 0,
    val textTerms: Int = 0,
    val candidates: Int = 0,
    val used: Int = 0
)

/**
 * 一次评价的结果（服务器回什么就是什么）。
 *
 * 本地不自己推断：[rating] 用服务器回的最终值 —— "再点一次＝取消"这种规则万一
 * 以后在服务端改了，本地跟着服务器走，不会两边不一致。
 */
data class ChatRatingUpdate(
    val turnId: String,
    val rating: ChatRating?,
    val note: String? = null
)

/** 人工评价，与服务器契约一致（`good` / `bad`；null 表示没评过）。 */
enum class ChatRating(val wire: String) {
    GOOD("good"),
    BAD("bad");

    companion object {
        fun fromWire(raw: String?): ChatRating? {
            val value = raw?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == value }
        }
    }
}

/**
 * 手机端的问答会话。
 *
 * 服务器按 `conversation_id` 过滤历史，网页端用 `web`；手机端用**本机 deviceId**
 * （形如 `OPPO-Find-X7-db1792`，就是 source_device_id 一直在用的那个）。
 *
 * 起初这里固定成 `"android"`，理由是"换台手机也该看到同一份记录"。实际用起来是反的：
 * 两台手机上问的问题、看过的召回过程是两回事，混在一条历史里既翻不动也说不清，
 * 而且 `source=android` 已经把"来自手机"这个事实记下来了，不需要会话 id 再记一遍。
 *
 * deviceId 在 AppPreferences 里，服务端 conversation_id 上限 64 字符。
 */
object QaThread {
    const val SOURCE = "android"

    /** 拿不到 deviceId 时的兜底（理论上不会发生：首次启动就生成并持久化）。 */
    const val FALLBACK_CONVERSATION_ID = "android"

    /** 收字符串而不是 AppPreferences：后者要 Context，收它这条规则就没法在单测里验。 */
    fun conversationId(deviceId: String?): String =
        deviceId?.trim().orEmpty().ifBlank { FALLBACK_CONVERSATION_ID }.take(64)
}
