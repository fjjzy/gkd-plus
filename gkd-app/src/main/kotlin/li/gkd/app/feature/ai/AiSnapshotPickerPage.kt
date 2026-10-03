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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
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
import li.gkd.app.ui.component.GkIconButton
import li.gkd.app.ui.component.GkIcons
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
    // 多选：保持选择顺序
    var selected by remember { mutableStateOf<List<NodeInfo>>(emptyList()) }
    val hasSelection = selected.isNotEmpty()
    val lastSelected = selected.lastOrNull()

    fun toggle(node: NodeInfo) {
        selected = if (selected.any { it.id == node.id }) {
            selected.filterNot { it.id == node.id }
        } else {
            selected + node
        }
    }
    fun replaceLast(node: NodeInfo?) {
        if (node == null) return
        selected = if (selected.isEmpty()) {
            listOf(node)
        } else {
            selected.subList(0, selected.size - 1) + node
        }
    }

    Scaffold { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 视图缩小 + 边距加大，整屏展示不滚动
            SnapshotNodeSelector(
                screenshotModel = vm.screenshotFile(route.snapshotId),
                nodes = nodes,
                screenWidth = snapshot?.screenWidth ?: 0,
                screenHeight = snapshot?.screenHeight ?: 0,
                selectedNodes = selected,
                onNodeToggle = ::toggle,
                modifier = Modifier
                    .fillMaxWidth(0.72f)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
        // 底部操作区（chips + 按钮），贴底不参与垂直滚动
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (selected.isNotEmpty()) {
                // 已选组件 chips：横向滚动，每个可单独移除
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    selected.forEach { node ->
                        val comp = node.toSelectedComponent()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .padding(start = 10.dp),
                        ) {
                            Text(
                                text = comp.displayText(),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                            GkIconButton(
                                imageVector = GkIcons.Close,
                                contentDescription = "移除 ${comp.displayText()}",
                                modifier = Modifier.size(28.dp),
                                onClick = { toggle(node) },
                            )
                        }
                    }
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
                    enabled = hasSelection,
                    onClick = { replaceLast(expandSelection(nodes, lastSelected)) },
                    contentPadding = compactButtonPadding,
                ) {
                    Text(text = "扩大", maxLines = 1, softWrap = false)
                }
                TextButton(
                    enabled = hasSelection,
                    onClick = { replaceLast(shrinkSelection(nodes, lastSelected)) },
                    contentPadding = compactButtonPadding,
                ) {
                    Text(text = "缩小", maxLines = 1, softWrap = false)
                }
                Spacer(modifier = Modifier.width(4.dp))
                TextButton(
                    enabled = hasSelection,
                    onClick = throttle {
                        val appId = snapshot?.appId ?: return@throttle
                        if (selected.isEmpty()) return@throttle
                        vm.startConversation(route.snapshotId, appId, selected)
                        mainVm.navigatePage(AiConversationRoute(route.snapshotId))
                    },
                    contentPadding = compactButtonPadding,
                ) {
                    Text(text = "对话生成", maxLines = 1, softWrap = false)
                }
                TextButton(
                    enabled = hasSelection,
                    onClick = throttle {
                        val appId = snapshot?.appId ?: return@throttle
                        if (selected.isEmpty()) return@throttle
                        vm.generateDirect(route.snapshotId, appId, selected)
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
