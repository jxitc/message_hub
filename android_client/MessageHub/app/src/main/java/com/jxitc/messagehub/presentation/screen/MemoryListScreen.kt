package com.jxitc.messagehub.presentation.screen

import com.jxitc.messagehub.domain.service.AttachmentPreviewRules
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.provider.Telephony
import android.widget.Toast
import com.jxitc.messagehub.domain.model.Memory
import com.jxitc.messagehub.domain.model.SourceType
import com.jxitc.messagehub.presentation.viewmodel.MemoryListViewModel
import java.time.format.DateTimeFormatter

/**
 * 主页列表：按"来源"分组聚合展示。
 * 1. 同来源(app) 且「严格相邻连续」的多个 memory 合并成一个条目(按时间倒序, 只与上一组比较),
 *    避免几百条同质通知(如 QQ音乐)淹没; A A B A A → [A×2][B×1][A×2]。
 * 2. 每个分组显示来源的真实 app 图标 + 名称 + 数量 + 时间范围，可展开看全部。
 * 3. 全量聚合后渐进式展示: 默认前 10 组, 底部「载入更多」每次 +10。
 * 4. 长按条目 → 菜单「禁止此 app 的通知」(只拦新通知, 旧记录保留)。
 * 5. 搜索时不聚合, 逐条精确展示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryListScreen(
    viewModel: MemoryListViewModel,
    onNavigateToAddMemory: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    blockedApps: Set<String> = emptySet(),
    onToggleBlock: (String) -> Unit = {},
    /** 附件下载地址一律用 key + serverUrl 拼（见 AttachmentUrls），所以在页面里传入。 */
    blobUrlFor: (String) -> String = { "" },
    onOpenOriginal: (com.jxitc.messagehub.domain.model.ServerAttachment) -> Unit = {}
) {
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    // 页面可见性驱动附件状态轮询：可见才轮询，离开（进"添加记忆"/切后台）立刻停。
    // 用 LifecycleResumeEffect 而不是 DisposableEffect：切到别的 app 时 composable 还在，
    // 但"不可见"必须包含这种情形。
    LifecycleResumeEffect(Unit) {
        viewModel.onScreenVisible()
        onPauseOrDispose { viewModel.onScreenHidden() }
    }

    val isSearchActive = searchQuery.isNotBlank()

    // 分组: 主列表严格相邻聚合; 搜索精确匹配逐条展示(不聚合)
    val groups = remember(memories, isSearchActive) {
        if (isSearchActive) memories.map(::singleMemoryGroup) else groupMemories(memories)
    }
    // 渐进式展示: 默认 10 组, 载入更多每次 +10 (搜索路径不渐进)
    var visibleCount by remember { mutableStateOf(10) }
    val visibleGroups = if (isSearchActive) groups else groups.take(visibleCount)
    var selectedMemory by remember { mutableStateOf<Memory?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top App Bar
        TopAppBar(
            title = { Text("My Memories") },
            actions = {
                IconButton(onClick = viewModel::refreshMemories) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                }
                IconButton(onClick = onNavigateToSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
                IconButton(onClick = onNavigateToAddMemory) {
                    Icon(Icons.Default.Add, contentDescription = "Add Memory")
                }
            }
        )

        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = viewModel::searchMemories,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Search memories...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
            trailingIcon = {
                if (searchQuery.isNotBlank()) {
                    IconButton(onClick = viewModel::clearSearch) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                    }
                }
            },
            singleLine = true
        )

        // Error
        error?.let { errorMessage ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Text(
                    text = errorMessage,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // 这里**故意不放**全局的"正在提取"横幅：等待与结果都属于某一条消息，
        // 挂在列表顶部既说不清是哪条、又会一直占位。状态显示在那一行消息上
        // （见 MemoryGroupCard 里的 attachmentsSummary），文本只在详情里看。

        // Content
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                groups.isEmpty() -> EmptyMemoriesView(onAddMemory = onNavigateToAddMemory, modifier = Modifier.align(Alignment.Center))
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 不指定 key: 同来源但不相邻会分成多个组, 若用 sourceKey 作 key 会因重复 key 崩溃
                    items(visibleGroups) { group ->
                        MemoryGroupCard(
                            group = group,
                            onItemClick = { selectedMemory = it },
                            blockedApps = blockedApps,
                            onToggleBlock = onToggleBlock
                        )
                    }
                    // 渐进式展示: 还有更多聚合组时显示「载入更多」
                    if (!isSearchActive && visibleCount < groups.size) {
                        item {
                            Button(
                                onClick = { visibleCount += 10 },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("载入更多 (剩余 ${groups.size - visibleCount} 组)")
                            }
                        }
                    }
                }
            }
        }
    }

    // 详情对话框。用内存里的最新一份（Room 更新后列表会刷新，selectedMemory 是快照），
    // 这样附件状态轮询的结果会实时反映在打开着的详情里。
    selectedMemory?.let { snapshot ->
        val memory = memories.firstOrNull { it.id == snapshot.id } ?: snapshot
        MemoryDetailDialog(
            memory = memory,
            onDismiss = { selectedMemory = null },
            blobUrlFor = blobUrlFor,
            onOpenOriginal = onOpenOriginal,
            onRefreshAttachments = { viewModel.refreshAttachmentStatus(memory.serverMessageId) }
        )
    }
}

