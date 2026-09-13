package com.jxitc.messagehub.presentation.viewmodel

import androidx.lifecycle.viewModelScope
import com.jxitc.messagehub.data.attachment.AttachmentPrepResult
import com.jxitc.messagehub.data.attachment.AttachmentPreparer
import com.jxitc.messagehub.data.local.AppPreferences
import com.jxitc.messagehub.data.remote.MessageHubApiClient
import com.jxitc.messagehub.domain.model.Attachment
import com.jxitc.messagehub.domain.model.AttachmentLimits
import com.jxitc.messagehub.domain.model.Memory
import com.jxitc.messagehub.domain.model.MemoryCreationRequest
import com.jxitc.messagehub.domain.model.ProcessingResult
import com.jxitc.messagehub.domain.model.SourceType
import com.jxitc.messagehub.domain.service.AttachmentPolicy
import com.jxitc.messagehub.domain.usecase.CreateMemoryUseCase
import com.jxitc.messagehub.domain.repository.MemoryRepository
import com.jxitc.messagehub.utils.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AddMemoryViewModel(
    private val createMemoryUseCase: CreateMemoryUseCase,
    private val apiClient: MessageHubApiClient,
    private val appPreferences: AppPreferences,
    private val memoryRepository: MemoryRepository,
    private val attachmentPreparer: AttachmentPreparer
) : BaseViewModel() {

    private val _content = MutableStateFlow("")
    val content: StateFlow<String> = _content.asStateFlow()

    private val _isSubmitted = MutableStateFlow(false)
    val isSubmitted: StateFlow<Boolean> = _isSubmitted.asStateFlow()

    /** 已选中并可上传的附件（该压的已经压好了）。 */
    private val _attachments = MutableStateFlow<List<Attachment>>(emptyList())
    val attachments: StateFlow<List<Attachment>> = _attachments.asStateFlow()

    /** 服务器给的上限与允许类型；拿不到时用契约里的兜底值。 */
    private val _limits = MutableStateFlow(AttachmentLimits.fallback())
    val limits: StateFlow<AttachmentLimits> = _limits.asStateFlow()

    /** 正在读取/压缩附件（可能要一两秒）。 */
    private val _isPreparingAttachments = MutableStateFlow(false)
    val isPreparingAttachments: StateFlow<Boolean> = _isPreparingAttachments.asStateFlow()

    /**
     * 非致命提示（例如"某个文件被拒绝"）。与 [error] 分开：
     * 它不该拦住提交 —— 被拒的是那一个文件，其他内容照样能存。
     */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    init {
        loadAttachmentLimits()
    }

    fun updateContent(newContent: String) {
        _content.value = newContent
        clearError()
    }

    fun dismissNotice() {
        _notice.value = null
    }

    /** 拉取服务器的附件上限与允许类型（失败就静默用兜底值，不打扰用户）。 */
    fun loadAttachmentLimits() {
        viewModelScope.launch {
            when (val result = apiClient.fetchAttachmentLimits()) {
                is ProcessingResult.Success -> _limits.value = result.data
                is ProcessingResult.Error -> {
                    Logger.w("Using fallback attachment limits: ${result.message}")
                    _limits.value = AttachmentLimits.fallback()
                }
                ProcessingResult.Loading -> Unit
            }
        }
    }

    /**
     * 用户选完图片/文件。逐个读取 + 校验 + （必要时）压缩，成功的进入列表，
     * 被拒的拼成一条提示 —— 一次选多个时不能因为其中一个不合格就全丢。
     */
    fun addAttachmentUris(uriStrings: List<String>) {
        if (uriStrings.isEmpty()) return
        viewModelScope.launch {
            _isPreparingAttachments.value = true
            clearError()
            val accepted = mutableListOf<Attachment>()
            val rejected = mutableListOf<String>()
            val limits = _limits.value

            for (uri in uriStrings) {
                when (val result = attachmentPreparer.prepare(uri, limits)) {
                    is AttachmentPrepResult.Accepted -> accepted += result.attachment
                    is AttachmentPrepResult.Rejected -> {
                        rejected += "「${result.fileName}」：${result.reason}"
                    }
                }
            }

            if (accepted.isNotEmpty()) {
                _attachments.value = _attachments.value + accepted
            }
            if (rejected.isNotEmpty()) {
                _notice.value = rejected.joinToString("\n")
            }
            _isPreparingAttachments.value = false
        }
    }

    fun removeAttachment(attachment: Attachment) {
        _attachments.value = _attachments.value.filterNot { it.uri == attachment.uri }
    }

    fun submitMemory() {
        val contentText = _content.value.trim()
        val selected = _attachments.value

        Logger.i("submitMemory called: ${contentText.length} chars, ${selected.size} attachment(s)")

        if (contentText.isEmpty() && selected.isEmpty()) {
            handleError("请输入内容，或添加一个附件")
            return
        }
        if (_isPreparingAttachments.value) {
            handleError("附件还在处理中，请稍候再提交")
            return
        }

        val request = MemoryCreationRequest(
            content = contentText,
            sourceType = SourceType.MANUAL,
            metadata = buildMetadata(selected)
        )

        if (selected.isEmpty()) {
            // 纯文本：保持原来的两条老路（服务器 + 本地兜底 / 仅本地）
            if (appPreferences.autoSync) {
                Logger.i("Taking server upload path (text only)")
                submitToServerWithFallback(request)
            } else {
                Logger.i("Taking local-only path (text only)")
                submitToLocalOnly(request)
            }
        } else {
            submitWithAttachments(request, selected)
        }
    }

    /**
     * 带附件提交。
     *
     * 与纯文本不同，**服务器失败时不会退回"只存本地"**：本机数据库不存附件字节，
     * 存一条没有附件的记录、之后再同步，只会让附件被静默丢掉。宁可报错让用户重试。
     */
    private fun submitWithAttachments(request: MemoryCreationRequest, attachments: List<Attachment>) {
        if (!appPreferences.autoSync) {
            handleError("附件只能通过服务器保存（本机不存附件）。请先在「设置」里开启自动同步，或去掉附件。")
            return
        }

        // 服务器对一次请求的总体积也有上限（见 AttachmentPolicy.totalAttachmentBudget）：
        // 单个文件都合格、加起来却超了，同样会被 413 拒掉，提前说清楚。
        val maxBytes = _limits.value.maxBytes
        val totalBytes = attachments.sumOf { it.sizeBytes }
        if (AttachmentPolicy.totalSizeExceedsBudget(totalBytes, maxBytes)) {
            handleError(
                "附件总大小 ${AttachmentPolicy.formatSize(totalBytes)} 超过一次提交的上限 " +
                    "${AttachmentPolicy.formatSize(AttachmentPolicy.totalAttachmentBudget(maxBytes))}" +
                    "，请删掉一些附件再提交。"
            )
            return
        }

        val payloads = attachments.map { it.payload }
        launchWithLoading(
            block = {
                when (val serverResult = apiClient.createMemoryWithAttachments(
                    request = request,
                    attachments = payloads,
                    maxBytes = _limits.value.maxBytes
                )) {
                    is ProcessingResult.Success -> {
                        // 服务器已收下附件，本地留一条记录（不含字节），保持
                        // "服务器成功 ⇒ 本地列表里能看到"这个既有约定。
                        // 纯附件（没写正文）时本地用附件名当占位正文 ——
                        // CreateMemoryUseCase 不收空正文，而空的列表项对用户等于"没存上"。
                        val localRequest = if (request.content.isBlank()) {
                            request.copy(content = localPlaceholderFor(attachments))
                        } else {
                            request
                        }
                        when (val local = persistLocalCopy(localRequest)) {
                            is ProcessingResult.Success -> local
                            is ProcessingResult.Error -> {
                                Logger.w("Server saved the memory but local copy failed: ${local.message}")
                                serverResult
                            }
                            ProcessingResult.Loading -> serverResult
                        }
                    }
                    is ProcessingResult.Error -> ProcessingResult.Error(
                        serverResult.message +
                            "\n（附件不会离线保存：本机不存附件字节，请解决上面的问题后重新提交）"
                    )
                    ProcessingResult.Loading -> ProcessingResult.Loading
                }
            },
            onSuccess = { memory ->
                Logger.i("Memory with ${attachments.size} attachment(s) uploaded, id=${memory.id}")
                _isSubmitted.value = true
                resetInput()
            }
        )
    }

    private fun submitToServerWithFallback(request: MemoryCreationRequest) {
        Logger.i("submitToServerWithFallback: Starting server upload...")
        launchWithLoading(
            block = { 
                // Try server first
                Logger.i("Calling apiClient.createMemory...")
                when (val serverResult = apiClient.createMemory(request)) {
                    is ProcessingResult.Success -> {
                        Logger.i("Server upload successful, saving locally...")
                        when (val local = persistLocalCopy(request)) {
                            is ProcessingResult.Success -> local
                            is ProcessingResult.Error -> {
                                // 本地留档失败，但服务器已经存下了 —— 不因为本地失败就报错
                                Logger.e("Local save failed even though server succeeded: ${local.message}")
                                serverResult
                            }
                            ProcessingResult.Loading -> serverResult
                        }
                    }
                    is ProcessingResult.Error -> {
                        Logger.w("Server upload failed: ${serverResult.message}, saving locally...")
                        // Server failed - save locally for later sync (isUploaded = false by default)
                        val localResult = createMemoryUseCase.execute(request)
                        when (localResult) {
                            is ProcessingResult.Success -> {
                                // Add user notification about upload failure
                                Logger.i("Memory saved locally, will retry upload later")
                                ProcessingResult.Success(localResult.data)
                            }
                            is ProcessingResult.Error -> {
                                Logger.e("Both server and local save failed!")
                                ProcessingResult.Error("Failed to save memory: ${localResult.message}")
                            }
                            ProcessingResult.Loading -> ProcessingResult.Loading
                        }
                    }
                    ProcessingResult.Loading -> ProcessingResult.Loading
                }
            },
            onSuccess = { memory ->
                _isSubmitted.value = true
                _content.value = ""
            }
        )
    }

    /**
     * 服务器已经成功之后，在本地留一条已上传的记录。
     * 返回 Error 表示本地没留成 —— 调用方自己决定要不要因此改变整体结果。
     */
    private suspend fun persistLocalCopy(request: MemoryCreationRequest): ProcessingResult<Memory> {
        val localResult = createMemoryUseCase.execute(request)
        if (localResult !is ProcessingResult.Success) return localResult
        return when (val update = memoryRepository.updateMemoryUploadStatus(localResult.data.id, true)) {
            is ProcessingResult.Success -> {
                Logger.i("Local copy saved and marked uploaded: id=${localResult.data.id}")
                ProcessingResult.Success(localResult.data.copy(isUploaded = true))
            }
            is ProcessingResult.Error -> {
                Logger.e("Failed to update upload status: ${update.message}")
                ProcessingResult.Success(localResult.data.copy(isUploaded = true))
            }
            ProcessingResult.Loading -> ProcessingResult.Success(localResult.data.copy(isUploaded = true))
        }
    }

    private fun submitToLocalOnly(request: MemoryCreationRequest) {
        Logger.i("submitToLocalOnly: Saving memory locally only...")
        launchWithLoading(
            block = { 
                Logger.i("Calling createMemoryUseCase.execute...")
                createMemoryUseCase.execute(request) 
            },
            onSuccess = { memory ->
                Logger.i("Local save successful, memory ID: ${memory.id}")
                _isSubmitted.value = true
                _content.value = ""
            }
        )
    }

    /**
     * 纯附件提交时的本地占位正文。
     *
     * 服务器那边 `content` 是空字符串（契约允许"有附件、无正文"），本机数据库不接受空正文，
     * 而列表是本地库驱动的 —— 不写点什么，用户会觉得"存成功了但列表里没有"。
     * 所以本地记一行附件名当索引，服务器仍是权威数据源（OCR 提取后会填上正文）。
     */
    private fun localPlaceholderFor(attachments: List<Attachment>): String =
        "【附件】" + attachments.joinToString("、") { it.fileName }

    /**
     * 手动添加的 metadata。列里已有的事实不重复塞 JSON（见服务器 metadata_policy）：
     * 这里只记 input_method 与附件数量；附件本身的元数据由服务器在存 blob 时写入。
     */
    private fun buildMetadata(attachments: List<Attachment>): Map<String, String> =
        if (attachments.isEmpty()) {
            mapOf("input_method" to "manual_text_input")
        } else {
            mapOf(
                "input_method" to "manual_with_attachments",
                "attachment_count" to attachments.size.toString()
            )
        }

    private fun resetInput() {
        _content.value = ""
        _attachments.value = emptyList()
        _notice.value = null
    }

    fun resetSubmissionState() {
        _isSubmitted.value = false
    }
}
