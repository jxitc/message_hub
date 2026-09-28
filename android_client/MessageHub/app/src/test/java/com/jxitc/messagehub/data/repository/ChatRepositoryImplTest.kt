package com.jxitc.messagehub.data.repository

import com.jxitc.messagehub.data.database.ChatMessageDao
import com.jxitc.messagehub.data.database.ChatMessageEntity
import com.jxitc.messagehub.data.database.toEntity
import com.jxitc.messagehub.data.remote.QaRemoteSource
import com.jxitc.messagehub.domain.model.ChatMessage
import com.jxitc.messagehub.domain.model.ChatRating
import com.jxitc.messagehub.domain.model.ChatRatingUpdate
import com.jxitc.messagehub.domain.model.ProcessingResult
import com.jxitc.messagehub.domain.model.QaSource
import com.jxitc.messagehub.domain.model.QaStep
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仓储层的规则：**服务器是事实来源，本地是缓存**。
 *
 * 用假的 DAO（内存 map）+ 假的网络实现跑 —— 真实实现需要 Android Context/Retrofit，
 * 而这里要验的恰恰不是它们。
 */
class ChatRepositoryImplTest {

    /** Room 的替身：内存 map，写入后按 createdAt 正序对外发一次（与 DAO 的排序一致）。 */
    private class FakeChatDao : ChatMessageDao {
        val rows = linkedMapOf<String, ChatMessageEntity>()
        private val flow = MutableStateFlow<List<ChatMessageEntity>>(emptyList())

        private fun publish() {
            flow.value = rows.values.sortedWith(compareBy({ it.createdAt }, { it.turnId }))
        }

        override fun observeMessages(): Flow<List<ChatMessageEntity>> = flow

        override suspend fun getById(turnId: String): ChatMessageEntity? = rows[turnId]

        override suspend fun getWindow(since: String, before: String): List<ChatMessageEntity> =
            rows.values.filter { it.createdAt >= since && it.createdAt < before }

        override suspend fun upsert(message: ChatMessageEntity) {
            rows[message.turnId] = message
            publish()
        }

        override suspend fun updateRating(turnId: String, rating: String?, note: String?) {
            val existing = rows[turnId] ?: return
            rows[turnId] = existing.copy(rating = rating, ratingNote = note)
            publish()
        }

        override suspend fun deleteByIds(turnIds: List<String>) {
            turnIds.forEach { rows.remove(it) }
            publish()
        }

        override suspend fun count(): Int = rows.size
    }

    /** 网络替身：每个方法的结果事先摆好，调用记录下来。 */
    private class FakeQaRemote : QaRemoteSource {
        var askResult: ProcessingResult<ChatMessage> = ProcessingResult.Error("未设置")
        var turnsResult: ProcessingResult<List<ChatMessage>> = ProcessingResult.Success(emptyList())
        var turnResult: ProcessingResult<ChatMessage> = ProcessingResult.Error("未设置")
        var rateResult: ProcessingResult<ChatRatingUpdate> = ProcessingResult.Error("未设置")

        val asked = mutableListOf<String>()
        val rated = mutableListOf<Triple<String, ChatRating?, String?>>()

        override suspend fun askQuestion(question: String): ProcessingResult<ChatMessage> {
            asked += question
            return askResult
        }

        override suspend fun fetchTurns(limit: Int): ProcessingResult<List<ChatMessage>> = turnsResult

        override suspend fun fetchTurn(turnId: String): ProcessingResult<ChatMessage> = turnResult

        override suspend fun rateTurn(
            turnId: String,
            rating: ChatRating?,
            note: String?
        ): ProcessingResult<ChatRatingUpdate> {
            rated += Triple(turnId, rating, note)
            return rateResult
        }
    }

    private val dao = FakeChatDao()
    private val remote = FakeQaRemote()
    private val repository = ChatRepositoryImpl(dao, remote)

    private fun turn(
        id: String,
        answer: String = "回答 $id",
        createdAt: String = "2026-09-01T10:00:00",
        error: String? = null,
        steps: List<QaStep> = emptyList(),
        sources: List<QaSource> = emptyList()
    ) = ChatMessage(
        turnId = id,
        question = "问题 $id",
        answer = answer,
        createdAt = createdAt,
        error = error,
        steps = steps,
        sources = sources
    )

    // ------------------------------------------------------------------
    // 提问
    // ------------------------------------------------------------------

    @Test
    fun askCachesTheAnswerUnderTheServerTurnId() = runBlocking {
        remote.askResult = ProcessingResult.Success(
            turn("t-1", steps = listOf(QaStep(name = "answer", elapsedMs = 2196)))
        )

        val result = repository.ask("  问题 t-1  ")

        assertTrue(result is ProcessingResult.Success)
        // 问题先 trim 再发（服务器也会 trim，但本地这份要跟它一致）
        assertEquals(listOf("问题 t-1"), remote.asked)
        assertEquals(1, dao.count())
        val cached = dao.rows["t-1"]
        assertNotNull(cached)
        assertEquals("回答 t-1", cached!!.answer)
        // 过程一起落库，展开「过程」不必再等网络
        assertTrue(cached.stepsJson!!.contains("answer"))
    }

