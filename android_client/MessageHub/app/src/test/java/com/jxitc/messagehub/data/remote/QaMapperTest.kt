package com.jxitc.messagehub.data.remote

import com.google.gson.Gson
import com.jxitc.messagehub.domain.model.ChatRating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA 接口 JSON → 领域模型。
 *
 * 用**契约里的真实响应**（docs + api/v1/qa.py 的 to_dict）当输入，而不是手搓的
 * 理想对象：Gson 不调构造函数，缺字段/显式 null 都能落进 DTO，映射层必须自己收敛掉，
 * 这里就是钉住这件事的地方。
 */
class QaMapperTest {

    private val gson = Gson()

    private fun turn(json: String) = gson.fromJson(json, QaTurnDto::class.java)

    @Test
    fun fullAskResponseMapsEverythingTheUiShows() {
        val json = """
        {
          "id": "9a7b1c2d-0000-4e11-8f22-abcdefabcdef",
          "question": "我那辆车的 MOT 什么时候到期？",
          "rewritten": "用户的车的 MOT 到期时间是什么时候？",
          "answer": "MOT 到期日是 2026-10-16 [1]，另外还有一笔租金 [2]。",
          "keywords": ["MOT", "车辆年检", "到期"],
          "entities": [{"name": "简单心理", "kind": "org"}],
          "cited": [1, 2],
          "source_count": 40,
          "elapsed_ms": 2989,
          "cost": 0.0229,
          "tokens": {"prompt": 10432, "completion": 259},
          "source": "android",
          "conversation_id": "android",
          "rating": "good",
          "rating_note": "召回对了",
          "error": null,
          "created_at": "2026-09-28T09:12:33.123456",
          "steps": [
            {"step": "rewrite", "elapsed_ms": 1294, "input": "我那辆车的 MOT 什么时候到期？",
             "output": "用户的车的 MOT 到期时间是什么时候？",
             "tokens": {"prompt_tokens": 11197, "completion_tokens": 178},
             "cost": 0.0021, "model": "deepseek-chat"},
            {"step": "extract", "elapsed_ms": 0, "entities": [{"name": "简单心理", "kind": "org"}],
             "note": "与 rewrite 同一次调用返回"},
            {"step": "recall", "elapsed_ms": 164, "keywords": ["MOT", "车辆年检"],
             "entities_hit": [{"id": 703, "name": "简单心理", "kind": "org", "mentions": 21, "asked_as": "简单心理"}],
             "by_text": [{"term": "退款", "messages": 8}],
             "counts": {"entities_hit": 5, "text_terms": 13, "candidates": 96, "used": 40},
             "sources": [{"id": "m-1", "why": ["实体「简单心理」"], "routes": ["entity"], "excerpt": "…"}]},
            {"step": "answer", "elapsed_ms": 2196,
             "tokens": {"prompt_tokens": 9000, "completion_tokens": 259},
             "cost": 0.0209, "cited": [1, 2], "context_chars": 5419}
          ],
          "sources": [
            {"id": "11111111-2222-3333-4444-555555555555", "timestamp": "2026-09-15T12:24:02",
             "type": "SMS", "sender": "95588", "text": "尾号9303卡9月15日20:23支出…",
             "routes": ["entity", "text"], "why": ["实体「简单心理」", "正文含「支出」"]}
          ]
        }
        """.trimIndent()

        val message = turn(json).toDomain()
        assertNotNull(message)
        message!!

        assertEquals("9a7b1c2d-0000-4e11-8f22-abcdefabcdef", message.turnId)
        assertEquals("我那辆车的 MOT 什么时候到期？", message.question)
        assertTrue(message.answer.startsWith("MOT 到期日"))
        assertEquals("用户的车的 MOT 到期时间是什么时候？", message.rewritten)
        assertEquals(listOf("MOT", "车辆年检", "到期"), message.keywords)
        assertEquals(1, message.entities.size)
        assertEquals("简单心理", message.entities.first().name)
        assertEquals("org", message.entities.first().kind)
        assertEquals(listOf(1, 2), message.cited)
        assertEquals(2989, message.elapsedMs)
        assertEquals(0.0229, message.cost!!, 1e-9)
        assertEquals(10432, message.tokensPrompt)
        assertEquals(259, message.tokensCompletion)
        assertEquals(ChatRating.GOOD, message.rating)
        assertEquals("召回对了", message.ratingNote)
        assertNull(message.error)
        assertEquals("2026-09-28T09:12:33.123456", message.createdAt)

        // 四步都留下来了，顺序即 pipeline 顺序
        assertEquals(listOf("rewrite", "extract", "recall", "answer"), message.steps.map { it.name })
        // 顶层 tokens 是 prompt/completion，步骤里的是 prompt_tokens/completion_tokens，两套都要认
        val rewrite = message.steps.first()
        assertEquals(11197, rewrite.tokensPrompt)
        assertEquals(178, rewrite.tokensCompletion)
        assertEquals("deepseek-chat", rewrite.model)
        assertEquals("与 rewrite 同一次调用返回", message.steps[1].note)

        val recall = message.steps[2]
        assertEquals(listOf("MOT", "车辆年检"), recall.keywords)
        assertEquals(21, recall.entitiesHit.first().mentions)   // 「被提到 21 次」
        assertEquals("简单心理", recall.entitiesHit.first().askedAs)
        assertEquals(96, recall.counts?.candidates)
        assertEquals(40, recall.counts?.used)

        val source = message.sources.single()
        assertEquals("11111111-2222-3333-4444-555555555555", source.id)
        assertEquals("95588", source.sender)
        assertEquals("SMS", source.type)
        assertEquals(listOf("entity", "text"), source.routes)
        // 「为什么被召回」是过程面板里唯一不可省的东西
        assertEquals(listOf("实体「简单心理」", "正文含「支出」"), source.why)
        assertTrue(source.text.startsWith("尾号9303"))

        // 有过程 → 不需要再去服务器补详情
        assertTrue(!message.needsDetail)
    }

