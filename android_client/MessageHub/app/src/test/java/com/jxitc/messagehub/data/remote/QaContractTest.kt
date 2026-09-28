package com.jxitc.messagehub.data.remote

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 契约测试：用**线上真实响应**的字段名与类型解析一遍。
 *
 * 为什么需要它：这份 DTO 是照着接口文档写的，写的时候没有真实响应可对。而风险是
 * 具体的——实体 id 在服务端从自增整数改成了 16 位哈希字符串，DTO 还是 `Long`，
 * Gson 会在解析时抛异常，而它是**整份响应一起解析**的，于是每一句提问都失败。
 * 编译通过、单测全过，都拦不住这种错，只有拿真响应对一遍才行。
 *
 * 下面的 JSON 是从生产环境 `/api/v1/qa/ask` 抓下来的形状（值做了裁剪）。
 */
class QaContractTest {

    private val gson = Gson()

    private val realResponse = """
    {
      "turn": {
        "id": "80b65108-9e0f-4ddc-b6f1-54accec1143e",
        "question": "我那辆车的 MOT 什么时候到期？",
        "rewritten": "我那辆车的 MOT 什么时候到期？",
        "answer": "到期日是 2026年10月16日 [1]",
        "keywords": ["MOT", "到期", "车辆"],
        "entities": [],
        "cited": [1],
        "source_count": 40,
        "elapsed_ms": 3350,
        "cost": 0.025206,
        "tokens": {"prompt": 11395, "completion": 302},
        "source": "android",
        "conversation_id": "android",
        "rating": null,
        "rating_note": null,
        "error": null,
        "created_at": "2026-09-28T10:35:00.123456",
        "steps": [
          {"step": "rewrite", "elapsed_ms": 1294, "input": "q", "output": "r",
           "tokens": {"prompt_tokens": 683, "completion_tokens": 150},
           "cost": 0.0026, "model": "deepseek-chat"},
          {"step": "recall", "elapsed_ms": 164, "keywords": ["MOT"],
           "entities_hit": [
             {"id": "d47e5ea739129afe", "name": "简单心理", "kind": "org",
              "mentions": 22, "asked_as": "简单心理"}
           ],
           "by_text": [{"term": "MOT", "messages": 3}],
           "schedule_route": true,
           "counts": {"entities_hit": 5, "text_terms": 13,
                      "candidates": 65, "used": 40}},
          {"step": "answer", "elapsed_ms": 1892,
           "tokens": {"prompt_tokens": 10261, "completion_tokens": 126},
           "cost": 0.0215, "cited": [1], "context_chars": 5419}
        ],
        "sources": [
          {"id": "70bbd78d-921e-4bed-a014-73aac4eb5cad",
           "timestamp": "2026-09-15T09:00:00", "type": "SMS", "sender": "+447520667671",
           "why": ["正文含「MOT」"], "routes": ["text"],
           "text": "MOT reminders: YF17KHE needs an MOT by 16 Oct 2026."}
        ]
      },
      "citations": [
        {"id": "70bbd78d-921e-4bed-a014-73aac4eb5cad", "timestamp": "2026-09-15T09:00:00",
         "type": "SMS", "sender": "+447520667671", "why": ["正文含「MOT」"],
         "routes": ["text"], "text": "MOT reminders: ..."}
      ],
      "cost": 0.025206,
      "currency": "¥"
    }
    """.trimIndent()

    @Test
    fun `the real response parses without throwing`() {
        val response = gson.fromJson(realResponse, QaAskResponse::class.java)
        assertNotNull("整份响应必须能解析——一个字段类型错就会全盘失败", response.turn)
    }

    @Test
    fun `entity ids are strings, not numbers`() {
        // 回归：曾经是 Long，而服务端返回的是 "d47e5ea739129afe"。
        val response = gson.fromJson(realResponse, QaAskResponse::class.java)
        val hit = response.turn!!.steps!!.first { it.step == "recall" }.entitiesHit!!.first()
        assertEquals("d47e5ea739129afe", hit.id)
        assertEquals("简单心理", hit.name)
        assertEquals(22, hit.mentions)
    }

    @Test
    fun `cost and tokens survive the round trip`() {
        val turn = gson.fromJson(realResponse, QaAskResponse::class.java).turn!!
        assertEquals(0.025206, turn.cost!!, 1e-9)
        assertEquals(11395, turn.tokens!!.prompt)
        assertEquals(302, turn.tokens!!.completion)
    }

    @Test
    fun `the recall step keeps per-term hits and the calendar route flag`() {
        val recall = gson.fromJson(realResponse, QaAskResponse::class.java)
            .turn!!.steps!!.first { it.step == "recall" }
        assertEquals("MOT", recall.byText!!.first().term)
        assertEquals(3, recall.byText!!.first().messages)
        assertEquals(true, recall.scheduleRoute)
        assertEquals(65, recall.counts!!.candidates)
        assertEquals(40, recall.counts!!.used)
    }

    @Test
    fun `citations carry a message id that the history list can open`() {
        val response = gson.fromJson(realResponse, QaAskResponse::class.java)
        val first = response.citations!!.first()
        assertEquals("70bbd78d-921e-4bed-a014-73aac4eb5cad", first.id)
        assertEquals(listOf("正文含「MOT」"), first.why)
    }

    @Test
    fun `a failed turn parses too`() {
        // 502 的响应形状：有 error，没有 answer/steps。
        val body = """{"error":"LLM 调用失败：超时","turn_id":"abc"}"""
        val parsed = gson.fromJson(body, QaAskResponse::class.java)
        assertNull(parsed.turn)
    }

    @Test
    fun `a history entry without steps parses`() {
        val body = """
        {"count":1,"turns":[{"id":"x","question":"q","answer":"a","rating":"good",
         "cost":0.02,"tokens":{"prompt":10,"completion":5}}]}
        """.trimIndent()
        val parsed = gson.fromJson(body, QaTurnsResponse::class.java)
        val turn = parsed.turns!!.first()
        assertEquals("x", turn.id)
        assertNull("历史接口故意不带过程", turn.steps)
        assertEquals("good", turn.rating)
    }
}
