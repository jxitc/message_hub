package com.jxitc.messagehub.data.remote

import com.google.gson.Gson
import com.jxitc.messagehub.domain.model.AttachmentPayload
import com.jxitc.messagehub.domain.service.AttachmentPolicy
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 构造 `POST /api/v1/messages` 的 multipart/form-data 请求体。
 *
 * 字段名/形态是**冻结的接口契约**，所以集中在这里一处，并用单测锁住
 * （见 MessageMultipartBuilderTest）：
 *
 *  - 文本字段：`source_device_id`、`type`、`sender`、`content`、`timestamp`、`metadata`
 *  - `metadata` 是**JSON 字符串**（不是嵌套对象 —— multipart 里没有"对象"这回事）
 *  - 文件字段名固定为 **`attachments`**，一个文件一个 part、可以重复出现
 *  - 没有正文但有附件时，`content` 传空字符串（服务器允许"有附件、无正文"）
 *
 * 纯 OkHttp 类型、无 Android 依赖，因此可以在 JVM 单测里把请求体整个 dump 出来断言。
 */
object MessageMultipartBuilder {

    const val FIELD_SOURCE_DEVICE_ID = "source_device_id"
    const val FIELD_TYPE = "type"
    const val FIELD_SENDER = "sender"
    const val FIELD_CONTENT = "content"
    const val FIELD_TIMESTAMP = "timestamp"
    const val FIELD_METADATA = "metadata"

    /** 文件部分的字段名，契约固定为 `attachments`（可重复）。 */
    const val FIELD_ATTACHMENTS = "attachments"

    private val TEXT_MEDIA_TYPE = "text/plain; charset=utf-8".toMediaTypeOrNull()
    private val gson = Gson()

    fun build(
        sourceDeviceId: String,
        type: String,
        sender: String,
        content: String,
        timestamp: String,
        metadata: Map<String, Any>,
        attachments: List<AttachmentPayload> = emptyList()
    ): MultipartBody {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)

        fun text(name: String, value: String) {
            builder.addFormDataPart(name, null, value.toRequestBody(TEXT_MEDIA_TYPE))
        }

        text(FIELD_SOURCE_DEVICE_ID, sourceDeviceId)
        text(FIELD_TYPE, type)
        text(FIELD_SENDER, sender)
        text(FIELD_CONTENT, content)
        text(FIELD_TIMESTAMP, timestamp)
        // metadata 必须是 JSON 字符串，不是对象。
        text(FIELD_METADATA, metadataJson(metadata))

        for (attachment in attachments) {
            val mediaType = (attachment.mimeType.ifBlank { AttachmentPolicy.MIME_FALLBACK })
                .toMediaTypeOrNull()
            builder.addFormDataPart(
                FIELD_ATTACHMENTS,
                attachment.fileName,
                attachment.bytes.toRequestBody(mediaType)
            )
        }

        return builder.build()
    }

    /** metadata → JSON 字符串。空 map 也要发 `{}`，别发空串（那会让服务器按缺字段处理）。 */
    fun metadataJson(metadata: Map<String, Any>): String = gson.toJson(metadata.ifEmpty { emptyMap<String, Any>() })

    /** 便捷重载：只要文本字段（无附件）时的完整请求体。 */
    fun build(request: MessageCreateRequest, attachments: List<AttachmentPayload> = emptyList()): MultipartBody =
        build(
            sourceDeviceId = request.sourceDeviceId,
            type = request.type,
            sender = request.sender,
            content = request.content,
            timestamp = request.timestamp,
            metadata = request.metadata,
            attachments = attachments
        )
}
