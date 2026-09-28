package com.jxitc.messagehub.data.database

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.jxitc.messagehub.data.remote.QaCountsDto
import com.jxitc.messagehub.data.remote.QaEntityDto
import com.jxitc.messagehub.data.remote.QaSourceDto
import com.jxitc.messagehub.data.remote.QaStepDto
import com.jxitc.messagehub.data.remote.QaStepTokensDto
import com.jxitc.messagehub.data.remote.toDomain
import com.jxitc.messagehub.domain.model.QaEntity
import com.jxitc.messagehub.domain.model.QaSource
import com.jxitc.messagehub.domain.model.QaStep

/**
 * 问答记录里那几个结构化字段在 Room 里的 JSON 编解码（纯 JVM，有单测）。
 *
 * 两条硬规则，跟 [AttachmentMetadataCodec] 一致：
 *  1. **解码永不抛异常**：一列坏 JSON 只该让「过程」面板显示为空，不能把整个聊天页搞崩；
 *  2. 编解码直接用 `data/remote` 的那套 DTO（字段全可空），不另造一份"缓存专用"结构 ——
 *    一份映射只有一处会错。JSON 也因此与接口同形，抓日志时能直接对着契约读。
 *
 * 代价：接口字段改名会让老缓存解不出来（退化成"过程是空的"）。可以接受 ——
 * 点开「过程」会按 id 重新 `GET /qa/turns/<id>` 补回来。
 */
object ChatPayloadCodec {

    private val gson = Gson()

    private val stringListType = object : TypeToken<List<String>>() {}.type
    private val intListType = object : TypeToken<List<Int>>() {}.type
    private val entityListType = object : TypeToken<List<QaEntityDto>>() {}.type
    private val sourceListType = object : TypeToken<List<QaSourceDto>>() {}.type
    private val stepListType = object : TypeToken<List<QaStepDto>>() {}.type

    fun encodeKeywords(values: List<String>): String? = encodeOrNull(values) { gson.toJson(it) }

    fun decodeKeywords(json: String?): List<String> =
        decodeList<String>(json, stringListType).orEmpty().filter { it.isNotBlank() }

    fun encodeEntities(values: List<QaEntity>): String? =
        encodeOrNull(values) { gson.toJson(it.map(::toDto)) }

    fun decodeEntities(json: String?): List<QaEntity> =
        decodeList<QaEntityDto>(json, entityListType).orEmpty().mapNotNull { it.toDomain() }

    fun encodeSources(values: List<QaSource>): String? =
        encodeOrNull(values) { gson.toJson(it.map(::toDto)) }

    fun decodeSources(json: String?): List<QaSource> =
        decodeList<QaSourceDto>(json, sourceListType).orEmpty().mapNotNull { it.toDomain() }

    fun encodeSteps(values: List<QaStep>): String? =
        encodeOrNull(values) { gson.toJson(it.map(::toDto)) }

    fun decodeSteps(json: String?): List<QaStep> =
        decodeList<QaStepDto>(json, stepListType).orEmpty().mapNotNull { it.toDomain() }

    fun encodeCited(values: List<Int>): String? = encodeOrNull(values) { gson.toJson(it) }

    fun decodeCited(json: String?): List<Int> = decodeList<Int>(json, intListType).orEmpty()

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 空集合存成 NULL（而不是 `[]`）：老行/空行在 SQL 里就是"没有"，与
     * MemoryEntity 的附件列保持同一套读法。
     */
    private fun <T> encodeOrNull(values: List<T>, encode: (List<T>) -> String): String? =
        if (values.isEmpty()) null else encode(values)

    private fun <T> decodeList(json: String?, type: java.lang.reflect.Type): List<T>? {
        val raw = json?.takeIf { it.isNotBlank() } ?: return null
        return try {
            gson.fromJson<List<T>>(raw, type) ?: emptyList()
        } catch (e: Exception) {
            null
        }
    }

    private fun toDto(entity: QaEntity) = QaEntityDto(
        name = entity.name,
        kind = entity.kind,
        mentions = entity.mentions,
        askedAs = entity.askedAs
    )

    private fun toDto(source: QaSource) = QaSourceDto(
        id = source.id,
        timestamp = source.timestamp,
        type = source.type,
        sender = source.sender,
        text = source.text,
        routes = source.routes,
        why = source.why
    )

    private fun toDto(step: QaStep) = QaStepDto(
        step = step.name,
        elapsedMs = step.elapsedMs,
        input = step.input,
        output = step.output,
        tokens = if (step.tokensPrompt == null && step.tokensCompletion == null) null
        else QaStepTokensDto(step.tokensPrompt, step.tokensCompletion),
        cost = step.cost,
        model = step.model,
        note = step.note,
        keywords = step.keywords.takeIf { it.isNotEmpty() },
        entitiesHit = step.entitiesHit.takeIf { it.isNotEmpty() }?.map(::toDto),
        counts = step.counts?.let { QaCountsDto(it.entitiesHit, it.textTerms, it.candidates, it.used) }
    )
}
