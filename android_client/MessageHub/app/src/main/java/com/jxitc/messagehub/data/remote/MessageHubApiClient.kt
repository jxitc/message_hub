package com.jxitc.messagehub.data.remote

import com.jxitc.messagehub.data.local.AppPreferences
import com.jxitc.messagehub.domain.model.AttachmentLimits
import com.jxitc.messagehub.domain.model.AttachmentPayload
import com.jxitc.messagehub.domain.model.ChatMessage
import com.jxitc.messagehub.domain.model.ChatRating
import com.jxitc.messagehub.domain.model.ChatRatingUpdate
import com.jxitc.messagehub.domain.model.ExtractionStatus
import com.jxitc.messagehub.domain.model.Memory
import com.jxitc.messagehub.domain.model.MemoryCreationRequest
import com.jxitc.messagehub.domain.model.MessageAttachmentDetail
import com.jxitc.messagehub.domain.model.ProcessingResult
import com.jxitc.messagehub.domain.model.QaThread
import com.jxitc.messagehub.domain.service.ChatFormat
import com.jxitc.messagehub.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Network client for the Message Hub (MH) server.
 *
 * Converts local [MemoryCreationRequest]s into MH `POST /api/v1/messages` payloads
 * and reports them via Retrofit. Upload success = HTTP 2xx (MH returns 201 + id).
 */