    @Test
    fun askFailureIsNotCached() = runBlocking {
        remote.askResult = ProcessingResult.Error("LLM 调用失败：upstream timeout")

        val result = repository.ask("问题")

        assertTrue(result is ProcessingResult.Error)
        assertEquals("LLM 调用失败：upstream timeout", (result as ProcessingResult.Error).message)
        // 服务器那边留了一条 error 的 turn，但这不该变成手机里一条空气泡
        assertEquals(0, dao.count())
    }

    @Test
    fun answerlessTwoHundredIsNotCachedEither() = runBlocking {
        // 契约里失败是 502；但服务器也可能 200 回一条带 error、没有 answer 的记录。
        // 判断放在仓储层：只要没有回答，就不进缓存。
        remote.askResult = ProcessingResult.Success(
            turn("t-bad", answer = "", error = "LLM 调用失败：connection reset")
        )

        val result = repository.ask("问题")

        assertEquals("LLM 调用失败：connection reset", (result as ProcessingResult.Error).message)
        assertEquals(0, dao.count())
    }

    @Test
    fun answerlessTurnWithoutErrorStillGetsAReadableMessage() = runBlocking {
        remote.askResult = ProcessingResult.Success(turn("t-bad", answer = ""))

        val result = repository.ask("问题")

        assertEquals("服务器没有给出回答", (result as ProcessingResult.Error).message)
        assertEquals(0, dao.count())
    }

    @Test
    fun emptyQuestionNeverReachesTheNetwork() = runBlocking {
        val result = repository.ask("   ")
        assertEquals("问题不能为空", (result as ProcessingResult.Error).message)
        assertTrue(remote.asked.isEmpty())
    }

    // ------------------------------------------------------------------
    // 服务器为准的刷新
    // ------------------------------------------------------------------

    @Test
    fun refreshOverwritesLocalAndKeepsDetailTheHistoryEndpointOmits() = runBlocking {
        // 本地已有这条（带过程），服务器历史版没有过程
        dao.upsert(
            turn(
                "t-1",
                answer = "旧回答",
                steps = listOf(QaStep(name = "recall", counts = null)),
                sources = listOf(QaSource(id = "m-1", text = "原文", why = listOf("实体「A」")))
            ).toEntityWithRating(null)
        )
        remote.turnsResult = ProcessingResult.Success(listOf(turn("t-1", answer = "新回答")))

        val result = repository.refreshFromServer()

        assertEquals(1, (result as ProcessingResult.Success).data)
        assertEquals("新回答", dao.rows["t-1"]!!.answer)
        // 历史接口故意不带 steps/sources：直接覆盖会把点开过的过程抹掉
        assertNotNull(dao.rows["t-1"]!!.stepsJson)
        assertTrue(dao.rows["t-1"]!!.sourcesJson!!.contains("实体「A」"))
    }

    @Test
    fun refreshDropsLocalRowsTheServerNoLongerHasInsideTheFetchedWindow() = runBlocking {
        // 窗口 = [这次最老(09-01), 这次最新(09-03))
        dao.upsert(turn("older-than-window", createdAt = "2026-08-31T10:00:00").toEntityWithRating(null))
        dao.upsert(turn("t-1", createdAt = "2026-09-01T10:00:00").toEntityWithRating(null))
        dao.upsert(turn("ghost", createdAt = "2026-09-02T10:00:00").toEntityWithRating(null))
        dao.upsert(turn("newer-than-window", createdAt = "2026-09-04T10:00:00").toEntityWithRating(null))

        remote.turnsResult = ProcessingResult.Success(
            listOf(
                turn("t-1", createdAt = "2026-09-01T10:00:00"),
                turn("t-3", createdAt = "2026-09-03T10:00:00")
            )
        )

        repository.refreshFromServer()

        assertNull(dao.rows["ghost"])                       // 窗口内服务器没有 → 删
        assertNotNull(dao.rows["t-1"])
        assertNotNull(dao.rows["t-3"])
        assertNotNull(dao.rows["older-than-window"])        // 窗口外：可能只是这次 limit 没覆盖到
        assertNotNull(dao.rows["newer-than-window"])        // 比拉取结果还新（并发写入）→ 不删
    }

    @Test
    fun refreshKeepsTheLocalCacheWhenTheServerIsUnreachable() = runBlocking {
        dao.upsert(turn("t-1").toEntityWithRating(null))
        remote.turnsResult = ProcessingResult.Error("连接失败：timeout")

        val result = repository.refreshFromServer()

        assertTrue(result is ProcessingResult.Error)
        assertEquals(1, dao.count())
    }