    @Test
    fun historyTurnWithoutStepsOrSourcesStillMapsAndAsksForDetail() {
        // GET /qa/turns 用 to_dict(with_steps=False)：没有 steps / sources 两个键
        val json = """
        {"id": "t-1", "question": "我接下来有什么安排？", "answer": "10 月 5 日取车 [1]。",
         "rewritten": "接下来有什么安排", "keywords": ["安排"], "entities": [], "cited": [1],
         "source_count": 12, "elapsed_ms": 1800, "cost": 0.01,
         "tokens": {"prompt": 100, "completion": 20}, "source": "android",
         "conversation_id": "android", "rating": null, "rating_note": null,
         "error": null, "created_at": "2026-09-27T08:00:00"}
        """.trimIndent()

        val message = turn(json).toDomain()!!
        assertEquals("t-1", message.turnId)
        assertEquals(listOf("安排"), message.keywords)
        assertTrue(message.sources.isEmpty())
        assertTrue(message.steps.isEmpty())
        // 概要版 = 详情还没拉，展开时要按 id 去补
        assertTrue(message.needsDetail)
    }

    @Test
    fun missingAndNullFieldsDegradeToEmptyInsteadOfThrowing() {
        val minimal = turn("""{"id":"t-min"}""").toDomain()!!
        assertEquals("", minimal.question)
        assertEquals("", minimal.answer)
        assertEquals("", minimal.createdAt)
        assertNull(minimal.rewritten)
        assertNull(minimal.cost)
        assertNull(minimal.tokensPrompt)
        assertNull(minimal.rating)
        assertNull(minimal.error)
        assertTrue(minimal.keywords.isEmpty())
        assertTrue(minimal.entities.isEmpty())
        assertTrue(minimal.sources.isEmpty())
        assertTrue(minimal.steps.isEmpty())
        assertTrue(minimal.cited.isEmpty())

        // 显式 null（比缺字段更常见：服务器的 JSON 列还没写过时就是 null）
        val nulls = turn(
            """{"id":"t-null","question":null,"answer":null,"keywords":null,"entities":null,
                "cited":null,"tokens":null,"steps":null,"sources":null,"rating":null,
                "rating_note":null,"error":null,"rewritten":null,"created_at":null}"""
        ).toDomain()!!
        assertEquals("", nulls.answer)
        assertTrue(nulls.sources.isEmpty())
        assertNull(nulls.rating)
    }