class MessageHubApiClient(
    private val preferences: AppPreferences
) : QaRemoteSource {

    // HTTP logging: BASIC only (method/URL/status/timing).
    //
    // Level.BODY prints the whole request/response payload as ONE log line. A
    // body larger than logd's single-entry limit (~4068 bytes) makes Android's
    // logd chunking path run, and on Android 16 that path aborts the process
    // through an ubsan sub-overflow check (SIGABRT with no Java stack trace —
    // it looks like a silent crash on launch). A single long message (e.g. a
    // full article body) is enough to trigger it. Keep BASIC or NONE.
    private val loggingInterceptor = HttpLoggingInterceptor { message ->
        Logger.d(message, "API")
    }.apply {
        level = HttpLoggingInterceptor.Level.BASIC
    }
    
    private val okHttpClient = OkHttpClient.Builder()
        // 不跟随重定向：服务器若回 301/302（例如误填 http:// 被强制跳 https），
        // OkHttp 默认会把 POST 降级成 GET —— 消息提交会静默变成"查列表"，
        // 表面上还不报错。这里直接让 3xx 成为失败，并在下面给出可读的错误。
        .followRedirects(false)
        .addInterceptor(loggingInterceptor)
        // Attach the shared API key to every request to the configured MH server
        // (header). 只对**配置的那台主机**加：key 是凭据，不该出现在任何第三方请求里
        // —— 例如服务端 metadata 里那个独立 blob 源的 URL（客户端本来也不请求它）。
        .addInterceptor { chain ->
            val original = chain.request()
            val key = preferences.apiKey
            val sameHost = AttachmentUrls.shouldAttachApiKey(
                requestUrl = original.url.toString(),
                serverUrl = preferences.effectiveServerUrl
            )
            val request = if (key.isNotBlank() && sameHost) {
                original.newBuilder().header("X-API-Key", key).build()
            } else original
            chain.proceed(request)
        }
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 给图片加载库（Coil）用的同一个客户端。
     *
     * 必须共用：缩略图是从 `{serverUrl}/api/v1/blobs/<key>` 拉的，需要 `X-API-Key`，
     * 而鉴权只在上面这个拦截器里。Coil 那边再配一层磁盘缓存，图片就不会反复下载。
     */
    val httpClient: OkHttpClient get() = okHttpClient
    
    private fun createApiService(): MessageHubApiService {
        val retrofit = Retrofit.Builder()
            .baseUrl(preferences.effectiveServerUrl.ensureTrailingSlash())
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        
        return retrofit.create(MessageHubApiService::class.java)
    }

    /**
     * 问答专用的读超时：**120 秒**，而不是上传用的 30 秒。
     *
     * `/qa/ask` 是一次四步 LLM pipeline（改写 → 抽实体 → 召回 → 生成），实测 ~3 秒，
     * 但慢模型、长上下文、服务端重试都可能到几十秒 —— 沿用 30 秒会把"答案马上要出来了"
     * 变成一次网络错误，用户看到的是"连接失败"，而服务器其实答完了。
     *
     * 用 `newBuilder()` 而不是另造客户端：连接池、拦截器（含 `X-API-Key` 那层）都跟着走，
     * 只改超时这一件事。
     */
    private val qaOkHttpClient by lazy {
        okHttpClient.newBuilder()
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .build()
    }

    private fun createQaApiService(): MessageHubApiService {
        val retrofit = Retrofit.Builder()
            .baseUrl(preferences.effectiveServerUrl.ensureTrailingSlash())
            .client(qaOkHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        return retrofit.create(MessageHubApiService::class.java)
    }

    /**
     * Uploads one local memory to MH as a message.
     * Success is determined by HTTP 2xx (MH returns 201 + message id).
     */
    suspend fun createMemory(request: MemoryCreationRequest): ProcessingResult<Memory> {
        return withContext(Dispatchers.IO) {
            try {
                val apiService = createApiService()
                val apiRequest = request.toMessageCreateRequest()
                
                Logger.d("Creating message on MH server: ${request.content.take(50)}...")
                
                val response = apiService.createMessage(apiRequest)
                
                if (response.isSuccessful) {
                    val body = response.body()
                    val serverId = body?.id ?: body?.data?.id
                    Logger.i("Message created on MH server (HTTP ${response.code()}): id=$serverId")
                    ProcessingResult.Success(
                        Memory(
                            title = request.content.take(50),
                            content = request.content,
                            sourceType = request.sourceType,
                            metadata = request.metadata,
                            isUploaded = true,
                            serverMessageId = serverId?.takeIf { it.isNotBlank() }
                        )
                    )
                } else {
                    val errorMsg = ApiErrorMapper.describe(
                        code = response.code(),
                        rawErrorBody = response.errorBody()?.string(),
                        statusMessage = response.message()
                    )
                    Logger.e("MH API error: $errorMsg")
                    ProcessingResult.Error("Network error: $errorMsg")
                }
            } catch (e: Exception) {
                Logger.e("Failed to create message on MH server: ${e.message}", e)
                ProcessingResult.Error("Connection failed: ${e.message}")
            }
        }
    }

    /**
     * 带附件的提交：`POST /api/v1/messages` multipart/form-data（契约见 [MessageMultipartBuilder]）。
     *
     * type 由附件决定：带了非图片附件（PDF/文本）→ `DOCUMENT`，否则 `NOTE`；
     * sender 按产品决定填**设备名**（`Build.MODEL` 去空格，见 AppPreferences.manualSender）。
     * 附件字节已经在上传前准备好（该压的已经压过），这里不再做任何处理。
     */
    suspend fun createMemoryWithAttachments(
        request: MemoryCreationRequest,
        attachments: List<AttachmentPayload>,
        maxBytes: Long = AttachmentLimits.fallback().maxBytes
    ): ProcessingResult<Memory> {
        return withContext(Dispatchers.IO) {
            try {
                val apiService = createApiService()
                val type = MessageMapper.mapManualType(attachments.map { it.mimeType })
                val sender = preferences.manualSender
                val body = MessageMultipartBuilder.build(
                    sourceDeviceId = preferences.deviceId,
                    type = type,
                    sender = sender,
                    content = request.content,
                    timestamp = MessageMapper.isoTimestampUtc(request.metadata["timestamp"]?.toLongOrNull()),
                    metadata = request.metadata,
                    attachments = attachments
                )

                Logger.i(
                    "Creating MH message with ${attachments.size} attachment(s): " +
                        "type=$type, sender=$sender, content=${request.content.length} chars"
                )

                val response = apiService.createMessageMultipart(body)

                if (response.isSuccessful) {
                    val responseBody = response.body()
                    val serverId = responseBody?.id ?: responseBody?.data?.id
                    Logger.i("Message with attachments created on MH server (HTTP ${response.code()}): id=$serverId")
                    // 上传响应里已经带了每个附件的初始状态（`extraction.status = pending`），
                    // 先落一份，用户回到列表就能看到附件条目，不用等第一次轮询回来。
                    val initialAttachments = responseBody?.attachments.orEmpty().mapNotNull { it.toDomain() }
                    val rejected = responseBody?.rejected.orEmpty().map { it.toDomain() }
                    if (rejected.isNotEmpty()) {
                        Logger.w("MH rejected ${rejected.size} attachment(s): " +
                            rejected.joinToString { "${it.name}(${it.reason})" })
                    }
                    ProcessingResult.Success(
                        Memory(
                            title = request.content.take(50).ifBlank {
                                attachments.firstOrNull()?.fileName ?: "附件"
                            },
                            content = request.content,
                            sourceType = request.sourceType,
                            metadata = request.metadata,
                            isUploaded = true,
                            serverMessageId = serverId?.takeIf { it.isNotBlank() },
                            attachments = initialAttachments,
                            skippedAttachments = rejected
                        )
                    )
                } else {
                    val errorMsg = ApiErrorMapper.describe(
                        code = response.code(),
                        rawErrorBody = response.errorBody()?.string(),
                        statusMessage = response.message(),
                        maxBytes = maxBytes
                    )
                    Logger.e("MH attachment upload failed: $errorMsg")
                    ProcessingResult.Error(errorMsg)
                }
            } catch (e: Exception) {
                Logger.e("Failed to upload attachments to MH server: ${e.message}", e)
                ProcessingResult.Error("Connection failed: ${e.message}")
            }
        }
    }

    /**
     * `GET /api/v1/attachments/limits`：上限与允许类型由服务器给，客户端不写死。
     * 拿不到时返回 [AttachmentLimits.fallback]（1 MB + 契约里的类型清单）。
     */
    suspend fun fetchAttachmentLimits(): ProcessingResult<AttachmentLimits> {
        return withContext(Dispatchers.IO) {
            try {
                val response = createApiService().getAttachmentLimits()
                if (response.isSuccessful) {
                    val limits = response.body()?.toDomain() ?: AttachmentLimits.fallback()
                    Logger.i(
                        "Attachment limits from server: max=${limits.maxBytes} bytes, " +
                            "allowed=${limits.allowedMimeTypes}"
                    )
                    ProcessingResult.Success(limits)
                } else {
                    val errorMsg = ApiErrorMapper.describe(
                        code = response.code(),
                        rawErrorBody = response.errorBody()?.string(),
                        statusMessage = response.message()
                    )
                    Logger.w("Attachment limits unavailable: $errorMsg")
                    ProcessingResult.Error(errorMsg)
                }
            } catch (e: Exception) {
                Logger.w("Attachment limits request failed: ${e.message}")
                ProcessingResult.Error("Connection failed: ${e.message}")
            }
        }
    }


    /**
     * `GET /api/v1/messages/<id>`：单条消息 + 附件列表 + 提取状态。
     *
     * 附件在这个接口里位于 `metadata.attachments`；提取出的文本**两处都要看**
     * （`applied_to_content == true` → 文本在 content 里；否则看 `extraction.text`），
     * 判定集中在 [com.jxitc.messagehub.domain.service.AttachmentPreviewRules]。
     */
    suspend fun fetchMessageDetail(serverMessageId: String): ProcessingResult<MessageAttachmentDetail> {
        if (serverMessageId.isBlank()) {
            return ProcessingResult.Error("消息没有服务器 id，无法查询附件状态")
        }
        return withContext(Dispatchers.IO) {
            try {
                val response = createApiService().getMessage(serverMessageId)
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    val detail = body.toDomain()
                    Logger.d(
                        "Message $serverMessageId: ${detail.attachments.size} attachment(s), " +
                            "pending=${detail.attachments.count { !(it.extraction?.status ?: ExtractionStatus.UNKNOWN).isTerminal }}"
                    )
                    ProcessingResult.Success(detail)
                } else {
                    val errorMsg = ApiErrorMapper.describe(
                        code = response.code(),
                        rawErrorBody = response.errorBody()?.string(),
                        statusMessage = response.message()
                    )
                    Logger.w("Attachment status query failed for $serverMessageId: $errorMsg")
                    ProcessingResult.Error(errorMsg)
                }
            } catch (e: Exception) {
                Logger.w("Attachment status query failed for $serverMessageId: ${e.message}")
                ProcessingResult.Error("Connection failed: ${e.message}")
            }
        }
    }

    // ========================================================================
    // 问知识库（qa）—— 见 QaRemoteSource
    // ========================================================================

    /**
     * `POST /api/v1/qa/ask`：一次问答返回答案 + 整条 pipeline + turn id。
     *
     * 服务器**失败也留痕**（502 带 `turn_id`），所以这里的 Error 只是"这次没答上来"，
     * 不代表服务器那边没记录。
     */
    override suspend fun askQuestion(question: String): ProcessingResult<ChatMessage> {
        val asked = question.trim()
        if (asked.isEmpty()) return ProcessingResult.Error("问题不能为空")
        return withContext(Dispatchers.IO) {
            try {
                val response = createQaApiService().askQuestion(
                    QaAskRequest(
                        question = asked,
                        conversationId = QaThread.conversationId(preferences.deviceId),
                        source = QaThread.SOURCE
                    )
                )
                if (!response.isSuccessful) {
                    val errorMsg = ApiErrorMapper.describe(
                        code = response.code(),
                        rawErrorBody = response.errorBody()?.string(),
                        statusMessage = response.message()
                    )
                    Logger.e("QA ask failed: $errorMsg")
                    return@withContext ProcessingResult.Error(errorMsg)
                }
                val turn = response.body()?.turn?.toDomain()
                if (turn == null) {
                    Logger.e("QA ask returned no turn (HTTP ${response.code()})")
                    return@withContext ProcessingResult.Error("服务器返回的问答记录不完整")
                }
                Logger.i(
                    "QA answered in ${turn.elapsedMs}ms: ${turn.sources.size} source(s), " +
                        "cited=${turn.cited}, cost=${turn.cost?.let { ChatFormat.money(it) }}"
                )
                ProcessingResult.Success(turn)
            } catch (e: Exception) {
                Logger.e("QA ask failed: ${e.message}", e)
                ProcessingResult.Error("连接失败：${e.message}")
            }
        }
    }

    /** `GET /api/v1/qa/turns`：聊天历史，最新在前，**不带** steps/sources。 */
    override suspend fun fetchTurns(limit: Int): ProcessingResult<List<ChatMessage>> {
        return withContext(Dispatchers.IO) {
            try {
                val response = createQaApiService().getQaTurns(
                    limit = limit,
                    conversationId = QaThread.conversationId(preferences.deviceId)
                )
                if (response.isSuccessful) {
                    val turns = response.body()?.turns.orEmpty().mapNotNull { it.toDomain() }
                    Logger.i("QA history: ${turns.size} turn(s) from server")
                    ProcessingResult.Success(turns)
                } else {
                    val errorMsg = ApiErrorMapper.describe(
                        code = response.code(),
                        rawErrorBody = response.errorBody()?.string(),
                        statusMessage = response.message()
                    )
                    Logger.w("QA history unavailable: $errorMsg")
                    ProcessingResult.Error(errorMsg)
                }
            } catch (e: Exception) {
                Logger.w("QA history request failed: ${e.message}")
                ProcessingResult.Error("连接失败：${e.message}")
            }
        }
    }

    /** `GET /api/v1/qa/turns/<id>`：补齐某条历史的完整过程。 */
    override suspend fun fetchTurn(turnId: String): ProcessingResult<ChatMessage> {
        if (turnId.isBlank()) return ProcessingResult.Error("缺少问答 id")
        return withContext(Dispatchers.IO) {
            try {
                val response = createQaApiService().getQaTurn(turnId)
                if (!response.isSuccessful) {
                    val errorMsg = ApiErrorMapper.describe(
                        code = response.code(),
                        rawErrorBody = response.errorBody()?.string(),
                        statusMessage = response.message()
                    )
                    Logger.w("QA turn $turnId unavailable: $errorMsg")
                    return@withContext ProcessingResult.Error(errorMsg)
                }
                val turn = response.body()?.turn?.toDomain()
                if (turn == null) ProcessingResult.Error("服务器返回的问答记录不完整")
                else ProcessingResult.Success(turn)
            } catch (e: Exception) {
                Logger.w("QA turn $turnId request failed: ${e.message}")
                ProcessingResult.Error("连接失败：${e.message}")
            }
        }
    }

    /** `POST /api/v1/qa/turns/<id>/rate`：[rating] 传 null 表示取消评价。 */
    override suspend fun rateTurn(
        turnId: String,
        rating: ChatRating?,
        note: String?
    ): ProcessingResult<ChatRatingUpdate> {
        if (turnId.isBlank()) return ProcessingResult.Error("缺少问答 id")
        return withContext(Dispatchers.IO) {
            try {
                val response = createQaApiService().rateQaTurn(
                    id = turnId,
                    // 取消评价按契约发空串（服务器把 ''/none/clear/null 归一成"没评"）
                    request = QaRateRequest(rating = rating?.wire ?: "", note = note)
                )
                if (!response.isSuccessful) {
                    val errorMsg = ApiErrorMapper.describe(
                        code = response.code(),
                        rawErrorBody = response.errorBody()?.string(),
                        statusMessage = response.message()
                    )
                    Logger.w("QA rate failed for $turnId: $errorMsg")
                    return@withContext ProcessingResult.Error(errorMsg)
                }
                val update = response.body()?.toDomain()
                if (update == null) ProcessingResult.Error("服务器没有确认这次评价")
                else {
                    Logger.i("QA turn $turnId rated: ${update.rating?.wire ?: "（取消）"}")
                    ProcessingResult.Success(update)
                }
            } catch (e: Exception) {
                Logger.w("QA rate request failed for $turnId: ${e.message}")
                ProcessingResult.Error("连接失败：${e.message}")
            }
        }
    }

    /**
     * 附件原件的**稳定下载地址**：只用 `key` + 配置的 serverUrl 拼，不用接口返回的 `url`
     * （那个指向独立 blob 源，带不了 `X-API-Key`）—— 规则见 [AttachmentUrls]。
     */
    fun blobUrlFor(key: String): String = AttachmentUrls.blobUrl(preferences.effectiveServerUrl, key)

    /** Pulls messages from MH (page/per_page are MH's pagination params). */
    suspend fun getMemories(limit: Int = 50, offset: Int = 0): ProcessingResult<List<Memory>> {
        return withContext(Dispatchers.IO) {
            try {
                val apiService = createApiService()
                val page = (offset / limit) + 1

                Logger.d("Fetching messages from MH server (page: $page, per_page: $limit)")

                val response = apiService.getMessages(page = page, perPage = limit)

                if (response.isSuccessful) {
                    val body = response.body()
                    if (body != null) {
                        val memories = body.messages.map { it.toDomainModel() }
                        Logger.i("Fetched ${memories.size} messages from MH server (total: ${body.total})")
                        ProcessingResult.Success(memories)
                    } else {
                        ProcessingResult.Error("MH server returned empty body")
                    }
                } else {
                    val errorMsg = ApiErrorMapper.describe(
                        code = response.code(),
                        rawErrorBody = response.errorBody()?.string(),
                        statusMessage = response.message()
                    )
                    Logger.e("MH API error: $errorMsg")
                    ProcessingResult.Error("Network error: $errorMsg")
                }
            } catch (e: Exception) {
                Logger.e("Failed to fetch messages from MH server: ${e.message}", e)
                ProcessingResult.Error("Connection failed: ${e.message}")
            }
        }
    }
    
    suspend fun healthCheck(): ProcessingResult<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                val apiService = createApiService()
                
                Logger.d("Checking server health at ${preferences.serverUrl}")
                
                val response = apiService.healthCheck()
                
                if (response.isSuccessful) {
                    Logger.i("Server health check passed")
                    ProcessingResult.Success(true)
                } else {
                    val errorMsg = ApiErrorMapper.describe(
                        code = response.code(),
                        rawErrorBody = response.errorBody()?.string(),
                        statusMessage = response.message()
                    )
                    Logger.e("Health check failed: $errorMsg")
                    ProcessingResult.Error("Health check failed: $errorMsg")
                }
            } catch (e: Exception) {
                Logger.e("Health check failed: ${e.message}", e)
                ProcessingResult.Error("Server unreachable: ${e.message}")
            }
        }
    }

    // ========================================================================
    // Local Memory -> MH Message mapping
    // ========================================================================

    private fun MemoryCreationRequest.toMessageCreateRequest(): MessageCreateRequest {
        val type = MessageMapper.mapToMessageType(sourceType, metadata)
        return MessageCreateRequest(
            sourceDeviceId = preferences.deviceId,
            type = type,
            // 手动添加的记忆（NOTE/DOCUMENT）sender 用设备名，见 MessageMapper.resolveSenderFor
            sender = MessageMapper.resolveSenderFor(type, metadata, preferences.manualSender),
            content = content,
            timestamp = MessageMapper.isoTimestampUtc(metadata["timestamp"]?.toLongOrNull()),
            metadata = metadata // pass through (contains phone/app source info)
        )
    }

    private fun String.ensureTrailingSlash(): String {
        return if (this.endsWith("/")) this else "$this/"
    }

    companion object {
        // source_device_id 来自 AppPreferences.deviceId（每台设备首次运行生成并持久化），
        // 不再硬编码：多台设备接入时，硬编码会让所有设备的数据混在同一个名字下。
    }
}
