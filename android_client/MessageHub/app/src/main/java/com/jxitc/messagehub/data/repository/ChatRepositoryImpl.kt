package com.jxitc.messagehub.data.repository

import com.jxitc.messagehub.data.database.ChatMessageDao
import com.jxitc.messagehub.data.database.ChatMessageEntity
import com.jxitc.messagehub.data.database.toDomain
import com.jxitc.messagehub.data.database.toEntity
import com.jxitc.messagehub.data.remote.QaRemoteSource
import com.jxitc.messagehub.domain.model.ChatMessage
import com.jxitc.messagehub.domain.model.ChatRating
import com.jxitc.messagehub.domain.model.ProcessingResult
import com.jxitc.messagehub.domain.repository.ChatRepository
import com.jxitc.messagehub.utils.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 「问知识库」的仓储：本地 Room 缓存 + 服务器为事实来源。
 *
 * 三条规则，都是"服务器优先"在具体场景下的样子：
 *  1. **提问成功后**才写本地（失败的那次不写，见 [ask]）；
 *  2. **刷新时**服务器的记录覆盖本地，本地有而服务器（在拉取窗口内）没有的删掉；
 *  3. **评价**等服务器确认了再改本地 —— 高亮不该在网络失败后自己跳回去。
 */
class ChatRepositoryImpl(
    private val dao: ChatMessageDao,
    private val remote: QaRemoteSource
) : ChatRepository {

    override fun observeMessages(): Flow<List<ChatMessage>> =
        dao.observeMessages().map { rows -> rows.map { it.toDomain() } }

    /**
     * 提问。
     *
     * 问题在这里先 trim 再发（服务器也会 trim，但本地缓存里那份要与发出去的一致）；
     * 空问题直接挡掉，不发这一趟 —— 服务器会回 400，但那是白等一次往返。
     *
     * 只有服务器给出了回答才落本地缓存：
     *  - HTTP 失败（502/503/400）：服务器那边留了一条 error 的 turn，本地不存；
     *  - HTTP 200 但 `answer` 为空（服务器也记了 error）：同样不存 —— 界面上一条
     *    没有回答的气泡不是"历史"，只是噪音，用户重问一次就好。
     */
    override suspend fun ask(question: String): ProcessingResult<ChatMessage> {
        val asked = question.trim()
        if (asked.isEmpty()) return ProcessingResult.Error("问题不能为空")
        return when (val result = remote.askQuestion(asked)) {
            is ProcessingResult.Success -> {
                val message = result.data
                if (message.answer.isBlank()) {
                    val reason = message.error?.takeIf { it.isNotBlank() } ?: "服务器没有给出回答"
                    Logger.w("QA turn ${message.turnId} has no answer, not cached: $reason")
                    ProcessingResult.Error(reason)
                } else {
                    dao.upsert(message.toEntity())
                    Logger.i("QA turn ${message.turnId} cached (${message.sources.size} source(s))")
                    ProcessingResult.Success(message)
                }
            }
            is ProcessingResult.Error -> ProcessingResult.Error(result.message, result.throwable)
            ProcessingResult.Loading -> ProcessingResult.Loading
        }
    }

    /**
     * 拉服务器历史覆盖本地。
     *
     * 历史接口**故意不带 steps/sources**（那是详情接口的事），所以写入时把本地已有的
     * 过程字段带过去 —— 否则每刷新一次，点开过的「过程」就会被抹成空的。
     */
    override suspend fun refreshFromServer(limit: Int): ProcessingResult<Int> {
        return when (val result = remote.fetchTurns(limit)) {
            is ProcessingResult.Success -> {
                val serverTurns = result.data
                val answerable = serverTurns.filter { it.answer.isNotBlank() }
                answerable.forEach { incoming ->
                    val existing = dao.getById(incoming.turnId)
                    dao.upsert(incoming.toEntity().keepDetailFrom(existing))
                }
                val pruned = pruneGhosts(serverTurns)
                Logger.i(
                    "QA history refreshed: ${answerable.size} cached, $pruned removed, " +
                        "(server returned ${serverTurns.size})"
                )
                ProcessingResult.Success(answerable.size)
            }
            is ProcessingResult.Error -> ProcessingResult.Error(result.message, result.throwable)
            ProcessingResult.Loading -> ProcessingResult.Loading
        }
    }

    /**
     * 补齐一条历史记录的过程。
     *
     * 详情接口给的是这一条的完整 turn，直接覆盖：它比本地那份新，也没必要逐字段挑。
     */
    override suspend fun loadDetail(turnId: String): ProcessingResult<ChatMessage> {
        return when (val result = remote.fetchTurn(turnId)) {
            is ProcessingResult.Success -> {
                dao.upsert(result.data.toEntity())
                ProcessingResult.Success(result.data)
            }
            is ProcessingResult.Error -> ProcessingResult.Error(result.message, result.throwable)
            ProcessingResult.Loading -> ProcessingResult.Loading
        }
    }

    override suspend fun rate(
        turnId: String,
        rating: ChatRating?,
        note: String?
    ): ProcessingResult<Unit> {
        return when (val result = remote.rateTurn(turnId, rating, note)) {
            is ProcessingResult.Success -> {
                // 用服务器回的最终值，不自己推断（"再点一次＝取消"这类规则在服务端）
                val update = result.data
                dao.updateRating(update.turnId, update.rating?.wire, update.note)
                ProcessingResult.Success(Unit)
            }
            is ProcessingResult.Error -> ProcessingResult.Error(result.message, result.throwable)
            ProcessingResult.Loading -> ProcessingResult.Loading
        }
    }

    /**
     * 服务器这次没给的字段，用本地已有的补上（目前只有 steps/sources）。
     */
    private fun ChatMessageEntity.keepDetailFrom(existing: ChatMessageEntity?): ChatMessageEntity {
        if (existing == null) return this
        return copy(
            stepsJson = stepsJson ?: existing.stepsJson,
            sourcesJson = sourcesJson ?: existing.sourcesJson
        )
    }

    /**
     * 删掉"服务器已经没有"的本地记录 —— 服务器为准要连**消失**一起为准，
     * 否则服务器上删掉的问答会永远留在手机里。
     *
     * 两个边界，都是防误删：
     *  - 只在**这次拉到的窗口内**删（`[最老, 最新)`）。窗口外的本地记录可能只是因为
     *    这次 `limit` 没覆盖到，不能当幽灵；
     *  - 上界取**严格小于**最新那条：提问的写入与历史拉取是并发的，刚落库的那条比
     *    这次拉取的结果新，不能被判成幽灵。
     */
    private suspend fun pruneGhosts(serverTurns: List<ChatMessage>): Int {
        val times = serverTurns.map { it.createdAt }.filter { it.isNotBlank() }.sorted()
        if (times.isEmpty()) return 0
        val serverIds = serverTurns.map { it.turnId }.toSet()
        val ghosts = dao.getWindow(since = times.first(), before = times.last())
            .map { it.turnId }
            .filterNot { it in serverIds }
        if (ghosts.isNotEmpty()) {
            dao.deleteByIds(ghosts)
            Logger.i("QA cache: dropped ${ghosts.size} turn(s) the server no longer has")
        }
        return ghosts.size
    }
}