    @Test
    fun turnWithoutIdIsDropped() {
        // 没有 id 就没有主键，缓存无从去重 → 整条丢掉，而不是存一条 id 为空的行
        assertNull(turn("""{"question":"q","answer":"a"}""").toDomain())
        assertNull(turn("""{"id":"   ","question":"q"}""").toDomain())
    }

    @Test
    fun unusableNestedItemsAreDroppedNotPropagated() {
        val message = turn(
            """{"id":"t-2","answer":"a",
                "entities":[{"name":null,"kind":"org"},{"name":"  ","kind":"x"},{"name":"简单心理","kind":null}],
                "sources":[{"text":"没有 id 的原文"},{"id":"m-9","text":"有 id"}],
                "steps":[{"elapsed_ms":5},{"step":"recall","elapsed_ms":9}]}"""
        ).toDomain()!!

        assertEquals(listOf("简单心理"), message.entities.map { it.name })
        assertNull(message.entities.single().kind)
        assertEquals(listOf("m-9"), message.sources.map { it.id })
        assertEquals(listOf("recall"), message.steps.map { it.name })
    }

    @Test
    fun ratingWireValuesMapToEnumOrNothing() {
        assertEquals(ChatRating.GOOD, ChatRating.fromWire("good"))
        assertEquals(ChatRating.BAD, ChatRating.fromWire("BAD"))
        assertEquals(ChatRating.GOOD, ChatRating.fromWire(" Good "))
        // 没评（服务器三种写法都见过：null / 空串 / 不认识的旧值）
        assertNull(ChatRating.fromWire(null))
        assertNull(ChatRating.fromWire(""))
        assertNull(ChatRating.fromWire("   "))
        assertNull(ChatRating.fromWire("weird"))
    }

    @Test
    fun rateResponseMapsIncludingCancel() {
        val good = gson.fromJson("""{"id":"t-1","rating":"good","rating_note":"召回对了"}""", QaRateResponse::class.java)
            .toDomain()!!
        assertEquals("t-1", good.turnId)
        assertEquals(ChatRating.GOOD, good.rating)
        assertEquals("召回对了", good.note)

        // 取消评价：服务器把 rating 归一成 null 回给我们
        val cancelled = gson.fromJson("""{"id":"t-1","rating":null,"rating_note":null}""", QaRateResponse::class.java)
            .toDomain()!!
        assertNull(cancelled.rating)
        assertNull(cancelled.note)

        // 没有 id 的响应无法落到本地哪一行上
        assertNull(gson.fromJson("""{"rating":"good"}""", QaRateResponse::class.java).toDomain())
    }

    @Test
    fun askResponseKeepsTurnAndIgnoresCitationsPayload() {
        // citations 是 turn.sources 的子集，界面按 cited 编号给 sources 打标即可，
        // 不需要第二份列表（两份数据会在渲染时打架）
        val response = gson.fromJson(
            """{"turn":{"id":"t-3","question":"q","answer":"a","sources":[]},
                "citations":[{"id":"m-1","text":"x","why":["实体「A」"]}],
                "cost":0.02,"currency":"¥"}""",
            QaAskResponse::class.java
        )
        val message = response.turn?.toDomain()
        assertNotNull(message)
        assertEquals("t-3", message!!.turnId)
        assertEquals(0.02, response.cost!!, 1e-9)
    }
}
