package com.jxitc.messagehub.domain.model

import com.jxitc.messagehub.domain.service.AttachmentPolicy

/** 附件的两个大类：图片（可以在客户端压缩）与其他文件（PDF 等不可无损压缩，超限只能拒绝）。 */
enum class AttachmentKind { IMAGE, FILE }

/**
 * 上传时真正要发出去的字节 + 元数据。
 *
 * 故意**不是** data class：内容里有 ByteArray，data class 生成的 equals/hashCode 会退化成
 * 引用比较，容易让人误以为在比较内容。这里只当容器用。
 */
class AttachmentPayload(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray
) {
    val sizeBytes: Long get() = bytes.size.toLong()

    /** 日志/提示里用，避免把整个字节数组打进日志。 */
    override fun toString(): String = "AttachmentPayload($fileName, $mimeType, $sizeBytes bytes)"
}

/**
 * 「手动添加记忆」里已选中的附件：已经过类型校验与（超过上限时的）压缩，等待上传。
 *
 * [uri] 存的是 content:// 字符串而不是 android.net.Uri —— 本类因此在 JVM 单测里也能构造。
 * 真正的字节放在 [payload]（选完就立刻读出来）：上传时不再依赖 ContentResolver，
 * 用户选完图之后系统收回临时读权限也不影响上传。
 */
data class Attachment(
    val uri: String,
    val fileName: String,
    val mimeType: String,
    val kind: AttachmentKind,
    val payload: AttachmentPayload,
    /** 处理前的大小，用于在 UI 上说明"已从 X 压到 Y"。 */
    val originalSizeBytes: Long = payload.sizeBytes,
    /** true 表示这张图被重新编码压缩过（≤ 上限的图片一律原样上传，不会走到这里）。 */
    val compressed: Boolean = false
) {
    val sizeBytes: Long get() = payload.sizeBytes
}

/**
 * `GET /api/v1/attachments/limits` 的结果。
 *
 * 上限与允许类型**不写死在客户端**：服务器说了算（含将来的调整），
 * 拿不到时用 [fallback] 兜底（1 MB + 契约里的类型清单）。
 */
data class AttachmentLimits(
    val maxBytes: Long,
    val allowedMimeTypes: List<String>
) {
    companion object {
        fun fallback(): AttachmentLimits = AttachmentLimits(
            maxBytes = AttachmentPolicy.DEFAULT_MAX_BYTES,
            allowedMimeTypes = AttachmentPolicy.DEFAULT_ALLOWED_MIME_TYPES
        )
    }
}
