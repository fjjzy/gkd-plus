package li.gkd.app.feature.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import li.gkd.app.MainViewModel
import li.gkd.app.ui.component.GkEmptyState
import li.gkd.app.ui.component.GkIconButton
import li.gkd.app.ui.component.GkIcons
import li.gkd.app.ui.component.GkPageBottomSpace
import li.gkd.app.ui.component.GkTopAppBar
import li.gkd.app.ui.style.itemHorizontalPadding
import li.gkd.app.util.TimeUtils.throttle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
data object AiWorkbenchRoute : NavKey

@Composable
fun AiWorkbenchPage() {
    val mainVm = MainViewModel.requireCurrent()
    val conversations by AiConversationStore.conversationsFlow.collectAsStateWithLifecycle()
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
                title = { Text(text = "AI 规则工作台") },
            )
        },
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(contentPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = itemHorizontalPadding,
                end = itemHorizontalPadding,
                bottom = 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (conversations.isEmpty()) {
                item(key = "empty") {
                    Column {
                        GkEmptyState(text = "暂无 AI 会话，通过快照按钮页发起对话")
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
                    )
                }
                item(key = "bottom-space") { GkPageBottomSpace() }
            }
        }
    }
}

private val conversationTimeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

@Composable
private fun ConversationCard(
    conversation: AiConversation,
    onClick: () -> Unit,
) {
    androidx.compose.material3.Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = li.gkd.app.ui.style.surfaceCardColors,
        onClick = onClick,
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