/** 来源分组: 同来源(app) 且严格相邻连续的 memory 合并 */
private data class MemoryGroup(
    val key: String,
    val sourceLabel: String,
    val packageName: String?,
    val memories: List<Memory>
) {
    val count: Int get() = memories.size
    val latest: Memory get() = memories.first()
    val timeRange: String
        get() {
            val first = memories.last().createdAt
            val last = memories.first().createdAt
            val fmt = DateTimeFormatter.ofPattern("MMM dd HH:mm")
            val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
            return if (first.toLocalDate() == last.toLocalDate() && first == last) {
                first.format(fmt)
            } else {
                "${first.format(fmt)} - ${last.format(timeFmt)}"
            }
        }
}

private fun sourceLabel(m: Memory): String = when (m.sourceType) {
    SourceType.NOTIFICATION -> m.metadata["app_name"]?.takeIf { it.isNotBlank() }
        ?: m.metadata["package_name"]?.takeIf { it.isNotBlank() } ?: "通知"
    SourceType.SMS -> "短信"
    SourceType.MANUAL -> "手动"
    SourceType.SCREENSHOT -> "截图"
    SourceType.SHARE_INTENT -> "分享"
}

private fun sourceKey(m: Memory): String {
    val pkg = m.metadata["package_name"] ?: m.metadata["app_id"]
    return "${m.sourceType.name}|${pkg ?: sourceLabel(m)}"
}

/**
 * 严格相邻连续聚合: 按时间倒序遍历, 只与「上一组」比较 —— 同 key 归入该组, 不同则打断开新组。
 * A A B A A (时间倒序) → [A×2] [B×1] [A×2] (两个 A 组绝不跨 B 合并)。
 */
private fun groupMemories(memories: List<Memory>): List<MemoryGroup> {
    val sorted = memories.sortedByDescending { it.createdAt }
    val groups = mutableListOf<MemoryGroup>()
    for (m in sorted) {
        val key = sourceKey(m)
        val pkg = m.metadata["package_name"]?.takeIf { it.isNotBlank() } ?: m.metadata["app_id"]
        val last = groups.lastOrNull()
        if (last != null && last.key == key) {
            groups[groups.size - 1] = last.copy(memories = last.memories + m)
        } else {
            groups.add(MemoryGroup(key, sourceLabel(m), pkg, listOf(m)))
        }
    }
    return groups.toList()
}

/** 搜索路径: 每条结果独立成组(精确匹配, 不聚合) */
private fun singleMemoryGroup(m: Memory): MemoryGroup {
    val pkg = m.metadata["package_name"]?.takeIf { it.isNotBlank() } ?: m.metadata["app_id"]
    return MemoryGroup(sourceKey(m), sourceLabel(m), pkg, listOf(m))
}

