package li.gkd.app.feature.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import li.gkd.app.MainViewModel
import li.gkd.app.data.NodeInfo
import li.gkd.app.ui.component.GkAlertDialog
import li.gkd.app.ui.component.GkIconButton
import li.gkd.app.ui.component.GkTopAppBar
import li.gkd.app.ui.component.GkIcons
import li.gkd.app.util.TimeUtils.throttle

// 四个按钮平分宽度，左右留白压到最小以给文字让位
private val compactButtonPadding =
    androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp)

/** 截图与状态栏、底部操作面板之间的上下留白 */
private val selectorVerticalMargin = 10.dp

@Serializable
data class AiSnapshotPickerRoute(
    val snapshotId: Long,
    /** 从会话页进来时为 true：点「对话生成」应回到那个会话，而不是再压一个会话页 */
    val fromConversation: Boolean = false,
) : NavKey

@Composable
fun AiSnapshotPickerPage(route: AiSnapshotPickerRoute) {
    val mainVm = MainViewModel.requireCurrent()
    val vm = viewModel<AiSnapshotPickerVm>()
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    LaunchedEffect(route.snapshotId) { vm.loadSnapshot(route.snapshotId) }
    val nodes = snapshot?.nodes.orEmpty()
    // 多选：再次点击已选组件则取消；多个组件在消息里用「组件 1」「组件 2」指代
    var selectedNodes by remember { mutableStateOf<List<NodeInfo>>(emptyList()) }
    var showSelectedInfo by remember { mutableStateOf(false) }
    // 「扩大」走过的节点栈：缩小时按原路回退，保证能递归回到最初选中的节点
    var expandStack by remember { mutableStateOf<List<NodeInfo>>(emptyList()) }
    // 底部按钮行高度（px），用于推导截图高度
    var bottomBarHeightPx by remember { mutableStateOf(0) }
    val lastComponent = selectedNodes.lastOrNull()?.toSelectedComponent()
    val summary = when (selectedNodes.size) {
        0 -> "点击截图选择组件"
        1 -> lastComponent?.displayText().orEmpty()
        else -> "已选 ${selectedNodes.size} 个：${lastComponent?.displayText().orEmpty()}"
    }

    Scaffold(topBar = {
        // 与其他 AI 页面共用 GkTopAppBar，保证高度、内边距和图标色调一致
        GkTopAppBar(
            title = {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (selectedNodes.isEmpty()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            },
            navigationIcon = {
                GkIconButton(
                    imageVector = GkIcons.ArrowBack,
                    onClick = { mainVm.popPage() },
                )
            },
            actions = {
                // 常驻按钮（没选中时禁用），避免顶栏内容随选中状态变化
                GkIconButton(
                    imageVector = GkIcons.MoreVert,
                    contentDescription = "查看已选组件信息",
                    enabled = selectedNodes.isNotEmpty(),
                    onClick = { showSelectedInfo = true },
                )
            },
        )
    }) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            val density = LocalDensity.current
            val verticalMarginPx = with(density) { selectorVerticalMargin.roundToPx() }
            // 截图高度 = 剩余高 - 底部按钮行 - 上下各 10dp。
            // 首帧按钮行还没测量出来，此时按 0 算，截图偏大 10dp 以内，不会溢出。
            val selectorHeightDp = with(density) {
                (constraints.maxHeight - bottomBarHeightPx - verticalMarginPx * 2)
                    .coerceAtLeast(0)
                    .toDp()
            }
            Column(modifier = Modifier.fillMaxSize()) {
                Spacer(modifier = Modifier.height(selectorVerticalMargin))
                // 截图视图：按推导高度展示，恰好位于顶栏下方、底部按钮行上方
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(selectorHeightDp)
                        .clip(MaterialTheme.shapes.medium),
                    contentAlignment = Alignment.Center,
                ) {
                    SnapshotNodeSelector(
                        screenshotModel = vm.screenshotFile(route.snapshotId),
                        nodes = nodes,
                        screenWidth = snapshot?.screenWidth ?: 0,
                        screenHeight = snapshot?.screenHeight ?: 0,
                        selectedNodes = selectedNodes,
                        onNodeToggle = { node ->
                            selectedNodes = if (selectedNodes.any { it.id == node.id }) {
                                selectedNodes.filterNot { it.id == node.id }
                            } else {
                                selectedNodes + node
                            }
                            // 重新点选后，之前的扩大路径不再适用
                            expandStack = emptyList()
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // 剩余高度全给这里，使底部按钮行贴屏幕底部
                Spacer(modifier = Modifier.weight(1f))
                // 四个按钮平分宽度；文字不换行，放不下时省略号收尾
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .onGloballyPositioned { coords -> bottomBarHeightPx = coords.size.height }
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        // 扩大/缩小作用于最后选中的那个组件
                        enabled = selectedNodes.isNotEmpty(),
                        onClick = {
                            val current = selectedNodes.lastOrNull()
                            val parent = if (current != null) {
                                expandSelection(nodes, current)
                            } else {
                                null
                            }
                            if (current != null && parent != null) {
                                expandStack = expandStack + current
                                selectedNodes = selectedNodes.dropLast(1) + parent
                            }
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = compactButtonPadding,
                    ) {
                        Text(
                            text = "扩大",
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(
                        // 只有扩大过才有可回退的节点，此时「缩小」才有意义
                        enabled = expandStack.isNotEmpty(),
                        onClick = {
                            val previous = expandStack.lastOrNull()
                            if (previous != null) {
                                expandStack = expandStack.dropLast(1)
                                selectedNodes = selectedNodes.dropLast(1) + previous
                            }
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = compactButtonPadding,
                    ) {
                        Text(
                            text = "缩小",
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    TextButton(
                        enabled = selectedNodes.isNotEmpty(),
                        onClick = throttle {
                            val appId = snapshot?.appId ?: return@throttle
                            vm.startConversation(route.snapshotId, appId, selectedNodes)
                            if (route.fromConversation) {
                                // 从会话页进来的：组件已经写进那个会话，直接返回它，
                                // 否则会在返回栈里留下两个相同的会话页
                                mainVm.popPage()
                            } else {
                                mainVm.navigatePage(
                                    AiConversationRoute(route.snapshotId),
                                    replaced = true,
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = compactButtonPadding,
                    ) {
                        Text(
                            text = "对话生成",
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(
                        enabled = selectedNodes.isNotEmpty(),
                        onClick = throttle {
                            val appId = snapshot?.appId ?: return@throttle
                            vm.generateDirect(route.snapshotId, appId, selectedNodes)
                            mainVm.popPage()
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = compactButtonPadding,
                    ) {
                        Text(
                            text = "直接生成",
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
    if (showSelectedInfo) {
        SelectedComponentsDialog(
            components = selectedNodes.map { it.toSelectedComponent() },
            onDismissRequest = { showSelectedInfo = false },
        )
    }
}

/**
 * 逐项展示已选组件的信息。用可编辑的输入框是为了方便选中、复制和临时改动，
 * 改动只留在弹窗里，不写回会话。
 */
@Composable
private fun SelectedComponentsDialog(
    components: List<AiSelectedComponent>,
    onDismissRequest: () -> Unit,
) {
    val rows = remember(components) {
        components.flatMapIndexed { index, component ->
            listOf(ComponentInfoRow.Header("${index + 1}. ${component.displayText()}")) +
                component.infoRows().map { ComponentInfoRow.Field(it.first, it.second) }
        }
    }
    // 只服务于复制/临时改动，不落库
    val edits = remember(rows) { mutableStateMapOf<Int, String>() }
    GkAlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = "已选组件（${components.size}）") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                rows.forEachIndexed { index, row ->
                    when (row) {
                        is ComponentInfoRow.Header -> Text(
                            text = row.title,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        is ComponentInfoRow.Field -> OutlinedTextField(
                            label = { Text(text = row.label) },
                            value = edits[index] ?: row.value,
                            onValueChange = { edits[index] = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = "关闭")
            }
        },
    )
}

/** 弹窗里的一行：组件标题或一个字段 */
private sealed interface ComponentInfoRow {
    data class Header(val title: String) : ComponentInfoRow
    data class Field(val label: String, val value: String) : ComponentInfoRow
}

/**
 * 组件信息的字段列表；与 prompt 里给模型的字段保持一致，便于对照。
 * 为 null 的字段直接不显示——没有信息，显示出来只占地方。
 */
private fun AiSelectedComponent.infoRows(): List<Pair<String, String>> = listOfNotNull(
    text?.let { "文本" to it },
    desc?.let { "描述" to it },
    vid?.let { "id" to it },
    name?.let { "类名" to it },
    "可点击" to clickable.toString(),
    "可长按" to longClickable.toString(),
    "区域" to bounds,
)
