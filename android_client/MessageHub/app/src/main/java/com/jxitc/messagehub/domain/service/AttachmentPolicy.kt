package com.jxitc.messagehub.domain.service

import com.jxitc.messagehub.domain.model.AttachmentKind
import java.util.Locale

/**
 * 「手动添加记忆」附件的**纯规则**：上限/允许类型、压缩参数、类型与 sender 取值、展示格式。
 *
 * 这里不引用任何 Android 类（连 Build 都不碰，设备型号作为参数传进来），
 * 所以整块逻辑可以在 JVM 单测里直接跑 —— 见 AttachmentPolicyTest。
 */
object AttachmentPolicy {

    /** 服务器上限的兜底值，与 `GET /api/v1/attachments/limits` 的 max_bytes 一致。 */
    const val DEFAULT_MAX_BYTES = 1_048_576L

    /** 允许类型的兜底值，与 limits 的 allowed 一致。 */
    val DEFAULT_ALLOWED_MIME_TYPES = listOf(
        "image/png", "image/jpeg", "image/gif", "image/webp",
        "application/pdf", "text/plain"
    )

    /** 可压缩的图片类型；其余一律按"不可无损压缩"处理（不尝试压 PDF）。 */
    val IMAGE_MIME_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")

    /** 图片压缩：长边上限 2048px。 */
    const val MAX_IMAGE_LONG_EDGE = 2048

    /** 图片压缩：JPEG 质量阶梯，从 80 起逐级降到 50。 */
    val JPEG_QUALITY_LADDER = listOf(80, 70, 60, 50)

    /**
     * 单次读取上限。比这还大的文件（误选了几百 MB 的视频/压缩包）不去读进内存，
     * 直接拒绝 —— 读进来只会 OOM，而它反正也过不了 1 MB 的服务器上限。
     */
    const val MAX_READ_BYTES = 32L * 1024 * 1024

    /** 拿不到 Content-Type 时发送/展示用的兜底类型。 */
    const val MIME_FALLBACK = "application/octet-stream"

    /** 手动添加记忆的两种 type（见服务器 metadata_policy.MESSAGE_TYPES）。 */
    const val TYPE_NOTE = "NOTE"
    const val TYPE_DOCUMENT = "DOCUMENT"

    /**
     * 服务器会按扩展名直接拒掉的"文本类但可执行"的扩展名（见 blob_store._DANGEROUS_TEXT_EXT）。
     * 客户端提前拦一道，用户不用等上传完才看到拒绝。
     */
    val DANGEROUS_EXTENSIONS = setOf("svg", "html", "htm", "xhtml", "js", "mjs", "xml")

    /** 常见同义 / 别名 MIME，归一到契约里用的那几种。 */
    private val MIME_ALIASES = mapOf(
        "image/jpg" to "image/jpeg",
        "image/pjpeg" to "image/jpeg",
        "image/x-png" to "image/png",
        "application/x-pdf" to "application/pdf",
        "text/x-log" to "text/plain",
        "text/markdown" to "text/plain",
        "text/x-markdown" to "text/plain",
        "text/csv" to "text/plain"
    )

    private val WHITESPACE = Regex("\\s+")

    /** 扩展名 → MIME。服务器按内容嗅探，这里只是拿不到 Content-Type 时的兜底。 */
    private val EXTENSION_MIME = mapOf(
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "pdf" to "application/pdf",
        "txt" to "text/plain",
        "md" to "text/plain",
        "log" to "text/plain",
        "csv" to "text/plain",
        "tsv" to "text/plain",
        "json" to "text/plain",
        "yaml" to "text/plain",
        "yml" to "text/plain"
    )

    /** 去掉 `; charset=utf-8` 之类的参数、转小写、按 [MIME_ALIASES] 归一。 */
    fun normalizeMime(raw: String?): String {
        val base = raw?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (base.isEmpty()) return ""
        return MIME_ALIASES[base] ?: base
    }

    fun fileNameExtension(fileName: String?): String =
        (fileName ?: "").substringAfterLast('.', "").trim().lowercase(Locale.ROOT)

    fun mimeFromFileName(fileName: String?): String = EXTENSION_MIME[fileNameExtension(fileName)] ?: ""

    fun isImage(mimeType: String?): Boolean = normalizeMime(mimeType) in IMAGE_MIME_TYPES

