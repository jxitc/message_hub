package com.jxitc.messagehub.data.remote

import com.jxitc.messagehub.data.local.AppPreferences
import com.jxitc.messagehub.domain.model.Memory
import com.jxitc.messagehub.domain.model.MemoryCreationRequest
import com.jxitc.messagehub.domain.model.ProcessingResult
import com.jxitc.messagehub.domain.model.SourceType
import com.jxitc.messagehub.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Network client for the Message Hub (MH) server.
 *
 * Converts local [MemoryCreationRequest]s into MH `POST /api/v1/messages` payloads
 * and reports them via Retrofit. Upload success = HTTP 2xx (MH returns 201 + id).
 */
class MessageHubApiClient(
    private val preferences: AppPreferences
) {

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
        .addInterceptor(loggingInterceptor)
        // Attach the shared API key to every request to MH /api/v1/* (header).
        .addInterceptor { chain ->
            val original = chain.request()
            val key = preferences.apiKey
            val request = if (key.isNotBlank()) {
                original.newBuilder().header("X-API-Key", key).build()
            } else original
            chain.proceed(request)
        }
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private fun createApiService(): MessageHubApiService {
        val retrofit = Retrofit.Builder()
            .baseUrl(preferences.serverUrl.ensureTrailingSlash())
            .client(okHttpClient)
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
                            isUploaded = true
                        )
                    )
                } else {
                    val errorMsg = "HTTP ${response.code()}: ${response.message()}"
                    Logger.e("MH API error: $errorMsg")
                    ProcessingResult.Error("Network error: $errorMsg")
                }
            } catch (e: Exception) {
                Logger.e("Failed to create message on MH server: ${e.message}", e)
                ProcessingResult.Error("Connection failed: ${e.message}")
            }
        }
    }

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
                    val errorMsg = "HTTP ${response.code()}: ${response.message()}"
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
                    val errorMsg = "HTTP ${response.code()}: ${response.message()}"
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
        return MessageCreateRequest(
            sourceDeviceId = preferences.deviceId,
            type = MessageMapper.mapToMessageType(sourceType, metadata),
            sender = MessageMapper.resolveSender(metadata),
            content = content,
            timestamp = resolveTimestamp(metadata),
            metadata = metadata // pass through (contains phone/app source info)
        )
    }

    /**
     * ISO8601 UTC timestamp for the message.
     * Prefers metadata["timestamp"] (epoch millis, set by the SMS/notification processors,
     * which is the same event time stored in Memory.createdAt); falls back to now.
     */
    private fun resolveTimestamp(metadata: Map<String, String>): String {
        val epochMillis = metadata["timestamp"]?.toLongOrNull()
        val instant = if (epochMillis != null) Instant.ofEpochMilli(epochMillis) else Instant.now()
        return instant.toString() // e.g. 2026-08-31T07:00:00Z
    }

    private fun String.ensureTrailingSlash(): String {
        return if (this.endsWith("/")) this else "$this/"
    }

    companion object {
        // source_device_id 来自 AppPreferences.deviceId（每台设备首次运行生成并持久化），
        // 不再硬编码：多台设备接入时，硬编码会让所有设备的数据混在同一个名字下。
    }
}
