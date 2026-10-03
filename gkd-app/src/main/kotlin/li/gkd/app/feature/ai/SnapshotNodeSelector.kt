package li.gkd.app.feature.ai

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import li.gkd.app.data.NodeInfo

/**
 * 快照组件选择器：
 * - 点击截图命中节点（选面积最小的命中者）
 * - 扩大 = 选中其父节点；缩小 = 选中其最小子节点
 * - 通过回调通知当前选中节点
 */
@Composable
fun SnapshotNodeSelector(
    screenshotModel: Any?,
    nodes: List<NodeInfo>,
    screenWidth: Int,
    screenHeight: Int,
    selectedNodes: List<NodeInfo>,
    onNodeToggle: (NodeInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val strokePx = with(density) { 2.dp.toPx() }
    val scaleX = if (screenWidth > 0 && canvasSize.width > 0) {
        canvasSize.width.toFloat() / screenWidth
    } else {
        0f
    }
    val scaleY = if (screenHeight > 0 && canvasSize.height > 0) {
        canvasSize.height.toFloat() / screenHeight
    } else {
        0f
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(
                ratio = if (screenHeight > 0) screenWidth.toFloat() / screenHeight else 9f / 16f,
                // 外部给定高度时以高度为准推导宽度
                matchHeightConstraintsFirst = true,
            ),
    ) {
        AsyncImage(
            model = screenshotModel,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = it }
                .pointerInput(nodes, screenWidth, screenHeight, scaleX, scaleY) {
                    detectTapGestures { offset: Offset ->
                        val x = if (scaleX > 0) offset.x / scaleX else offset.x
                        val y = if (scaleY > 0) offset.y / scaleY else offset.y
                        // 命中最小面积节点：再点选中的节点则取消选择
                        val hit = nodes
                            .filter { it.attr.visibleToUser }
                            .filter { node ->
                                node.attr.left <= x && node.attr.right >= x &&
                                    node.attr.top <= y && node.attr.bottom >= y
                            }
                            .minByOrNull { it.attr.width * it.attr.height }
                        if (hit != null) {
                            onNodeToggle(hit)
                        }
                    }
                },
        ) {
            selectedNodes.forEach { node ->
                drawRect(
                    color = Color(0xFF2196F3),
                    topLeft = Offset(node.attr.left * scaleX, node.attr.top * scaleY),
                    size = androidx.compose.ui.geometry.Size(
                        node.attr.width * scaleX,
                        node.attr.height * scaleY,
                    ),
                    style = Stroke(width = strokePx),
                )
            }
        }
    }
}

/**
 * 扩大选中：返回父节点（null 表示已到根）
 */
fun expandSelection(nodes: List<NodeInfo>, current: NodeInfo?): NodeInfo? {
    if (current == null) return null
    return nodes.find { it.id == current.pid }
}

/**
 * 缩最小的可见子节点（null 表示无子节点）
 */
fun shrinkSelection(nodes: List<NodeInfo>, current: NodeInfo?): NodeInfo? {
    if (current == null) return null
    return nodes
        .filter { it.pid == current.id && it.attr.visibleToUser }
        .minByOrNull { it.attr.width * it.attr.height }
}