    fun kindOf(mimeType: String?): AttachmentKind =
        if (isImage(mimeType)) AttachmentKind.IMAGE else AttachmentKind.FILE

    fun isDangerousExtension(fileName: String?): Boolean =
        fileNameExtension(fileName) in DANGEROUS_EXTENSIONS

    /**
     * 客户端预检：这个类型服务器会不会收。
     *
     * 规则来自 limits.allowed（拿不到就用契约里的兜底清单）：
     *  - 命中清单 → 收；
     *  - 以 `text/` 开头的子类型 → 收（服务器是按内容嗅探的：UTF-8 且没有 NUL 字节就是
     *    text/plain，所以 .md/.csv 这类工具产出的文本照样能进，最终仍以服务器裁决为准）。
     */
    fun isAcceptableMime(mimeType: String?, fileName: String?, allowedMimeTypes: List<String>): Boolean {
        val allowed = allowedMimeTypes
            .map { normalizeMime(it) }
            .filter { it.isNotEmpty() }
            .ifEmpty { DEFAULT_ALLOWED_MIME_TYPES }
        val mime = normalizeMime(mimeType).ifBlank { mimeFromFileName(fileName) }
        if (mime.isEmpty()) return false
        if (allowed.contains(mime)) return true
        return mime.startsWith("text/") && allowed.any { it.startsWith("text/") }
    }

    /**
     * 单次提交的附件总字节预算。
     *
     * 服务器不仅限制单个文件，还对整个 multipart 请求体设了上限
     * （`api/v1/messages.py` 里是 `max_bytes * 4 + 256KB`，超过直接 413）。
     * 冻结的契约里没写这条，但踩上去只会得到一个 413 —— 所以在客户端先算一遍，
     * 提前把话说明白。计算方式与服务器一致，服务器调整 max_bytes 时这里跟着变。
     */
    fun totalAttachmentBudget(maxBytes: Long): Long = maxBytes * 4 + 256L * 1024

    /** 所有附件加起来是否超过一次提交的预算。 */
    fun totalSizeExceedsBudget(totalBytes: Long, maxBytes: Long): Boolean =
        totalBytes > totalAttachmentBudget(maxBytes)

    /** 一次最多选几张图：4 张正好是预算内（每张最多 max_bytes），多选会被服务器按 413 拒。 */
    const val MAX_IMAGE_PICKS = 4

    /**
     * 手动添加记忆的 type：只要带了**非图片**附件（PDF/文本等）就是 DOCUMENT，
     * 纯文本或纯图片是 NOTE。
     */
    fun typeForMimeTypes(mimeTypes: List<String>): String =
        if (mimeTypes.any { !isImage(it) }) TYPE_DOCUMENT else TYPE_NOTE

    /**
     * SAF 文件选择器（OpenDocument）的筛选类型：直接用服务器公布的 allowed 去掉图片类型。
     *
     * `allowed` 里只要有 text/ 开头的类型，就再补一个 `text/` 通配 —— 让 .md/.csv 这类纯文本
     * 也能被选到（服务器是按**内容**嗅探的，UTF-8 文本一样会判成 text/plain，不会因此 415）。
     */
    fun filePickerMimeTypes(allowedMimeTypes: List<String>): List<String> {
        val nonImage = allowedMimeTypes
            .map { it.trim() }
            .filter { it.isNotEmpty() && !isImage(it) }
        val withTextWildcard =
            if (nonImage.any { it.startsWith("text/") }) nonImage + "text/*" else nonImage
        return withTextWildcard.ifEmpty { listOf("application/pdf", "text/plain") }.distinct()
    }

    /**
     * sender：手动添加时填**设备名**（例：`Build.MODEL` 去空格），这是产品决定 ——
     * 不用用户名、也不允许空值。取不到型号时回退成 "Android"。
     */
    fun senderFromDeviceModel(model: String?): String {
        val cleaned = model.orEmpty().replace(WHITESPACE, "").trim().take(255)
        return cleaned.ifBlank { "Android" }
    }

    /** 人类可读的大小：B / KB / MB（1 KB = 1024 B）。 */
    fun formatSize(bytes: Long): String {
        if (bytes < 0) return "未知大小"
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return trimZero(kb) + " KB"
        return trimZero(kb / 1024.0) + " MB"
    }

    private fun trimZero(value: Double): String {
        val text = String.format(Locale.US, "%.1f", value)
        return if (text.endsWith(".0")) text.dropLast(2) else text
    }
}
