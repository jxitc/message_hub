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

    // HTTP logging: Enabled for debugging (set to NONE for production builds)
    // TODO: Change to Level.NONE before releasing to production
    private val loggingInterceptor = HttpLoggingInterceptor { message ->
        Logger.d(message, "API")
    }.apply {
        level = HttpLoggingInterceptor.Level.BODY  // Change to NONE for production
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
            sourceDeviceId = SOURCE_DEVICE_ID,
            type = mapToMessageType(sourceType, metadata),
            sender = resolveSender(metadata),
            content = content,
            timestamp = resolveTimestamp(metadata),
            metadata = metadata // pass through (contains phone/app source info)
        )
    }

    /** SourceType -> MH message type. MANUAL/others fall back to SMS unless metadata looks like a notification. */
    private fun mapToMessageType(sourceType: SourceType, metadata: Map<String, String>): String {
        return when (sourceType) {
            SourceType.SMS -> "SMS"
            SourceType.NOTIFICATION -> "PUSH_NOTIFICATION"
            SourceType.MANUAL -> {
                val looksLikeNotification = metadata.containsKey("app_name") ||
                    metadata.containsKey("package_name") ||
                    metadata.containsKey("notification_id")
                if (looksLikeNotification) "PUSH_NOTIFICATION" else "SMS"
            }
            // MH MVP only supports SMS/PUSH_NOTIFICATION/CALL_LOG/EMAIL
            SourceType.SCREENSHOT, SourceType.SHARE_INTENT -> "SMS"
        }
    }

    /** Sender = contact_name > phone_number > app_name > package_name > "unknown". */
    private fun resolveSender(metadata: Map<String, String>): String {
        val candidate = listOf("contact_name", "phone_number", "app_name", "package_name")
            .mapNotNull { metadata[it]?.takeIf { v -> v.isNotBlank() } }
            .firstOrNull()
        return candidate ?: "unknown"
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
        /** Device id reported to MH. TODO: replace with a registered device id (MH device registration is a stretch goal). */
        private const val SOURCE_DEVICE_ID = "android-phone-1"
    }
}
