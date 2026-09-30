package li.gkd.app.feature.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import li.gkd.app.ui.component.GkPageBottomSpace
import li.gkd.app.ui.component.GkTopAppBar
import li.gkd.app.util.TimeUtils.throttle

@Serializable
data class AiSnapshotPickerRoute(val snapshotId: Long) : NavKey

@Composable
fun AiSnapshotPickerPage(route: AiSnapshotPickerRoute) {
    val mainVm = MainViewModel.requireCurrent()
    val vm = viewModel<AiSnapshotPickerVm>()
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    var selectedNode by remember { mutableStateOf<NodeInfo?>(null) }
    LaunchedEffect(route.snapshotId) { vm.loadSnapshot(route.snapshotId) }
    val nodes = snapshot?.nodes.orEmpty()
    val onNodeChange: (NodeInfo?) -> Unit = { node -> selectedNode = node }
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
                title = { Text(text = "选择组件") },
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                selectedNode?.let { node ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = node.attr.text ?: node.attr.desc ?: node.attr.id ?: "(无文本)",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${node.attr.name} (${node.attr.left},${node.attr.top},${node.attr.right},${node.attr.bottom})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(onClick = throttle { mainVm.popPage() }) {
                        Text(text = "算了")
                    }
                    TextButton(
                        enabled = selectedNode != null,
                        onClick = { onNodeChange(expandSelection(nodes, selectedNode)) },
                    ) {
                        Text(text = "扩大选中")
                    }
                    TextButton(
                        enabled = selectedNode != null,
                        onClick = { onNodeChange(shrinkSelection(nodes, selectedNode)) },
                    ) {
                        Text(text = "缩小选中")
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(
                        enabled = selectedNode != null,
                        onClick = throttle {
                            val appId = snapshot?.appId ?: return@throttle
                            val node = selectedNode ?: return@throttle
                            vm.startConversation(route.snapshotId, appId, node)
                            mainVm.navigatePage(AiConversationRoute(route.snapshotId))
                        },
                    ) {
                        Text(text = "对话生成")
                    }
                    TextButton(
                        enabled = selectedNode != null,
                        onClick = throttle {
                            val node = selectedNode ?: return@throttle
                            vm.generateDirect(route.snapshotId, node)
                            mainVm.popPage()
                        },
                    ) {
                        Text(text = "直接生成")
                    }
                }
            }
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SnapshotNodeSelector(
                screenshotModel = vm.screenshotFile(route.snapshotId),
                nodes = nodes,
                screenWidth = snapshot?.screenWidth ?: 0,
                screenHeight = snapshot?.screenHeight ?: 0,
                selectedNode = selectedNode,
                onSelectedNodeChange = onNodeChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            GkPageBottomSpace()
        }
    }
}
