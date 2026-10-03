package li.gkd.app.feature.ai

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import li.gkd.app.MainViewModel
import li.gkd.app.data.settings.AiConfig
import li.gkd.app.feature.settings.AiSettingsDialog
import li.gkd.app.feature.snapshot.SnapshotPageRoute
import li.gkd.app.store.AppStore.storeFlow
import li.gkd.app.text.UiStrings
import li.gkd.app.ui.component.GkEmptyState
import li.gkd.app.ui.component.GkIconButton
import li.gkd.app.ui.component.GkIcons
import li.gkd.app.ui.component.GkPageBottomSpace
import li.gkd.app.ui.component.GkSettingItem
import li.gkd.app.ui.component.GkTextSwitch
import li.gkd.app.ui.component.GkTopAppBar
import li.gkd.app.ui.style.itemHorizontalPadding
import li.gkd.app.ui.style.surfaceCardColors
import li.gkd.app.util.TimeUtils.throttle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
data object AiWorkbenchRoute : NavKey

@Composable
fun AiWorkbenchPage() {
    val mainVm = MainViewModel.requireCurrent()
    val vm = viewModel<AiWorkbenchVm>()
    val store by storeFlow.collectAsStateWithLifecycle()
    val conversations by AiConversationStore.conversationsFlow.collectAsStateWithLifecycle()
    var showAiConfigDialog by remember { mutableStateOf(false) }
    var deleteRequest by remember { mutableStateOf<AiConversation?>(null) }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    if (showAiConfigDialog) {
        AiSettingsDialog(
            aiConfig = store.aiConfig,
            onDismissRequest = { showAiConfigDialog = false },
        )
    }
    deleteRequest?.let { conversation ->
        li.gkd.app.ui.component.GkAlertDialog(
            onDismissRequest = { deleteRequest = null },
            title = { Text(text = "删除会话") },
            text = { Text(text = "删除「${conversation.appId.substringAfterLast('.')}」的 AI 会话记录？") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = throttle {
                    val target = conversation
                    deleteRequest = null
                    vm.deleteConversation(target.snapshotId)
                }) {
                    Text(text = UiStrings.action_delete)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { deleteRequest = null }) {
                    Text(text = UiStrings.action_cancel)
                }
            },
        )
    }
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
                title = { Text(text = "AI 规则工作台") },
                actions = {
                    // 加号：跳转快照记录，从快照发起 AI 规则生成
                    GkIconButton(
                        imageVector = GkIcons.Add,
                        contentDescription = "打开快照记录",
                        onClick = { mainVm.navigatePage(SnapshotPageRoute) },
                    )
                },
            )
        },
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(contentPadding),
            contentPadding = PaddingValues(
                start = itemHorizontalPadding,
                end = itemHorizontalPadding,
                top = 8.dp,
                bottom = 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "ai-settings") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GkTextSwitch(
                        title = "启用 AI 规则",
                        subtitle = "捕获快照后自动调用大模型生成订阅规则",
                        checked = store.aiEnable,
                        onCheckedChange = vm::setAiEnable,
                    )
                    GkSettingItem(
                        title = "AI 规则设置",
                        subtitle = "配置大模型协议、API 地址、模型等参数",
                        imageVector = GkIcons.Edit,
                        onClick = { showAiConfigDialog = true },
                    )
                }
            }
            if (conversations.isEmpty()) {
                item(key = "empty") {
                    Column {
                        GkEmptyState(text = "暂无 AI 会话，从快照记录右上角菜单发起生成")
                        GkPageBottomSpace()
                    }
                }
            } else {
                items(conversations, key = { it.snapshotId }) { conversation ->
                    ConversationCard(
                        conversation = conversation,
                        onClick = throttle {
                            mainVm.navigatePage(AiConversationRoute(conversation.snapshotId))
                        },
                        onLongClick = { deleteRequest = conversation },
                    )
                }
                item(key = "bottom-space") { GkPageBottomSpace() }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationCard(
    conversation: AiConversation,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    androidx.compose.material3.Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onClickLabel = "打开会话",
                onLongClickLabel = "删除会话",
            ),
        shape = MaterialTheme.shapes.large,
        colors = surfaceCardColors,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = conversation.appId.substringAfterLast('.'),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = conversationTimeFormat.format(Date(conversation.updateTime)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val statusText = when (conversation.status) {
                AiConversation.STATUS_GENERATING -> "生成中..."
                AiConversation.STATUS_SUCCESS -> "已生成规则"
                AiConversation.STATUS_FAILED -> "生成失败"
                else -> if (conversation.messages.isEmpty()) "待发起" else "对话中"
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (conversation.status == AiConversation.STATUS_FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            conversation.messages.lastOrNull()?.let { last ->
                Text(
                    text = last.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val conversationTimeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
