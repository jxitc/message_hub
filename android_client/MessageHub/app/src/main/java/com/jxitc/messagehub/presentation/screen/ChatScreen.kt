package com.jxitc.messagehub.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jxitc.messagehub.domain.model.ChatMessage
import com.jxitc.messagehub.domain.model.ChatRating
import com.jxitc.messagehub.domain.model.QaEntity
import com.jxitc.messagehub.domain.model.QaSource
import com.jxitc.messagehub.domain.model.QaStep
import com.jxitc.messagehub.domain.service.ChatFormat
import com.jxitc.messagehub.presentation.viewmodel.ChatViewModel

/** 空状态给的几个例子：点一下就问，省得用户对着输入框不知道能问什么。 */
private val EXAMPLE_QUESTIONS = listOf(
    "我接下来有什么安排？",
    "我那辆车的 MOT 什么时候到期？",
    "最近有哪些账单或者扣款？"
)

/**
 * 问知识库：一个问答式聊天页。
 *
 * 答案下面那行（耗时/tokens/花费）、两个评价按钮、可展开的「过程」，都是为了同一个目的 ——
 * 答错时要能当场看出**是哪一步错了**（改写丢了主语？实体没抽到？召回是空的？），
 * 而不是只能判断"这个答案不对"。这些数据服务器一次就返回了，不用再问第二次。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onNavigateBack: () -> Unit = {}
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val asking by viewModel.asking.collectAsStateWithLifecycle()
    val detailLoading by viewModel.detailLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    // 聊天页里"最新"永远在底部：新消息或开始等待时自动滚到底。
    // 等待中的占位气泡也是列表里的一项，所以索引要把它算进去。
    val itemCount = messages.size + if (asking) 1 else 0
    LaunchedEffect(itemCount) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }

    /** 发送并把输入框清空（标准聊天行为；失败原因由错误卡片显示）。 */
    fun send() {
        val question = input.trim()
        if (question.isEmpty() || asking) return
        input = ""
        viewModel.ask(question)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 与 MemoryListScreen / SettingsScreen 一样用 Column + TopAppBar：
        // 外层 MainActivity 已经有一个 Scaffold 在管系统栏内边距，这里再套一个会叠一层。
        TopAppBar(
            title = { Text("问知识库") },
            navigationIcon = {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
            actions = {
                IconButton(onClick = viewModel::refreshHistory) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新历史")
                }
            }
        )

        error?.let { message ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = message,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            if (messages.isEmpty() && !asking) {
                EmptyChatView(onAsk = { viewModel.ask(it) })
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(messages, key = { it.turnId }) { message ->
                        QuestionBubble(message)
                        Spacer(modifier = Modifier.height(8.dp))
                        AnswerBubble(
                            message = message,
                            detailLoading = detailLoading.contains(message.turnId),
                            onRate = { rating -> viewModel.rate(message.turnId, rating) },
                            onNeedDetail = { viewModel.loadDetail(message.turnId) }
                        )
                    }
                    if (asking) {
                        item { ThinkingBubble() }
                    }
                }
            }
        }

        ChatInputBar(
            value = input,
            onValueChange = { input = it },
            onSend = ::send,
            sending = asking
        )
    }
}

