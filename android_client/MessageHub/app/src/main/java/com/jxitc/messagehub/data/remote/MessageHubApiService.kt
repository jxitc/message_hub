package com.jxitc.messagehub.data.remote

import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

/**
 * Retrofit API definition for the Message Hub (MH) server.
 *
 * MH endpoints:
 *  - POST /api/v1/messages   -> report one message (201 + {"message","id","data"})
 *  - GET  /api/v1/messages   -> list messages with pagination
 *  - GET  /api/v1/attachments/limits -> attachment size cap + allowed types
 *  - GET  /health            -> health check {"status":"healthy",...}
 */
interface MessageHubApiService {

    @POST("api/v1/messages")
    suspend fun createMessage(
        @Body request: MessageCreateRequest
    ): Response<MessageApiResponse>

    /**
     * 带附件的提交：multipart/form-data。
     *
     * 声明成 [RequestBody]（实际传 [okhttp3.MultipartBody]）是为了走 Retrofit 内置的
     * RequestBody 直通转换器，不让 Gson 转换器插手 —— 请求体由
     * [MessageMultipartBuilder] 自己拼好，字段名/形态契约集中在那一个地方。
     */
    @POST("api/v1/messages")
    suspend fun createMessageMultipart(
        @Body body: RequestBody
    ): Response<MessageApiResponse>

    /** 附件上限与允许类型，客户端不写死。 */
    @GET("api/v1/attachments/limits")
    suspend fun getAttachmentLimits(): Response<AttachmentLimitsResponse>

    @GET("api/v1/messages")
    suspend fun getMessages(
        @Query("page") page: Int = 1,
        @Query("per_page") perPage: Int = 50,
        @Query("device") device: String? = null,
        @Query("type") type: String? = null
    ): Response<MessageListResponse>

    @GET("health")
    suspend fun healthCheck(): Response<Map<String, Any>>
}
