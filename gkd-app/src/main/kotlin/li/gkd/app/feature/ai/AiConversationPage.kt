package li.gkd.app.feature.ai

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import li.gkd.app.MainActivity
import li.gkd.app.MainViewModel
import li.gkd.app.store.AppStore
import li.gkd.app.ui.component.GkAlertDialog
import li.gkd.app.ui.component.GkAppIcon
import li.gkd.app.ui.component.GkAppNameText
import li.gkd.app.ui.component.GkIcon
import li.gkd.app.ui.component.GkIconButton
import li.gkd.app.ui.component.GkIcons
import li.gkd.app.ui.component.GkPageBottomSpace
import li.gkd.app.ui.component.GkTopAppBar
import li.gkd.app.util.TimeUtils.throttle
import li.gkd.app.util.LogUtils
import li.gkd.app.util.ToastUtils
import li.gkd.app.util.ToastUtils.toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
data class AiConversationRoute(val snapshotId: Long) : NavKey

@Composable
fun AiConversationPage(route: AiConversationRoute) {
    val mainVm = MainViewModel.requireCurrent()
    val vm = viewModel<AiConversationVm>()
    val conversation by AiConversationStore.conversationsFlow.collectAsStateWithLifecycle()
    val current = conversation.find { it.snapshotId == route.snapshotId }
    var input by remember { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmShare by remember { mutableStateOf(false) }
    var sharedFile by remember { mutableStateOf<File?>(null) }
    val shareScope = rememberCoroutineScope()
    val activity = LocalActivity.current as? MainActivity
    val listState = rememberLazyListState()
    // 进程被杀后会话会停在 generating，进来先尝试恢复
    LaunchedEffect(route.snapshotId) { vm.recoverInterruptedGeneration(route.snapshotId) }
    // 选了多个组件时提示可以用序号指代；提醒两次后不再打扰
    LaunchedEffect(route.snapshotId) { vm.notifyMultiSelectHint(route.snapshotId) }
    // 流式输出时自动跟随到底部；**一开始滚动就停止跟随**——等到滚动结束再判断就晚了，
    // 拖动期间标记还是 true，每个增量都会把列表拽回底部，松手后位置已在底部、
    // canScrollForward 变回 false，标记又恢复，用户永远甩不掉
    var followTail by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                followTail = if (scrolling) false else !listState.canScrollForward
            }
    }
    LaunchedEffect(
        current?.messages?.size,
        current?.messages?.lastOrNull()?.content?.length,
        current?.messages?.lastOrNull()?.reasoning?.length,
    ) {
        val messageCount = current?.messages?.size ?: 0
        if (messageCount > 0 && followTail) {
            // 先定位到最后一条，再往下顶到底：只定位到最后一条会把它的开头（思考内容）
            // 摆在屏幕顶部，最新输出反而在屏幕外
            listState.scrollToItem(messageCount - 1)
            listState.scrollBy(TAIL_SCROLL_DISTANCE)
        }
    }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GkTopAppBar(
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    GkIconButton(
                        imageVector = GkIcons.ArrowBack,
                        onClick = mainVm::popPage,
                    )
                },
                title = {
                    val appId = current?.appId
                    if (appId == null) {
                        Text(text = "AI 对话")
                    } else {
                        Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            GkAppIcon(appId = appId, size = 24.dp)
                            GkAppNameText(
                                appId = appId,
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                },
                actions = {
                    // 没有对话内容时不给入口，避免一个点了没反应的菜单项
                    if (current?.messages?.isNotEmpty() == true) {
                        GkIconButton(
                            imageVector = GkIcons.MoreVert,
                            contentDescription = "更多",
                            onClick = { menuExpanded = true },
                        )
                        Box(modifier = Modifier.wrapContentSize(Alignment.TopStart)) {
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(text = "清空对话") },
                                    onClick = {
                                        menuExpanded = false
                                        confirmClear = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(text = "转发分享当前对话") },
                                    onClick = {
                                        menuExpanded = false
                                        confirmShare = true
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { contentPadding ->
        // 不用 Scaffold.bottomBar：它与 ime inset 叠加导致的空隙屡修不好。
        // 改用 App 内已验证的模式：输入栏作为内容末尾元素，仅由 imePadding 让位。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .consumeWindowInsets(contentPadding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                state = listState,
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = 8.dp,
                    bottom = 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
            if (current == null) {
                item(key = "empty") {
                    Column {
                        Text(
                            text = "会话不存在，可能已被删除",
                            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        GkPageBottomSpace()
                    }
                }
            } else {
                itemsIndexed(current.messages) { index, message ->
                    MessageItem(
                        message = message,
                        isStreaming = current.status == AiConversation.STATUS_GENERATING &&
                            index == current.messages.lastIndex,
                    )
                }
                if (current.status == AiConversation.STATUS_GENERATING) {
                    item(key = "generating") {
                        Text(
                            text = "AI 正在回复...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (current.status == AiConversation.STATUS_RULE_READY) {
                    item(key = "accept-rule") {
                        androidx.compose.material3.FilledTonalButton(
                            onClick = throttle { vm.acceptRule(route.snapshotId) },
                        ) {
                            Text(text = "加入规则")
                        }
                    }
                }
                item(key = "bottom-space") { GkPageBottomSpace() }
            }
            }
            // 已选组件：竖直排列、整体水平居中、无背景，悬浮于输入栏上方
            if (!current?.selectedComponents.isNullOrEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                ) {
                    current!!.selectedComponents.forEachIndexed { index, comp ->
                        Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                // 序号要和上下文里给模型的一致，用户才能用「组件 N」指代
                                text = "组件 ${index + 1}：${comp.displayText()}",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            GkIconButton(
                                imageVector = GkIcons.Close,
                                contentDescription = "移除",
                                modifier = Modifier.size(24.dp),
                                onClick = { vm.removeSelectedComponent(route.snapshotId, index) },
                            )
                        }
                    }
                }
            }
            // 输入栏：仅 imePadding 让位（App 内已验证模式），键盘弹出时紧贴键盘
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .imePadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 加号：跳转该快照的组件选择页
                GkIconButton(
                    imageVector = GkIcons.Add,
                    contentDescription = "选择快照组件",
                    onClick = {
                        mainVm.navigatePage(
                            AiSnapshotPickerRoute(route.snapshotId, fromConversation = true),
                        )
                    },
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text(text = "输入你的要求") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp),
                    minLines = 1,
                    maxLines = 4,
                )
                TextButton(
                    onClick = throttle {
                        val text = input.trim()
                        if (text.isNotEmpty() && current != null) {
                            input = ""
                            vm.sendUserMessage(route.snapshotId, text)
                        }
                    },
                    enabled = input.isNotBlank() && current != null &&
                        current.status != AiConversation.STATUS_GENERATING,
                ) {
                    Text(text = "发送")
                }
                // 生成中把发送换成停止：模型跑偏或太慢时用户得能中断，否则只能等或退出
                if (current?.status == AiConversation.STATUS_GENERATING) {
                    TextButton(
                        onClick = throttle { vm.stopGeneration(route.snapshotId) },
                    ) {
                        Text(text = "停止")
                    }
                }
            }
        }
    }
    if (confirmClear) {
        GkAlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(text = "清空对话") },
            text = { Text(text = "清空这段对话？快照和已选中的组件会保留。") },
            confirmButton = {
                TextButton(
                    onClick = throttle {
                        confirmClear = false
                        vm.clearConversation(route.snapshotId)
                    },
                ) {
                    Text(text = "清空")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(text = "取消")
                }
            },
        )
    }
    if (confirmShare) {
        GkAlertDialog(
            onDismissRequest = { confirmShare = false },
            title = { Text(text = "转发分享当前对话") },
            text = {
                Text(text = "您的对话过程以及快照会被包含在转发的内容之内，请您确认隐私安全。")
            },
            confirmButton = {
                TextButton(onClick = throttle {
                    confirmShare = false
                    val conversation = current ?: return@throttle
                    val target = activity ?: return@throttle
                    shareScope.launch {
                        val file = runCatching {
                            AiConversationExport.build(
                                conversation,
                                AppStore.storeFlow.value.aiConfig,
                            )
                        }.getOrElse { e ->
                            LogUtils.d("AI conversation export failed", e)
                            toast("导出失败：${e.message ?: e.javaClass.simpleName}")
                            return@launch
                        }
                        target.saveFileToDownloads(file)
                        sharedFile = file
                    }
                }) {
                    Text(text = "确认")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmShare = false }) {
                    Text(text = "取消")
                }
            },
        )
    }
    sharedFile?.let { file ->
        GkAlertDialog(
            onDismissRequest = { sharedFile = null },
            title = { Text(text = "已生成反馈文件") },
            text = {
                Text(
                    text = "文件已保存到下载目录。可以把它发送到 $FEEDBACK_EMAIL，" +
                        "或提交到 GitHub 的 issue / discussion。"
                )
            },
            confirmButton = {
                TextButton(onClick = throttle {
                    sharedFile = null
                    activity?.shareFile(file, "分享对话记录")
                }) {
                    Text(text = "分享")
                }
            },
            dismissButton = {
                TextButton(onClick = throttle {
                    ToastUtils.copyTextSilently(FEEDBACK_EMAIL)
                    toast("邮箱已复制")
                }) {
                    Text(text = "复制邮箱")
                }
                TextButton(onClick = { sharedFile = null }) {
                    Text(text = "关闭")
                }
            },
        )
    }
}

