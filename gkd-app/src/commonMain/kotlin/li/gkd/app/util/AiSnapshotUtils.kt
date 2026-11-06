package li.gkd.app.util

import li.gkd.app.logging.LogUtils

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import li.gkd.app.model.ComplexSnapshot
import li.gkd.app.model.NodeInfo
import li.gkd.app.feature.ai.AiSelectedComponent

/**
 * 生成发给模型的快照。
 *
 * 完整快照保存在本地供审查工具使用，这里只负责「发送版」：
 * - 去掉模型用不到或能从其它字段推导的属性，省略等于默认值的字段；
 * - 没有选中组件时保留整棵树，只清理不可见/零尺寸的空叶子；
 * - 有选中组件时只保留根到目标的路径、目标子树和路径上每一层的兄弟节点，
 *   保证 `>` / `<` / `+` / `-` 关系在发送的子集里仍然成立。
 */
object AiSnapshotUtils {

    fun buildModelSnapshot(
        snapshot: ComplexSnapshot,
        selected: List<AiSelectedComponent>,
    ): String {
        val nodes = snapshot.nodes
        val selectedIds = findSelectedNodeIds(nodes, selected)
        val focused = selectedIds.isNotEmpty()
        val keepIds = if (focused) {
            relevantNodeIds(nodes, selectedIds).ifEmpty { nodes.mapTo(HashSet()) { it.id } }
        } else {
            nodesWithoutNoiseLeaves(nodes)
        }
        val compact = buildJsonObject {
            put("id", snapshot.id)
            put("appId", snapshot.appId)
            snapshot.activityId?.let { put("activityId", it) }
            put("screenWidth", snapshot.screenWidth)
            put("screenHeight", snapshot.screenHeight)
            if (focused) put("focused", true)
            put(
                "nodes",
                buildJsonArray {
                    nodes.forEach { node ->
                        if (node.id in keepIds) add(compactNode(node))
                    }
                },
            )
        }.toString()
        LogUtils.d(
            "AI snapshot compact: nodes=${keepIds.size}/${nodes.size}," +
                " focused=$focused, chars=${compact.length}"
        )
        return compact
    }

    /** 用 bounds + 文本/描述/vid 定位用户选中的节点，允许同一区域命中多个节点 */
    fun findSelectedNodeIds(
        nodes: List<NodeInfo>,
        selected: List<AiSelectedComponent>,
    ): Set<Int> {
        if (selected.isEmpty()) return emptySet()
        val ids = HashSet<Int>()
        selected.forEach { component ->
            nodes.forEach { node ->
                val attr = node.attr
                if ("${attr.left},${attr.top},${attr.right},${attr.bottom}" != component.bounds) {
                    return@forEach
                }
                if (component.text != null && component.text != attr.text) return@forEach
                if (component.desc != null && component.desc != attr.desc) return@forEach
                if (component.vid != null && component.vid != attr.vid) return@forEach
                ids.add(node.id)
            }
        }
        return ids
    }

    /**
     * 根到选中节点的路径 + 目标子树 + 路径上每一层的全部兄弟。
     * 兄弟全部保留是为了不改变 `+` / `-` 的偏移。
     */
    private fun relevantNodeIds(nodes: List<NodeInfo>, selectedIds: Set<Int>): Set<Int> {
        val byId = nodes.associateBy { it.id }
        val childrenByPid = nodes.groupBy { it.pid }
        val keep = HashSet<Int>()

        fun addSubtree(id: Int) {
            val stack = ArrayDeque<Int>()
            stack.add(id)
            while (stack.isNotEmpty()) {
                val current = stack.removeLast()
                if (!keep.add(current)) continue
                childrenByPid[current]?.forEach { stack.add(it.id) }
            }
        }

        selectedIds.forEach { selectedId ->
            // 目标子树
            addSubtree(selectedId)
            // 向上补全路径和每层兄弟
            var current = byId[selectedId]
            while (current != null) {
                val parentId = current.pid
                if (parentId < 0) break
                keep.add(parentId)
                childrenByPid[parentId]?.forEach { keep.add(it.id) }
                current = byId[parentId]
            }
        }
        // 根节点始终保留
        nodes.firstOrNull { it.pid < 0 }?.let { keep.add(it.id) }
        return keep
    }

    /** 没有目标时保留整棵树，只去掉不可见或零尺寸、且没有任何可用属性的叶子 */
    private fun nodesWithoutNoiseLeaves(nodes: List<NodeInfo>): Set<Int> {
        val parentIds = nodes.mapTo(HashSet()) { it.pid }
        return nodes.asSequence()
            .filter { node ->
                if (node.id in parentIds) return@filter true
                val attr = node.attr
                val hasAttr = !attr.text.isNullOrEmpty() ||
                    !attr.desc.isNullOrEmpty() ||
                    !attr.vid.isNullOrEmpty() ||
                    attr.clickable ||
                    attr.longClickable
                hasAttr || (attr.visibleToUser && attr.width > 0 && attr.height > 0)
            }
            .mapTo(HashSet()) { it.id }
    }

    private fun compactNode(node: NodeInfo) = buildJsonObject {
        val attr = node.attr
        put("id", node.id)
        put("pid", node.pid)
        attr.vid?.let { put("vid", it) }
        attr.text?.let { put("text", it) }
        attr.desc?.let { put("desc", it) }
        attr.name?.let { put("name", it) }

        if (attr.clickable) put("clickable", true)
        if (attr.longClickable) put("longClickable", true)
        if (!attr.visibleToUser) put("visibleToUser", false)
        if (attr.checkable) {
            put("checkable", true)
            if (attr.checked == null) put("checked", JsonNull) else put("checked", attr.checked)
        }

        put("left", attr.left)
        put("top", attr.top)
        put("right", attr.right)
        put("bottom", attr.bottom)
        put("width", attr.width)
        put("height", attr.height)
        put("childCount", attr.childCount)
        put("index", attr.index)
        if (node.idQf == true) put("idQf", true)
        if (node.textQf == true) put("textQf", true)
    }
}
