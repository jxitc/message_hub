package com.jxitc.messagehub.presentation.viewmodel

import androidx.lifecycle.viewModelScope
import com.jxitc.messagehub.data.remote.MessageHubApiClient
import com.jxitc.messagehub.domain.model.Memory
import com.jxitc.messagehub.domain.model.ProcessingResult
import com.jxitc.messagehub.domain.repository.MemoryRepository
import com.jxitc.messagehub.domain.usecase.GetMemoriesUseCase
import com.jxitc.messagehub.domain.service.AttachmentExtractionPoller
import com.jxitc.messagehub.domain.service.MemorySyncService
import com.jxitc.messagehub.domain.service.PollOutcome
import com.jxitc.messagehub.utils.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MemoryListViewModel(
    private val getMemoriesUseCase: GetMemoriesUseCase,
    private val syncService: MemorySyncService,
    private val apiClient: MessageHubApiClient,
    private val memoryRepository: MemoryRepository
) : BaseViewModel() {
    
    private val _memories = MutableStateFlow<List<Memory>>(emptyList())
    val memories: StateFlow<List<Memory>> = _memories.asStateFlow()
    
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /** 正在等附件提取结果（列表页可见时才有值），UI 可以据此显示一个小提示。 */
    private val _attachmentPolling = MutableStateFlow(false)
    val attachmentPolling: StateFlow<Boolean> = _attachmentPolling.asStateFlow()

    /**
     * 页面是否可见。**不可见就停止轮询**（需求）：
     * 列表页离开（例如进"添加记忆"或切到别的 app）后没有任何理由继续问服务器。
     */
    @Volatile
    private var screenVisible = false
    private var attachmentPollJob: Job? = null
    
    init {
        loadMemories()
        // Start auto-sync on app launch
        syncPendingMemories()
    }
    
    fun loadMemories() {
        viewModelScope.launch(exceptionHandler) {
            setLoading(true)
            try {
                // 全量取数(几千条内存聚合无压力), 避免先取 50 条导致单一 app 连刷顶掉其他 app
                getMemoriesUseCase.getAllMemories().collect { memoriesList ->
                    _memories.value = memoriesList
                    setLoading(false)
                }
            } catch (e: Exception) {
                handleError("Failed to load memories: ${e.message}")
            }
        }
    }
    
    fun searchMemories(query: String) {
        _searchQuery.value = query
        
        viewModelScope.launch(exceptionHandler) {
            setLoading(true)
            try {
                getMemoriesUseCase.searchMemories(query).collect { memoriesList ->
                    _memories.value = memoriesList
                    setLoading(false)
                }
            } catch (e: Exception) {
                handleError("Failed to search memories: ${e.message}")
            }
        }
    }
    
    fun clearSearch() {
        _searchQuery.value = ""
        loadMemories()
    }
    
    fun refreshMemories() {
        clearError()
        // Trigger sync before refreshing memories
        syncPendingMemories()
        // 手动刷新时也把还挂着的附件状态问一遍（列表页可见，符合轮询前提）
        startAttachmentStatusPolling()
        
        if (_searchQuery.value.isBlank()) {
            loadMemories()
        } else {
            searchMemories(_searchQuery.value)
        }
    }

    // ------------------------------------------------------------------
    // 附件提取状态：页面可见时轮询，不可见即停
    // ------------------------------------------------------------------

    /** Compose 侧：列表页进入可见状态 → 有 pending 附件就开始轮询。 */
    fun onScreenVisible() {
        screenVisible = true
        startAttachmentStatusPolling()
    }

    /** Compose 侧：列表页不可见/销毁 → 立刻停掉轮询。 */
    fun onScreenHidden() {
        screenVisible = false
        attachmentPollJob?.cancel()
        attachmentPollJob = null
        _attachmentPolling.value = false
    }

    /**
     * 打开某条消息详情时手动刷一次（用户主动要求看最新状态，不必等退避）。
     * 结果直接进 Room，列表由 Room 的 Flow 自动刷新。
     */
    fun refreshAttachmentStatus(serverMessageId: String?) {
        if (serverMessageId.isNullOrBlank()) return
        viewModelScope.launch(exceptionHandler) {
            when (val result = apiClient.fetchMessageDetail(serverMessageId)) {
                is ProcessingResult.Success -> memoryRepository.saveAttachmentDetail(result.data)
                is ProcessingResult.Error -> Logger.w("Manual attachment refresh failed: ${result.message}")
                ProcessingResult.Loading -> Unit
            }
        }
    }

    /**
     * 对本地所有"还有附件在 pending"的消息按退避表轮询到终态。
     *
     * 与"添加记忆"页的轮询是同一套 [AttachmentExtractionPoller] 规则：
     * 3s → 5s → 10s → 20s → 之后每 30s，总计上限 3 分钟；无人可见时不启动。
     */
    private fun startAttachmentStatusPolling() {
        if (attachmentPollJob?.isActive == true) return
        attachmentPollJob = viewModelScope.launch(exceptionHandler) {
            val targets = memoryRepository.getMemoriesAwaitingAttachments()
            val ids = targets.mapNotNull { it.serverMessageId }
            if (ids.isEmpty()) return@launch

            Logger.i("Polling attachment status for ${ids.size} message(s)")
            _attachmentPolling.value = true
            val poller = AttachmentExtractionPoller(
                fetch = { apiClient.fetchMessageDetail(it) },
                persist = { memoryRepository.saveAttachmentDetail(it) }
            )
            val outcome = poller.poll(ids, isVisible = { screenVisible })
            Logger.i("Attachment status polling finished: $outcome")
            _attachmentPolling.value = false
            // 超时/连续失败给用户一句人话，别让"还在提取中"的假象留着。
            when (outcome) {
                is PollOutcome.TimedOut -> handleError(
                    "部分附件的文本还没提取完（已等待 3 分钟）。稍后可在详情页手动刷新。"
                )
                is PollOutcome.Failed -> handleError("附件状态查询失败：${outcome.message}")
                else -> Unit
            }
        }
    }
    
    private fun syncPendingMemories() {
        viewModelScope.launch(exceptionHandler) {
            try {
                val (uploaded, failed) = syncService.syncPendingMemories()
                if (uploaded > 0 || failed > 0) {
                    // Refresh the memory list after sync to show updated upload status
                    loadMemories()
                }
            } catch (e: Exception) {
                // Don't show sync errors to user, just log them
                // The memories will remain in pending state and sync can retry later
            }
        }
    }
}
