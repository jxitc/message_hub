package com.jxitc.messagehub.presentation.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.provider.Telephony
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
    onBlockApp: (String) -> Unit = {}
) {
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

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
                            onBlockApp = onBlockApp
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

    // 详情对话框
    selectedMemory?.let { memory ->
        MemoryDetailDialog(memory = memory, onDismiss = { selectedMemory = null })
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
    onBlockApp: (String) -> Unit = {}
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
                        onDismiss = { headerMenuOpen = false },
                        onBlockApp = onBlockApp
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
                            }
                            BlockAppDropdownMenu(
                                expanded = rowMenuOpen,
                                packageName = rowPkg,
                                onDismiss = { rowMenuOpen = false },
                                onBlockApp = onBlockApp
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
    onDismiss: () -> Unit,
    onBlockApp: (String) -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (!packageName.isNullOrBlank()) {
            DropdownMenuItem(
                text = { Text("禁止此 app 的通知") },
                onClick = {
                    onDismiss()
                    onBlockApp(packageName)
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

/** 记忆详情对话框: 完整内容 + 来源/时间, 不截断 */
@Composable
private fun MemoryDetailDialog(memory: Memory, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        title = {
            Column {
                Text(cleanTitle(memory.title), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${sourceLabel(memory)} · ${memory.createdAt.format(DateTimeFormatter.ofPattern("MMM dd HH:mm"))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = memory.content,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    )
}
