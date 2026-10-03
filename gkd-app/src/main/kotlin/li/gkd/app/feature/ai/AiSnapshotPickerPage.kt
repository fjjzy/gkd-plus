package li.gkd.app.feature.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import li.gkd.app.MainViewModel
import li.gkd.app.data.NodeInfo
import li.gkd.app.util.TimeUtils.throttle

private val compactButtonPadding =
    androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)

@Serializable
data class AiSnapshotPickerRoute(val snapshotId: Long) : NavKey

@Composable
fun AiSnapshotPickerPage(route: AiSnapshotPickerRoute) {
    val mainVm = MainViewModel.requireCurrent()
    val vm = viewModel<AiSnapshotPickerVm>()
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    LaunchedEffect(route.snapshotId) { vm.loadSnapshot(route.snapshotId) }
    val nodes = snapshot?.nodes.orEmpty()
    // 单选：再次点击已选组件则取消
    var selected by remember { mutableStateOf<NodeInfo?>(null) }

    Scaffold { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // 无顶栏时仍需让出状态栏，否则截图顶进状态栏
                .statusBarsPadding(),
        ) {
            // 截图视图：缩小 + 居中，整页一屏不滚动
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                SnapshotNodeSelector(
                    screenshotModel = vm.screenshotFile(route.snapshotId),
                    nodes = nodes,
                    screenWidth = snapshot?.screenWidth ?: 0,
                    screenHeight = snapshot?.screenHeight ?: 0,
                    selectedNodes = listOfNotNull(selected),
                    onNodeToggle = { node ->
                        selected = if (selected?.id == node.id) null else node
                    },
                    modifier = Modifier
                        .fillMaxWidth(0.72f)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }
            // 操作区贴屏幕底部
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (selected != null) {
                    val comp = selected!!.toSelectedComponent()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = comp.displayText(),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // 紧凑单行按钮：文字不换行；放不下时横向滚动而不是折行
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    TextButton(
                        onClick = throttle { mainVm.popPage() },
                        contentPadding = compactButtonPadding,
                    ) {
                        Text(text = "算了", maxLines = 1, softWrap = false)
                    }
                    TextButton(
                        enabled = selected != null,
                        onClick = { selected = expandSelection(nodes, selected) },
                        contentPadding = compactButtonPadding,
                    ) {
                        Text(text = "扩大", maxLines = 1, softWrap = false)
                    }
                    TextButton(
                        enabled = selected != null,
                        onClick = { selected = shrinkSelection(nodes, selected) },
                        contentPadding = compactButtonPadding,
                    ) {
                        Text(text = "缩小", maxLines = 1, softWrap = false)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    TextButton(
                        enabled = selected != null,
                        onClick = throttle {
                            val appId = snapshot?.appId ?: return@throttle
                            val node = selected ?: return@throttle
                            vm.startConversation(route.snapshotId, appId, listOf(node))
                            mainVm.navigatePage(AiConversationRoute(route.snapshotId))
                        },
                        contentPadding = compactButtonPadding,
                    ) {
                        Text(text = "对话生成", maxLines = 1, softWrap = false)
                    }
                    TextButton(
                        enabled = selected != null,
                        onClick = throttle {
                            val appId = snapshot?.appId ?: return@throttle
                            val node = selected ?: return@throttle
                            vm.generateDirect(route.snapshotId, appId, listOf(node))
                            mainVm.popPage()
                        },
                        contentPadding = compactButtonPadding,
                    ) {
                        Text(text = "直接生成", maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}