    @Test
    fun refreshDoesNotCacheServerErrorTurns() = runBlocking {
        remote.turnsResult = ProcessingResult.Success(
            listOf(turn("t-ok"), turn("t-failed", answer = "", error = "LLM 调用失败"))
        )

        val result = repository.refreshFromServer()

        assertEquals(1, (result as ProcessingResult.Success).data)
        assertNull(dao.rows["t-failed"])
    }

    @Test
    fun observedMessagesAreMappedAndOrderedOldestFirst() = runBlocking {
        dao.upsert(turn("t-2", answer = "第二", createdAt = "2026-09-02T10:00:00").toEntityWithRating(ChatRating.GOOD))
        dao.upsert(turn("t-1", answer = "第一", createdAt = "2026-09-01T10:00:00").toEntityWithRating(null))

        val messages = repository.observeMessages().first()

        assertEquals(listOf("t-1", "t-2"), messages.map { it.turnId })
        assertNull(messages[0].rating)
        assertEquals(ChatRating.GOOD, messages[1].rating)
    }

    // ------------------------------------------------------------------
    // 过程详情
    // ------------------------------------------------------------------

    @Test
    fun loadDetailFillsInStepsAndSources() = runBlocking {
        dao.upsert(turn("t-1").toEntityWithRating(null))
        remote.turnResult = ProcessingResult.Success(
            turn("t-1", steps = listOf(QaStep(name = "recall")), sources = listOf(QaSource(id = "m-1", text = "原文")))
        )

        val result = repository.loadDetail("t-1")

        assertTrue(result is ProcessingResult.Success)
        assertNotNull(dao.rows["t-1"]!!.stepsJson)
        assertTrue(dao.rows["t-1"]!!.sourcesJson!!.contains("m-1"))
    }

    // ------------------------------------------------------------------
    // 评价
    // ------------------------------------------------------------------

    @Test
    fun ratingGoodIsWrittenFromTheServerAnswer() = runBlocking {
        dao.upsert(turn("t-1").toEntityWithRating(null))
        remote.rateResult = ProcessingResult.Success(ChatRatingUpdate("t-1", ChatRating.GOOD))

        val result = repository.rate("t-1", ChatRating.GOOD)

        assertTrue(result is ProcessingResult.Success)
        assertEquals(listOf(Triple("t-1", ChatRating.GOOD, null)), remote.rated)
        assertEquals("good", dao.rows["t-1"]!!.rating)
    }

    @Test
    fun ratingBadCarriesTheNote() = runBlocking {
        dao.upsert(turn("t-1").toEntityWithRating(null))
        remote.rateResult = ProcessingResult.Success(ChatRatingUpdate("t-1", ChatRating.BAD, "召回错了"))

        repository.rate("t-1", ChatRating.BAD, "召回错了")

        assertEquals(ChatRating.BAD.wire, dao.rows["t-1"]!!.rating)
        assertEquals("召回错了", dao.rows["t-1"]!!.ratingNote)
    }

    @Test
    fun cancellingARatingClearsItLocally() = runBlocking {
        dao.upsert(turn("t-1").toEntityWithRating(ChatRating.GOOD))
        // 取消：客户端传 null，服务器归一成"没评"再回给我们
        remote.rateResult = ProcessingResult.Success(ChatRatingUpdate("t-1", null, null))

        repository.rate("t-1", null)

        assertEquals(listOf(Triple("t-1", null, null)), remote.rated)
        assertNull(dao.rows["t-1"]!!.rating)
        assertNull(dao.rows["t-1"]!!.ratingNote)
    }

    @Test
    fun failedRatingLeavesTheLocalStateAlone() = runBlocking {
        dao.upsert(turn("t-1").toEntityWithRating(ChatRating.GOOD))
        remote.rateResult = ProcessingResult.Error("连接失败：timeout")

        val result = repository.rate("t-1", ChatRating.BAD)

        assertTrue(result is ProcessingResult.Error)
        // 先改本地再报错回滚的话，高亮会在一次网络失败后自己跳回去 —— 宁可不改
        assertEquals("good", dao.rows["t-1"]!!.rating)
    }

    @Test
    fun serverSideRatingWinsOnRefresh() = runBlocking {
        // 在网页端评过 / 取消过：刷新时以服务器为准
        dao.upsert(turn("t-1").toEntityWithRating(ChatRating.GOOD))
        remote.turnsResult = ProcessingResult.Success(listOf(turn("t-1").copy(rating = ChatRating.BAD)))

        repository.refreshFromServer()

        assertEquals("bad", dao.rows["t-1"]!!.rating)
    }

    /** 领域模型 → 缓存行（评价单独指定，因为提问/刷新路径不经过评价）。 */
    private fun ChatMessage.toEntityWithRating(rating: ChatRating?) =
        this.copy(rating = rating).toEntity()
}
