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
 *  - GET  /api/v1/messages/<id> -> one message (attachment extraction status)
 *  - GET  /api/v1/attachments/limits -> attachment size cap + allowed types
 *  - POST /api/v1/qa/ask     -> ask the knowledge base (answer + whole pipeline)
 *  - GET  /api/v1/qa/turns   -> chat history
 *  - POST /api/v1/qa/turns/<id>/rate -> rate one answer
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

    /**
     * 单条消息（含附件的提取状态）：
     * `GET /api/v1/messages/<id>` → `{id, content, metadata:{attachments:[…]}}`。
     *
     * 附件在这个接口里位于 **`metadata.attachments`**（与上传响应的顶层 `attachments` 不同）。
     */
    @GET("api/v1/messages/{id}")
    suspend fun getMessage(
        @Path("id") id: String
    ): Response<MessageDetailApiData>

    @GET("api/v1/messages")
    suspend fun getMessages(
        @Query("page") page: Int = 1,
        @Query("per_page") perPage: Int = 50,
        @Query("device") device: String? = null,
        @Query("type") type: String? = null
    ): Response<MessageListResponse>

    // ------------------------------------------------------------------
    // 问知识库（qa）：提问 / 历史 / 单条详情 / 评价
    // ------------------------------------------------------------------

    /** 问一次：服务器一条 POST 就回"答案 + 整条 pipeline"，不用客户端再问第二次。 */
    @POST("api/v1/qa/ask")
    suspend fun askQuestion(
        @Body request: QaAskRequest
    ): Response<QaAskResponse>

    /** 聊天历史，**最新在前**。不带 steps/sources（详情按 id 单独取）。 */
    @GET("api/v1/qa/turns")
    suspend fun getQaTurns(
        @Query("limit") limit: Int = 30,
        @Query("conversation_id") conversationId: String? = null
    ): Response<QaTurnsResponse>

    /** 单条 turn 的完整过程（含 steps/sources）。 */
    @GET("api/v1/qa/turns/{id}")
    suspend fun getQaTurn(
        @Path("id") id: String
    ): Response<QaTurnResponse>

    /** 评价：`{"rating":"good"}` / `{"rating":"bad","note":"…"}` / `{"rating":""}` 取消。 */
    @POST("api/v1/qa/turns/{id}/rate")
    suspend fun rateQaTurn(
        @Path("id") id: String,
        @Body request: QaRateRequest
    ): Response<QaRateResponse>

    @GET("health")
    suspend fun healthCheck(): Response<Map<String, Any>>
}
