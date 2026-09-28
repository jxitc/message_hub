package com.jxitc.messagehub.data.database

import com.jxitc.messagehub.domain.model.ChatMessage
import com.jxitc.messagehub.domain.model.ChatRating
import com.jxitc.messagehub.domain.model.QaEntity
import com.jxitc.messagehub.domain.model.QaSource
import com.jxitc.messagehub.domain.model.QaStep
import com.jxitc.messagehub.domain.model.RecallCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 问答记录在 Room 里的 JSON 编解码。
 *
 * 两条要钉住的：**往返不丢东西**（过程面板全靠这几个字段），以及
 * **坏 JSON 只让面板空掉，不能让聊天页崩**。
 */
class ChatPayloadCodecTest {

    private val source = QaSource(
        id = "11111111-2222-3333-4444-555555555555",
        text = "尾号9303卡9月15日20:23支出…",
        why = listOf("实体「简单心理」", "正文含「支出」"),
        routes = listOf("entity", "text"),
        sender = "95588",
        type = "SMS",
        timestamp = "2026-09-15T12:24:02"
    )

    private val recallStep = QaStep(
        name = "recall",
        elapsedMs = 164,
        keywords = listOf("MOT", "车辆年检"),
        entitiesHit = listOf(QaEntity(name = "简单心理", kind = "org", mentions = 21, askedAs = "简单心理")),
        counts = RecallCounts(entitiesHit = 5, textTerms = 13, candidates = 96, used = 40)
    )

    private val rewriteStep = QaStep(
        name = "rewrite",
        elapsedMs = 1294,
        input = "我那辆车的 MOT 什么时候到期？",
        output = "用户的车的 MOT 到期时间是什么时候？",
        tokensPrompt = 11197,
        tokensCompletion = 178,
        cost = 0.0021,
        model = "deepseek-chat"
    )

    @Test
    fun sourcesAndStepsRoundTripThroughJson() {
        val sources = ChatPayloadCodec.decodeSources(ChatPayloadCodec.encodeSources(listOf(source)))
        assertEquals(1, sources.size)
        assertEquals(source, sources.first())

        val steps = ChatPayloadCodec.decodeSteps(ChatPayloadCodec.encodeSteps(listOf(rewriteStep, recallStep)))
        assertEquals(listOf("rewrite", "recall"), steps.map { it.name })
        assertEquals(rewriteStep, steps[0])
        assertEquals(recallStep, steps[1])
        // 召回统计与 entities_hit 是「过程」面板的核心，单独再钉一次
        assertEquals(96, steps[1].counts?.candidates)
        assertEquals(40, steps[1].counts?.used)
        assertEquals(21, steps[1].entitiesHit.first().mentions)
        assertEquals("简单心理", steps[1].entitiesHit.first().askedAs)
    }

    @Test
    fun entityRoundTripThroughTheWholeRow() {
        val message = ChatMessage(
            turnId = "t-1",
            question = "我那辆车的 MOT 什么时候到期？",
            answer = "MOT 到期日是 2026-10-16 [1]。",
            rewritten = "用户的车的 MOT 到期时间是什么时候？",
            keywords = listOf("MOT", "车辆年检"),
            entities = listOf(QaEntity(name = "简单心理", kind = "org")),
            sources = listOf(source),
            steps = listOf(rewriteStep, recallStep),
            cited = listOf(1),
            cost = 0.0229,
            tokensPrompt = 10432,
            tokensCompletion = 259,
            elapsedMs = 2989,
            rating = ChatRating.BAD,
            ratingNote = "召回错了",
            createdAt = "2026-09-28T09:12:33.123456"
        )

        val restored = message.toEntity().toDomain()

        assertEquals(message.turnId, restored.turnId)
        assertEquals(message.question, restored.question)
        assertEquals(message.answer, restored.answer)
        assertEquals(message.rewritten, restored.rewritten)
        assertEquals(message.keywords, restored.keywords)
        assertEquals(message.entities, restored.entities)
        assertEquals(message.sources, restored.sources)
        assertEquals(message.steps, restored.steps)
        assertEquals(message.cited, restored.cited)
        assertEquals(message.cost!!, restored.cost!!, 1e-9)
        assertEquals(message.tokensPrompt, restored.tokensPrompt)
        assertEquals(message.tokensCompletion, restored.tokensCompletion)
        assertEquals(message.elapsedMs, restored.elapsedMs)
        assertEquals(ChatRating.BAD, restored.rating)
        assertEquals("召回错了", restored.ratingNote)
        assertEquals(message.createdAt, restored.createdAt)
        // 存的是"过程"，所以刷新历史时不该被当成需要补详情的那类
        assertFalse(restored.needsDetail)
    }