/** 用户问的那句：靠右。宽度按内容，最多占 3/4（`fill = false` 才不会被撑满）。 */
@Composable
private fun QuestionBubble(message: ChatMessage) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.weight(1f))
        Surface(
            modifier = Modifier.weight(3f, fill = false),
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 4.dp, bottomStart = 16.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                SelectionContainer {
                    Text(
                        text = message.question,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                ChatFormat.timestamp(message.createdAt).takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

/** 回答：靠左，带耗时/tokens/花费、评价按钮、可展开的「过程」。 */
@Composable
private fun AnswerBubble(
    message: ChatMessage,
    detailLoading: Boolean,
    onRate: (ChatRating) -> Unit,
    onNeedDetail: () -> Unit
) {
    // 展开状态跟着 turnId 走：列表滚动/刷新后不该丢，也不该串到别的回答上。
    var expanded by rememberSaveable(message.turnId) { mutableStateOf(false) }

    Row(modifier = Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.weight(4f, fill = false),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 4.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                SelectionContainer {
                    Text(
                        text = message.answer,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 3.0s · 10432+259 tokens · ¥0.0229
                ChatFormat.meta(message).takeIf { it.isNotBlank() }?.let { meta ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                RatingRow(
                    rating = message.rating,
                    expanded = expanded,
                    onRate = onRate,
                    onToggleProcess = {
                        expanded = !expanded
                        // 历史接口不带 steps/sources，第一次展开时才去服务器补
                        if (expanded) onNeedDetail()
                    }
                )

                if (expanded) {
                    Spacer(modifier = Modifier.height(8.dp))
                    ProcessPanel(message = message, loading = detailLoading)
                }
            }
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}

/**
 * 👍 / 👎 + 「过程」。
 *
 * 再点一次同一个按钮＝取消评价（服务器认空 rating），所以只有两个按钮，
 * 界面上不需要第三个"取消"入口。已评价的那个高亮。
 */
@Composable
private fun RatingRow(
    rating: ChatRating?,
    expanded: Boolean,
    onRate: (ChatRating) -> Unit,
    onToggleProcess: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RatingButton(emoji = "👍", label = "有用", selected = rating == ChatRating.GOOD) {
            onRate(ChatRating.GOOD)
        }
        RatingButton(emoji = "👎", label = "没用", selected = rating == ChatRating.BAD) {
            onRate(ChatRating.BAD)
        }
        TextButton(onClick = onToggleProcess, modifier = Modifier.weight(1f)) {
            Text(if (expanded) "收起过程" else "过程", style = MaterialTheme.typography.labelMedium)
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun RatingButton(
    emoji: String,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .clip(CircleShape)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
            )
            // 按钮里只有一个 emoji，读屏软件读出来是"举手"，所以补上人话；
            // 已评价也一并说出来（高亮只是视觉上的）
            .semantics {
                contentDescription = if (selected) "$label（已标记）" else label
            },
        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
    ) {
        Text(text = emoji, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * 「过程」面板：改写 → 实体 → 检索词 → 召回（含每条原文**为什么**被召回）→ 每步耗时/tokens/花费。
 *
 * 顺序就是 pipeline 的顺序：从上往下读，就能看出是哪一步把答案带偏了。
 */
@Composable
private fun ProcessPanel(message: ChatMessage, loading: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (loading && message.needsDetail) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("正在拉取过程…", style = MaterialTheme.typography.labelSmall)
                }
            }

            message.rewritten?.let { ProcessField("改写后的问句", it) }
            if (message.entities.isNotEmpty()) {
                ProcessField("抽出的实体", message.entities.joinToString("、") { entityLabel(it) })
            }
            if (message.keywords.isNotEmpty()) {
                ProcessField("检索词", message.keywords.joinToString("、"))
            }
            recallSummary(message)?.let { ProcessField("召回统计", it) }
            StepList(message.steps)
            SourceList(message.sources, message.cited)

            if (message.needsDetail && !loading) {
                Text(
                    text = "这条记录的过程没能取到（可能离线，或服务器上已删除）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** `候选 96 → 采用 40；命中实体 5 个、文本词 13 个`。 */
private fun recallSummary(message: ChatMessage): String? {
    val counts = message.steps.firstOrNull { it.name == "recall" }?.counts ?: return null
    return "候选 ${counts.candidates} → 采用 ${counts.used}；" +
        "命中实体 ${counts.entitiesHit} 个、文本词 ${counts.textTerms} 个"
}

private fun entityLabel(entity: QaEntity): String {
    val kind = entity.kind?.takeIf { it.isNotBlank() } ?: return entity.name
    return "${entity.name}（$kind）"
}

@Composable
private fun ProcessField(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun StepList(steps: List<QaStep>) {
    if (steps.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "每一步",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary
        )
        steps.forEach { step ->
            val parts = mutableListOf<String>()
            step.elapsedMs?.let { parts += ChatFormat.duration(it) }
            if (step.tokensPrompt != null || step.tokensCompletion != null) {
                parts += "${step.tokensPrompt ?: 0}+${step.tokensCompletion ?: 0} tokens"
            }
            step.cost?.let { parts += ChatFormat.money(it) }
            step.model?.let { parts += it }
            Text(
                text = "${stepName(step.name)}${if (parts.isEmpty()) "" else " · " + parts.joinToString(" · ")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            // extract 这一步的说明（"与 rewrite 同一次调用返回"）——不然 0ms 看着像出错
            step.note?.let {
                Text(
                    text = "    $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun stepName(name: String): String = when (name) {
    "rewrite" -> "改写"
    "extract" -> "抽实体"
    "recall" -> "召回"
    "answer" -> "生成"
    else -> name
}

/**
 * 被召回的原文 + **为什么被召回**。
 *
 * why 是这一块唯一不可省的东西：只给原文，用户看不出"为什么是这些"，
 * 也就无从判断召回是不是错了（而这正是 👎 之后要查的）。
 */
@Composable
private fun SourceList(sources: List<QaSource>, cited: List<Int>) {
    if (sources.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "被召回的原文（${sources.size} 条，[编号] 就是答案里的出处）",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary
        )
        sources.forEachIndexed { index, source ->
            val number = index + 1
            val used = cited.contains(number)
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "[$number]",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = ChatFormat.sourceLabel(source.type, source.sender),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    source.timestamp?.let {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = ChatFormat.timestamp(it),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (used) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "已引用",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                if (source.why.isNotEmpty()) {
                    Text(
                        text = "为什么：" + source.why.joinToString("；"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = source.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/** 提问中的占位：说明白"要等一会儿"，以及这几秒在做哪四件事。 */
@Composable
private fun ThinkingBubble() {
    Row(modifier = Modifier.fillMaxWidth()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 4.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "正在查库：改写 → 抽实体 → 召回 → 生成",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun EmptyChatView(onAsk: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "问知识库",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "答案只来自你自己记录过的消息，每条都会标出处。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(20.dp))
        EXAMPLE_QUESTIONS.forEach { question ->
            SuggestionChip(
                onClick = { onAsk(question) },
                label = { Text(question, style = MaterialTheme.typography.bodyMedium) },
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    sending: Boolean
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("问点什么…", style = MaterialTheme.typography.bodyMedium) },
                maxLines = 4,
                enabled = !sending
            )
            Spacer(modifier = Modifier.width(8.dp))
            FilledIconButton(
                onClick = onSend,
                enabled = !sending && value.isNotBlank()
            ) {
                if (sending) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                }
            }
        }
    }
}