/** 取 memory 中可用的包名(package_name 优先, 回退 app_id); 没有返回 null */
private fun memoryPackageName(m: Memory): String? =
    m.metadata["package_name"]?.takeIf { it.isNotBlank() } ?: m.metadata["app_id"]

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MemoryGroupCard(
    group: MemoryGroup,
    onItemClick: (Memory) -> Unit = {},
    blockedApps: Set<String> = emptySet(),
    onToggleBlock: (String) -> Unit = {}
) {
    var expanded by remember(group.key) { mutableStateOf(false) }
    // 分组头部长按屏蔽菜单状态(包名取自分组首条 metadata)
    var headerMenuOpen by remember(group.key) { mutableStateOf(false) }
    val context = LocalContext.current
    // 短信用系统默认短信 app 的图标
    val smsPkg = remember {
        if (group.sourceLabel == "短信") {
            try { Telephony.Sms.getDefaultSmsPackage(context) } catch (e: Exception) { null }
        } else null
    }
    val groupPkg = group.packageName?.takeIf { it.isNotBlank() }

    Box(modifier = Modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Header: icon + source + count + expand hint (长按可屏蔽该 app)
                Box(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { expanded = !expanded },
                                onLongClick = { if (groupPkg != null) headerMenuOpen = true }
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppIcon(packageName = group.packageName ?: smsPkg, size = 36.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${group.sourceLabel}  ×${group.count}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = group.timeRange,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = if (expanded) "收起" else "展开",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    BlockAppDropdownMenu(
                        expanded = headerMenuOpen,
                        packageName = groupPkg,
                        isBlocked = groupPkg != null && blockedApps.contains(groupPkg),
                        onDismiss = { headerMenuOpen = false },
                        onToggleBlock = onToggleBlock
                    )
                }

                if (expanded) {
                    Spacer(modifier = Modifier.height(8.dp))
                    group.memories.forEach { m ->
                        Divider(modifier = Modifier.padding(vertical = 4.dp))
                        // 条目: 点击查看详情; 长按弹出菜单可屏蔽该条目的 app
                        var rowMenuOpen by remember(m.id) { mutableStateOf(false) }
                        val rowPkg = memoryPackageName(m)
                        Box(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = { onItemClick(m) },
                                        onLongClick = { if (rowPkg != null) rowMenuOpen = true }
                                    )
                            ) {
                                Text(
                                    text = cleanTitle(m.title),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = m.createdAt.format(DateTimeFormatter.ofPattern("HH:mm")) + "  点击查看详情",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                // 附件状态挂在**这一条消息**上（列表顶部不放全局横幅）
                                AttachmentPreviewRules.attachmentsSummary(
                                    m.attachments, m.skippedAttachments
                                )?.let { summary ->
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "\uD83D\uDCCE $summary",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            BlockAppDropdownMenu(
                                expanded = rowMenuOpen,
                                packageName = rowPkg,
                                isBlocked = rowPkg != null && blockedApps.contains(rowPkg),
                                onDismiss = { rowMenuOpen = false },
                                onToggleBlock = onToggleBlock
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 长按条目弹出的菜单; 目前唯一选项是「禁止此 app 的通知」。
 * 拿不到 packageName 时不显示该选项(expanded 保持 false, 不弹菜单)。
 */
@Composable
private fun BlockAppDropdownMenu(
    expanded: Boolean,
    packageName: String?,
    isBlocked: Boolean = false,
    onDismiss: () -> Unit,
    onToggleBlock: (String) -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (!packageName.isNullOrBlank()) {
            DropdownMenuItem(
                text = { Text(if (isBlocked) "取消屏蔽此 app 的通知" else "禁止此 app 的通知") },
                onClick = {
                    onDismiss()
                    onToggleBlock(packageName)
                }
            )
        }
    }
}

/** 标题干净化：content 已是干净正文（无 emoji/类型前缀），仅做 trim。 */
private fun cleanTitle(title: String): String {
    return title.trim()
}

/** 用来源 app 的真实图标; 拿不到则用默认通知图标 */
@Composable
private fun AppIcon(packageName: String?, size: androidx.compose.ui.unit.Dp) {
    val context = LocalContext.current
    val pkg = packageName?.takeIf { it.isNotBlank() }
    val bitmap = remember(pkg) {
        if (pkg == null) null else try {
            context.packageManager.getApplicationIcon(pkg).toBitmap().asImageBitmap()
        } catch (e: Exception) { null }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = pkg,
            modifier = Modifier.size(size).clip(CircleShape)
        )
    } else {
        Icon(
            imageVector = Icons.Default.Notifications,
            contentDescription = "notification",
            modifier = Modifier.size(size),
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun EmptyMemoriesView(onAddMemory: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("No memories yet", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Start by adding your first memory", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onAddMemory) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Add Memory")
        }
    }
}

/**
 * 记忆详情：完整内容（不截断、可复制）+ 来源/时间 + **附件列表与提取状态**。
 *
 * 附件区的规则（缩略图、状态徽标、提取文本落位、未保存附件）都在 [AttachmentSection] 里，
 * 这里只把它们摆进对话框。
 */
@Composable
private fun MemoryDetailDialog(
    memory: Memory,
    onDismiss: () -> Unit,
    blobUrlFor: (String) -> String = { "" },
    onOpenOriginal: (com.jxitc.messagehub.domain.model.ServerAttachment) -> Unit = {},
    onRefreshAttachments: () -> Unit = {}
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val syncedAt = memory.attachmentsSyncedAt
    val hasAttachments = memory.attachments.isNotEmpty() || memory.skippedAttachments.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(memory.content))
                    Toast.makeText(context, "已复制正文", Toast.LENGTH_SHORT).show()
                }) { Text("复制正文") }
                if (memory.serverMessageId != null) {
                    TextButton(onClick = onRefreshAttachments) { Text("刷新附件") }
                }
            }
        },
        title = {
            Column {
                Text(cleanTitle(memory.title), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${sourceLabel(memory)} · ${memory.createdAt.format(DateTimeFormatter.ofPattern("MMM dd HH:mm"))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (hasAttachments && syncedAt != null) {
                    Text(
                        text = "附件状态更新于 ${syncedAt.format(DateTimeFormatter.ofPattern("HH:mm:ss"))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SelectionContainer {
                    Text(
                        text = memory.content,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                AttachmentSection(
                    attachments = memory.attachments,
                    skipped = memory.skippedAttachments,
                    messageContent = memory.content,
                    blobUrlFor = blobUrlFor,
                    onOpenOriginal = onOpenOriginal
                )
            }
        }
    )
}