    @Test
    fun corruptJsonDegradesToEmptyInsteadOfThrowing() {
        assertEquals(emptyList<QaSource>(), ChatPayloadCodec.decodeSources("这不是 JSON"))
        assertEquals(emptyList<QaSource>(), ChatPayloadCodec.decodeSources("{{{"))
        assertEquals(emptyList<QaStep>(), ChatPayloadCodec.decodeSteps("[]]"))
        assertEquals(emptyList<QaEntity>(), ChatPayloadCodec.decodeEntities("[1,2,3]"))
        assertEquals(emptyList<String>(), ChatPayloadCodec.decodeKeywords("null"))
        assertEquals(emptyList<Int>(), ChatPayloadCodec.decodeCited("\"oops\""))
        // null / 空串（= 这一列从来没写过）
        assertTrue(ChatPayloadCodec.decodeSources(null).isEmpty())
        assertTrue(ChatPayloadCodec.decodeSteps("   ").isEmpty())
        assertTrue(ChatPayloadCodec.decodeKeywords(null).isEmpty())
    }

    @Test
    fun structureValidButIncompleteJsonKeepsWhatItCan() {
        // 结构对、字段缺：能认的留下（why 缺失就是没有理由，不是崩）
        val sources = ChatPayloadCodec.decodeSources("""[{"text":"没有 id"},{"id":"m-1","why":["实体「A」"]}]""")
        assertEquals(listOf("m-1"), sources.map { it.id })
        assertEquals(listOf("实体「A」"), sources.first().why)
        assertTrue(sources.first().routes.isEmpty())

        val steps = ChatPayloadCodec.decodeSteps("""[{"elapsed_ms":5},{"step":"answer","cost":0.02}]""")
        assertEquals(listOf("answer"), steps.map { it.name })
        assertEquals(0.02, steps.first().cost!!, 1e-9)
        assertNull(steps.first().counts)
    }

    @Test
    fun emptyCollectionsAreStoredAsNull() {
        // 空 = 这一列没有内容（NULL），而不是 `[]`：与 memories 的附件列同一套读法
        assertNull(ChatPayloadCodec.encodeSources(emptyList()))
        assertNull(ChatPayloadCodec.encodeSteps(emptyList()))
        assertNull(ChatPayloadCodec.encodeEntities(emptyList()))
        assertNull(ChatPayloadCodec.encodeKeywords(emptyList()))
        assertNull(ChatPayloadCodec.encodeCited(emptyList()))
    }

    @Test
    fun historyStyleRowWithoutDetailReadsAsNeedingDetail() {
        // 从 GET /qa/turns 写进来的行：没有 steps/sources 两列
        val row = ChatMessageEntity(
            turnId = "t-2",
            question = "q",
            answer = "a",
            rewritten = null,
            keywordsJson = null,
            entitiesJson = null,
            sourcesJson = null,
            stepsJson = null,
            citedJson = null,
            cost = null,
            tokensPrompt = null,
            tokensCompletion = null,
            elapsedMs = null,
            rating = null,
            ratingNote = null,
            createdAt = "2026-09-27T08:00:00"
        )
        val domain = row.toDomain()
        assertTrue(domain.sources.isEmpty())
        assertTrue(domain.steps.isEmpty())
        assertTrue(domain.needsDetail)
        assertNull(domain.rating)
    }

    @Test
    fun unknownRatingStringReadsAsUnrated() {
        val base = ChatMessage(turnId = "t-3", question = "q", answer = "a")
        val restored = base.toEntity().copy(rating = "过时取值").toDomain()
        assertNull(restored.rating)
    }
}
