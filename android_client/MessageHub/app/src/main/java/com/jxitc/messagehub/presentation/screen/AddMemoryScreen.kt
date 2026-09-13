package com.jxitc.messagehub.presentation.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jxitc.messagehub.domain.model.Attachment
import com.jxitc.messagehub.domain.model.AttachmentKind
import com.jxitc.messagehub.domain.service.AttachmentPolicy
import com.jxitc.messagehub.presentation.viewmodel.AddMemoryViewModel

/** 一次最多选几张图（Photo Picker 的多选上限）。 */
private const val MAX_IMAGE_PICKS = AttachmentPolicy.MAX_IMAGE_PICKS

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddMemoryScreen(
    viewModel: AddMemoryViewModel,
    onNavigateBack: () -> Unit = {}
) {
    val content by viewModel.content.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val isSubmitted by viewModel.isSubmitted.collectAsStateWithLifecycle()
    val attachments by viewModel.attachments.collectAsStateWithLifecycle()
    val limits by viewModel.limits.collectAsStateWithLifecycle()
    val isPreparing by viewModel.isPreparingAttachments.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val tracking by viewModel.tracking.collectAsStateWithLifecycle()

    val busy = isLoading || isPreparing

    // 页面可见性：一离开（切后台/返回）就停止轮询附件提取状态。
    // 用 LifecycleResumeEffect 而不是 DisposableEffect —— 切到别的 app 时 composable
    // 并没有被销毁，"不可见"必须包含这种情形。
    LifecycleResumeEffect(Unit) {
        viewModel.onScreenVisible()
        onPauseOrDispose { viewModel.onScreenHidden() }
    }

    // 相册：Android Photo Picker（API 33+ 原生；老系统由 androidx 自动回退到
    // ACTION_OPEN_DOCUMENT/GET_CONTENT），可多选。
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGE_PICKS)
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.addAttachmentUris(uris.map { it.toString() })
        }
    }

    // 文件：SAF OpenDocument，筛选类型直接来自服务器公布的 allowed（去掉图片类型）。
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.addAttachmentUris(listOf(it.toString())) }
    }

    val fileMimeTypes = remember(limits) {
        AttachmentPolicy.filePickerMimeTypes(limits.allowedMimeTypes).toTypedArray()
    }
    val maxAttachmentText = remember(limits) { AttachmentPolicy.formatSize(limits.maxBytes) }

    // 提交成功后的返回时机：
    //  - 没有附件要等（tracking == null）→ 立刻返回，保持原来的手感；
    //  - 有附件 → 留在页面上等提取结果（轮询 3s/5s/10s/20s/30s，上限 3 分钟），
    //    结束时再返回。"跳过"按钮可以立刻走人（走了之后列表页会接着轮询）。
    LaunchedEffect(isSubmitted) {
        if (isSubmitted && viewModel.tracking.value == null) {
            viewModel.resetSubmissionState()
            onNavigateBack()
        }
    }
    LaunchedEffect(tracking?.state) {
        if (tracking?.finished == true) {
            viewModel.resetSubmissionState()
            viewModel.clearTracking()
            onNavigateBack()
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Text(
            text = "Add Memory",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        
        Text(
            text = "Type anything you want to remember, and optionally attach photos or files. " +
                "MessageHub will generate a title and process it for you.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        // Content Input
        OutlinedTextField(
            value = content,
            onValueChange = viewModel::updateContent,
            label = { Text("Content") },
            placeholder = { Text("Type your memory here... (optional if you attach a file)") },
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
            maxLines = 10,
            enabled = !busy
        )

        // Attachment pickers
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    imagePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                modifier = Modifier.weight(1f),
                enabled = !busy
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Photo")
            }
            OutlinedButton(
                onClick = { filePicker.launch(fileMimeTypes) },
                modifier = Modifier.weight(1f),
                enabled = !busy
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("File")
            }
        }

        Text(
            text = "Up to $maxAttachmentText per attachment (limit from the server). " +
                "Photos larger than that are downscaled to 2048px and re-encoded as JPEG; " +
                "other files (PDF, text) cannot be shrunk and will be refused.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (isPreparing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Preparing attachments...", style = MaterialTheme.typography.bodySmall)
            }
        }

        // Selected attachments: name + human-readable size, each removable
        if (attachments.isNotEmpty()) {
            Text(
                text = "Attachments (${attachments.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            attachments.forEach { attachment ->
                AttachmentRow(
                    attachment = attachment,
                    enabled = !busy,
                    onRemove = { viewModel.removeAttachment(attachment) }
                )
            }
        }

        // 上传成功后在等服务器提取文本（附件是异步处理的）
        tracking?.let { state ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "已保存，正在等服务器提取附件文本…",
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Text(
                        text = "${state.attachmentCount} 个附件。离开本页会停止等待，" +
                            "回到列表后仍可看到状态（也可在详情里手动刷新）。",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    TextButton(onClick = {
                        viewModel.skipTracking()
                    }) { Text("立即返回") }
                }
            }
        }

        // Non-fatal notice (e.g. one of several files was refused)
        notice?.let { message ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(onClick = viewModel::dismissNotice) { Text("Got it") }
                }
            }
        }

        // Error Display
        error?.let { errorMessage ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = errorMessage,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        // Submit Button
        Button(
            onClick = viewModel::submitMemory,
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && tracking == null && (content.isNotBlank() || attachments.isNotEmpty())
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Saving...")
            } else {
                Text("Save Memory")
            }
        }
        
        // Back Button
        OutlinedButton(
            onClick = onNavigateBack,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading || tracking != null
        ) {
            Text("Cancel")
        }
    }
}

@Composable
private fun AttachmentRow(
    attachment: Attachment,
    enabled: Boolean,
    onRemove: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = attachment.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = sizeLabel(attachment),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onRemove, enabled = enabled) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove ${attachment.fileName}"
                )
            }
        }
    }
}

/** "820 KB" / 压缩过的再补一句"已压缩（原 4.2 MB）"。 */
private fun sizeLabel(attachment: Attachment): String {
    val kind = if (attachment.kind == AttachmentKind.IMAGE) "Photo" else "File"
    val size = AttachmentPolicy.formatSize(attachment.sizeBytes)
    return if (attachment.compressed) {
        "$kind · $size · compressed (was ${AttachmentPolicy.formatSize(attachment.originalSizeBytes)})"
    } else {
        "$kind · $size"
    }
}
