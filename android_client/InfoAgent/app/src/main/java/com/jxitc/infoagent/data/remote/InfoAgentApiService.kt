package com.jxitc.infoagent.data.remote

import retrofit2.Response
import retrofit2.http.*

/**
 * Retrofit API definition for the Message Hub (MH) server.
 *
 * MH endpoints:
 *  - POST /api/v1/messages   -> report one message (201 + {"message","id","data"})
 *  - GET  /api/v1/messages   -> list messages with pagination
 *  - GET  /health            -> health check {"status":"healthy",...}
 */
interface InfoAgentApiService {

    @POST("api/v1/messages")
    suspend fun createMessage(
        @Body request: MessageCreateRequest
    ): Response<MessageApiResponse>

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
