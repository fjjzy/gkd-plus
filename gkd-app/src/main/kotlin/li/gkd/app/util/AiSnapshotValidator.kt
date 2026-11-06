package li.gkd.app.util

import li.gkd.app.data.ComplexSnapshot
import li.gkd.app.data.NodeInfo
import li.gkd.app.data.RawSubscription
import li.gkd.app.feature.ai.AiSelectedComponent
import li.gkd.selector.MatchOptions
import li.gkd.selector.NodeAdapter
import li.gkd.selector.Selector
import li.gkd.selector.SelectorCompileResult

/**
 * 在本地快照树上真正跑一遍 AI 生成的选择器。
 *
 * 只做「静态可判定」的检查，不替代真机验证：
 * - 目标选择器当前是否命中；
 * - 是否命中了选中组件所在的那条父子链；
 * - 是否命中多个节点；
 * - 默认点击的目标是否真的可点击；
 * - clickCenter 的目标是否过小。
 *
 * 多步规则只验证第一步（没有 preKeys 的规则），后续步骤依赖运行时界面，静态快照里没有。
 */
object AiSnapshotValidator {

    fun validate(
        snapshot: ComplexSnapshot,
        subscription: RawSubscription,
        selected: List<AiSelectedComponent>,
    ): String? {
        val nodes = snapshot.nodes
        if (nodes.isEmpty()) return null
        val root = nodes.firstOrNull { it.pid < 0 } ?: return null
        val byId = nodes.associateBy { it.id }
        val children = nodes.groupBy { it.pid }
        val adapter = SnapshotNodeAdapter(root, byId, children)
        val selectedIds = AiSnapshotUtils.findSelectedNodeIds(nodes, selected)
        val groups: List<RawSubscription.RawGroupProps> =
            subscription.globalGroups + subscription.apps.flatMap { it.groups }

        groups.forEach { group ->
            val rule = group.rules.firstOrNull { it.preKeys.isNullOrEmpty() } ?: return@forEach
            val matches = rule.matches?.takeIf { it.isNotEmpty() }
            val candidates = matches
                ?: rule.anyMatches?.takeIf { it.isNotEmpty() }
                ?: return@forEach
            val targetSelectors = if (matches != null) listOf(matches.last()) else candidates

            val hits = targetSelectors.flatMap { source ->
                val selector = (Selector.compile(source) as? SelectorCompileResult.Success)
                    ?.value ?: return@flatMap emptyList()
                adapter.querySelectorAll(root, selector, MatchOptions.default)
            }.distinctBy { it.id }

            val ruleName = rule.name ?: group.name
            if (hits.isEmpty()) {
                return "规则「$ruleName」在当前快照中没有匹配到任何节点：" +
                    targetSelectors.joinToString(" / ")
            }
            if (hits.size > 1) {
                return "规则「$ruleName」在当前快照中命中了 ${hits.size} 个节点，" +
                    "请收紧到唯一目标：" + targetSelectors.joinToString(" / ")
            }
            val target = hits.first()
            if (selectedIds.isNotEmpty() && selectedIds.none { id -> isRelated(target.id, id, byId) }) {
                return "规则「$ruleName」命中的节点不是用户选中的组件，也不是它的父/子节点：" +
                    targetSelectors.joinToString(" / ")
            }

            val action = rule.action ?: "click"
            when (action) {
                "click", "clickNode" -> if (!target.attr.clickable) {
                    return "规则「$ruleName」的目标节点不可点击（clickable=false）。" +
                        "请改用最近的可点击父节点，或显式写 action: \"clickCenter\""
                }

                "clickCenter", "longClickCenter" -> if (rule.position == null &&
                    (target.attr.width < MIN_CLICK_CENTER_SIZE || target.attr.height < MIN_CLICK_CENTER_SIZE)
                ) {
                    return "规则「$ruleName」用 clickCenter 点击 ${target.attr.width}×${target.attr.height} " +
                        "的节点，小于 $MIN_CLICK_CENTER_SIZE×$MIN_CLICK_CENTER_SIZE，坐标点击容易点偏。" +
                        "请回退到可点击父节点"
                }
            }
        }
        return null
    }

    /** a 与 b 相同，或 a 是 b 的祖先，或 b 是 a 的祖先 */
    private fun isRelated(a: Int, b: Int, byId: Map<Int, NodeInfo>): Boolean {
        if (a == b) return true
        var current = byId[b]?.pid ?: -1
        val visited = HashSet<Int>()
        while (current >= 0 && visited.add(current)) {
            if (current == a) return true
            current = byId[current]?.pid ?: -1
        }
        current = byId[a]?.pid ?: -1
        visited.clear()
        while (current >= 0 && visited.add(current)) {
            if (current == b) return true
            current = byId[current]?.pid ?: -1
        }
        return false
    }

    private class SnapshotNodeAdapter(
        private val root: NodeInfo,
        private val byId: Map<Int, NodeInfo>,
        private val children: Map<Int, List<NodeInfo>>,
    ) : NodeAdapter<NodeInfo>() {

        override fun getAttr(target: Any, name: String): Any? = when (target) {
            is NodeInfo -> getAttr(target, name)
            else -> null
        }

        private fun getAttr(node: NodeInfo, name: String): Any? {
            val attr = node.attr
            return when (name) {
                "id" -> attr.id
                "vid" -> attr.vid
                "name" -> attr.name
                "text" -> attr.text
                "desc" -> attr.desc
                "clickable" -> attr.clickable
                "focusable" -> attr.focusable
                "checkable" -> attr.checkable
                "checked" -> attr.checked
                "editable" -> attr.editable
                "longClickable" -> attr.longClickable
                "visibleToUser" -> attr.visibleToUser
                "left" -> attr.left
                "top" -> attr.top
                "right" -> attr.right
                "bottom" -> attr.bottom
                "width" -> attr.width
                "height" -> attr.height
                "childCount" -> attr.childCount
                "index" -> attr.index
                "depth" -> attr.depth
                "parent" -> byId[node.pid]
                else -> null
            }
        }

        override fun getInvoke(target: Any, name: String, args: List<Any>): Any? = when {
            target is NodeInfo && name == "getChild" -> {
                val index = args.getOrNull(0) as? Int ?: return null
                children[target.id]?.getOrNull(index)
            }

            else -> null
        }

        override fun getName(node: NodeInfo): String? = node.attr.name

        override fun getChildCount(node: NodeInfo): Int = children[node.id]?.size ?: 0

        override fun getChild(node: NodeInfo, index: Int): NodeInfo? =
            children[node.id]?.getOrNull(index)

        override fun getParent(node: NodeInfo): NodeInfo? = byId[node.pid]

        override fun getNodeKey(node: NodeInfo): Any = node.id

        override fun getRoot(node: NodeInfo): NodeInfo = root
    }
}

private const val MIN_CLICK_CENTER_SIZE = 48
