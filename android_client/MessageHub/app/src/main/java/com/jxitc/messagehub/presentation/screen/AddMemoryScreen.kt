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

    val busy = isLoading || isPreparing

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

    // Handle successful submission
    LaunchedEffect(isSubmitted) {
        if (isSubmitted) {
            viewModel.resetSubmissionState()
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
            enabled = !busy && (content.isNotBlank() || attachments.isNotEmpty())
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
            enabled = !isLoading
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