/** 反馈邮箱：与 README、关于页保持一致 */
private const val FEEDBACK_EMAIL = "3329221986@qq.com"

private val messageTimeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

/** 跟随末尾时再往下顶的距离：给一个远超任何消息高度的值，让滚动被夹到列表底部 */
private const val TAIL_SCROLL_DISTANCE = 100_000f

@Composable
private fun MessageItem(message: AiMessage, isStreaming: Boolean) {
    val isUser = message.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .background(
                    color = if (isUser) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    shape = MaterialTheme.shapes.medium,
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            // 长按可选中复制；时间戳放在外面，避免复制时把时间一起带上
            SelectionContainer {
                Column {
                    if (message.reasoning.isNotBlank()) {
                        // 生成中展开，方便看到模型在干什么；结束后自动收起，仍可手动展开
                        var expanded by rememberSaveable(message.time) { mutableStateOf(isStreaming) }
                        LaunchedEffect(isStreaming) {
                            if (!isStreaming) expanded = false
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .clickable { expanded = !expanded }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            GkIcon(
                                imageVector = if (expanded) GkIcons.ExpandLess else GkIcons.ExpandMore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = if (isStreaming) "思考中…" else "思考过程",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (expanded) {
                            Text(
                                text = message.reasoning,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                            )
                        }
                    }
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isUser) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
            Text(
                text = messageTimeFormat.format(Date(message.time)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
