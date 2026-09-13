package com.jxitc.messagehub.presentation.screen

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.jxitc.messagehub.domain.model.ExtractedTextBlock
import com.jxitc.messagehub.domain.model.ExtractionStatus
import com.jxitc.messagehub.domain.model.ServerAttachment
import com.jxitc.messagehub.domain.model.SkippedAttachment
import com.jxitc.messagehub.domain.service.AttachmentPreviewRules

/**
 * 消息详情里的附件区：每个附件 = 类型方块/缩略图 + 名称 + 大小 + 状态徽标 + 提取文本。
 *
 * 两条来自契约的硬要求：
 *  - **提取文本两处都要看**（正文 or extraction.text），来源必须写明
 *    —— 否则用户会把 OCR 猜的当成发件人写的（规则在 [AttachmentPreviewRules]）。
 *  - **"有附件但没存下来"**也要显示（名称 + 原因），别当成附件去下载。
 */
@Composable
fun AttachmentSection(
    attachments: List<ServerAttachment>,
    skipped: List<SkippedAttachment>,
    messageContent: String,
    blobUrlFor: (String) -> String,
    onOpenOriginal: (ServerAttachment) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (attachments.isEmpty() && skipped.isEmpty()) return

    val textBlocks = remember(attachments, messageContent) {
        AttachmentPreviewRules.extractedTexts(messageContent, attachments)
            .associateBy { it.attachmentKey }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (attachments.isNotEmpty()) {
            Text(
                text = "附件（${attachments.size}）",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            attachments.forEach { attachment ->
                AttachmentCard(
                    attachment = attachment,
                    textBlock = textBlocks[attachment.key],
                    messageContent = messageContent,
                    blobUrlFor = blobUrlFor,
                    onOpenOriginal = { onOpenOriginal(attachment) }
                )
            }
        }

        if (skipped.isNotEmpty()) {
            SkippedAttachmentsCard(skipped)
        }
    }
}

/** 「有附件但没存下来」：名称 + 原因，明确说明它们不在服务器上。 */
@Composable
private fun SkippedAttachmentsCard(skipped: List<SkippedAttachment>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "未保存的附件（${skipped.size}）",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = "这些文件在原始消息里存在，但没能存到服务器（见下面原因），点不开也下载不了。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            skipped.forEach { item ->
                val size = item.readableSize
                Text(
                    text = buildString {
                        append("• ").append(item.name)
                        if (size != null) append(" · ").append(size)
                        item.reason?.let { append(" · ").append(it) }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

@Composable
private fun AttachmentCard(
    attachment: ServerAttachment,
    textBlock: ExtractedTextBlock?,
    messageContent: String,
    blobUrlFor: (String) -> String,
    onOpenOriginal: () -> Unit
) {
    val extraction = attachment.extraction
    val status = extraction?.status ?: ExtractionStatus.UNKNOWN

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (attachment.isImage) {
                    AttachmentThumbnail(attachment = attachment, blobUrlFor = blobUrlFor)
                } else {
                    AttachmentTypeTile(attachment = attachment)
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = attachment.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = listOfNotNull(
                            attachment.readableSize.takeIf { attachment.size >= 0 },
                            attachment.mime.takeIf { it.isNotBlank() }
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                StatusBadge(status = status, attachment = attachment)
            }

            if (status == ExtractionStatus.PENDING) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "服务端正在提取文本…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            extraction?.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            extraction?.note?.takeIf { it.isNotBlank() && status != ExtractionStatus.PENDING }?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            textBlock?.let { ExtractedTextBox(it) }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onOpenOriginal) {
                    Text(if (attachment.isImage) "查看大图 / 打开原件" else "打开原件")
                }
            }
        }
    }
}

/**
 * 提取文本：标明来源（**已写入消息正文** vs **OCR 结果**）、可选中、可复制、可展开。
 * 长文本默认折叠 8 行 —— 几十页的 OCR 结果不该把详情页撑成无限长。
 */
@Composable
private fun ExtractedTextBox(block: ExtractedTextBlock) {
    var expanded by remember(block.attachmentKey) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = AttachmentPreviewRules.detailLine(block),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(block.text))
                Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }) {
                Text("复制", style = MaterialTheme.typography.labelMedium)
            }
        }
        SelectionContainer {
            Text(
                text = block.text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = if (expanded) Int.MAX_VALUE else 8,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (!expanded && block.text.length > 160) {
            TextButton(onClick = { expanded = true }, contentPadding = PaddingValues(0.dp)) {
                Text("展开全文（${block.text.length} 字）", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** 状态徽标：提取中 / N 字 / 无文字 / 提取失败 / 不支持。 */
@Composable
private fun StatusBadge(status: ExtractionStatus, attachment: ServerAttachment) {
    val label = AttachmentPreviewRules.badgeLabelFor(attachment)
        ?: if (status == ExtractionStatus.UNKNOWN) "状态未知" else null
    if (label == null) return
    val tone = AttachmentPreviewRules.badgeTone(status)
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (tone) {
        AttachmentPreviewRules.BadgeTone.POSITIVE -> scheme.primaryContainer to scheme.onPrimaryContainer
        AttachmentPreviewRules.BadgeTone.WARNING -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        AttachmentPreviewRules.BadgeTone.ERROR -> scheme.errorContainer to scheme.onErrorContainer
        AttachmentPreviewRules.BadgeTone.NEUTRAL -> scheme.surfaceVariant to scheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(container)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = content)
    }
}

/**
 * 图片缩略图：Coil 从 `{serverUrl}/api/v1/blobs/<key>` 拉（带 `X-API-Key`），
 * 走磁盘缓存，所以第二次进详情不会重新下载。加载中/失败时露出下面的类型方块。
 */
@Composable
private fun AttachmentThumbnail(attachment: ServerAttachment, blobUrlFor: (String) -> String) {
    Box(
        modifier = Modifier.size(56.dp),
        contentAlignment = Alignment.Center
    ) {
        AttachmentTypeTile(attachment = attachment, size = 56.dp)
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(blobUrlFor(attachment.key))
                .crossfade(true)
                .build(),
            contentDescription = attachment.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
        )
    }
}

/**
 * 类型方块：用三个字母标出类型（IMG / PDF / TXT / FILE）。
 *
 * 为什么是文字而不是图标字体：这个项目没有引 `material-icons-extended`
 * （material-icons-core 里没有图片/文档/PDF 这几个字形），为四个字形引一整包
 * 扩展图标会明显撑大 APK。文字标识同样"一眼看出类型"，而且图片本来就直接显示缩略图。
 */
@Composable
private fun AttachmentTypeTile(attachment: ServerAttachment, size: androidx.compose.ui.unit.Dp = 56.dp) {
    val label = when (AttachmentPreviewRules.iconFor(attachment)) {
        AttachmentPreviewRules.AttachmentIcon.IMAGE -> "IMG"
        AttachmentPreviewRules.AttachmentIcon.PDF -> "PDF"
        AttachmentPreviewRules.AttachmentIcon.TEXT -> "TXT"
        AttachmentPreviewRules.AttachmentIcon.FILE -> "FILE"
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}
