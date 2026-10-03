package li.gkd.app.feature.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import li.gkd.app.MainViewModel
import li.gkd.app.ui.component.GkIconButton
import li.gkd.app.ui.component.GkIcons
import li.gkd.app.ui.component.GkPageBottomSpace
import li.gkd.app.ui.component.GkTopAppBar
import li.gkd.app.util.TimeUtils.throttle
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
    val listState = rememberLazyListState()
    LaunchedEffect(current?.messages?.size, current?.messages?.lastOrNull()?.content?.length) {
        if ((current?.messages?.size ?: 0) > 0) {
            listState.animateScrollToItem(current!!.messages.size - 1)
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
                    Text(
                        text = current?.appId?.substringAfterLast('.') ?: "AI 对话",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(
                        // 最大值取一个：键盘弹出时贴键盘，收起时贴导航栏，避免双重叠加
                        WindowInsets.ime.union(WindowInsets.navigationBars)
                    )
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
            }
        },
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(contentPadding),
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
                items(current.messages, key = { it.time }) { message ->
                    MessageItem(message)
                }
                if (current.status == AiConversation.STATUS_GENERATING) {
                    item(key = "generating") {
                        Text(
                            text = "AI 正在生成规则...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                item(key = "bottom-space") { GkPageBottomSpace() }
            }
        }
    }
}

private val messageTimeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

@Composable
private fun MessageItem(message: AiMessage) {
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
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isUser) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                text = messageTimeFormat.format(Date(message.time)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
