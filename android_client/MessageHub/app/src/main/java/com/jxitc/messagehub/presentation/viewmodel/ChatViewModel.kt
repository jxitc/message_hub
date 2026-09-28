package com.jxitc.messagehub.presentation.viewmodel

import androidx.lifecycle.viewModelScope
import com.jxitc.messagehub.domain.model.ChatMessage
import com.jxitc.messagehub.domain.model.ChatRating
import com.jxitc.messagehub.domain.model.ProcessingResult
import com.jxitc.messagehub.domain.repository.ChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 「问知识库」页面。
 *
 * 数据流是**单向**的：ViewModel 只读仓储（Room 的 Flow + 服务器刷新），界面只读 StateFlow。
 * 提问/评价的结果都落进 Room，再由 Flow 回到界面，所以这里没有"手动往列表里塞一条"的代码 ——
 * 那样在刷新到达时会出现重复或顺序错乱。
 */
class ChatViewModel(
    private val repository: ChatRepository
) : BaseViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    /** 正在提问。一次问答要几秒，期间输入框与发送按钮都要禁用，避免重复提交。 */
    private val _asking = MutableStateFlow(false)
    val asking: StateFlow<Boolean> = _asking.asStateFlow()

    /** 正在补过程的 turn id。展开「过程」时按需去服务器取（历史接口不带）。 */
    private val _detailLoading = MutableStateFlow<Set<String>>(emptySet())
    val detailLoading: StateFlow<Set<String>> = _detailLoading.asStateFlow()

    init {
        // 先显示本地缓存（断网也能翻历史），同时拉服务器覆盖 —— 顺序不能反过来：
        // 先等网络的话，没网时页面就是一片空白，而本地明明有历史。
        viewModelScope.launch(exceptionHandler) {
            repository.observeMessages().collect { _messages.value = it }
        }
        refreshHistory()
    }

    /**
     * 提问。问题由界面清空（标准聊天行为）；失败原因以错误卡片显示。
     */
    fun ask(question: String) {
        val asked = question.trim()
        if (asked.isEmpty() || _asking.value) return
        viewModelScope.launch(exceptionHandler) {
            _asking.value = true
            clearError()
            when (val result = repository.ask(asked)) {
                is ProcessingResult.Success -> Unit
                is ProcessingResult.Error -> handleError(result.message)
                ProcessingResult.Loading -> Unit
            }
            _asking.value = false
        }
    }

    /**
     * 评价。**再点一次同一个按钮就是取消**（服务器认空 rating），
     * 所以界面上只需要两个按钮，不需要第三个"取消"入口。
     */
    fun rate(turnId: String, rating: ChatRating) {
        viewModelScope.launch(exceptionHandler) {
            val current = _messages.value.firstOrNull { it.turnId == turnId }?.rating
            val next = if (current == rating) null else rating
            clearError()
            when (val result = repository.rate(turnId, next)) {
                is ProcessingResult.Error -> handleError("评价没记上：${result.message}")
                else -> Unit
            }
        }
    }

    /** 展开「过程」时补详情；本地已经有过程、或正在拉，就不重复请求。 */
    fun loadDetail(turnId: String) {
        val message = _messages.value.firstOrNull { it.turnId == turnId } ?: return
        if (!message.needsDetail || _detailLoading.value.contains(turnId)) return
        viewModelScope.launch(exceptionHandler) {
            _detailLoading.value = _detailLoading.value + turnId
            when (val result = repository.loadDetail(turnId)) {
                is ProcessingResult.Error -> handleError("拉取过程失败：${result.message}")
                else -> Unit
            }
            _detailLoading.value = _detailLoading.value - turnId
        }
    }

    fun refreshHistory() {
        viewModelScope.launch(exceptionHandler) {
            clearError()
            when (val result = repository.refreshFromServer()) {
                is ProcessingResult.Error -> handleError("历史同步失败（下面是本地缓存）：${result.message}")
                else -> Unit
            }
        }
    }
}
