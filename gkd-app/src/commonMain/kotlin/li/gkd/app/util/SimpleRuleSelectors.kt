package li.gkd.app.util

private val simpleRuleBoundsPattern = Regex("""^\s*\d+\s*,\s*\d+\s*,\s*\d+\s*,\s*\d+\s*$""")

/**
 * 把简易规则输入转成选择器：
 * - `left,top,right,bottom` 或 `bounds:...` → bounds 匹配
 * - `vid:xxx` / `id:xxx` / `text:xxx` → 显式指定属性
 * - 含 `:id/` 的完整 ID → `[id="..."]`
 * - 其余按文字匹配
 */
fun simpleRuleSelector(input: String): String? {
    val value = input.trim()
    if (value.isEmpty()) return null
    val bounds =
        when {
            value.startsWith("bounds:", ignoreCase = true) ->
                value.substring("bounds:".length).trim()
            simpleRuleBoundsPattern.matches(value) -> value
            else -> null
        }
    if (bounds != null) {
        val numbers = bounds.split(',').map { it.trim().toIntOrNull() }
        if (numbers.size != 4 || numbers.any { it == null }) return null
        val (left, top, right, bottom) = numbers
        return "[left=$left&&top=$top&&right=$right&&bottom=$bottom]"
    }
    prefixedValue(value, "vid:")?.let {
        return "[vid=\"${escapeSelectorValue(it)}\"]"
    }
    prefixedValue(value, "id:")?.let {
        return "[id=\"${escapeSelectorValue(it)}\"]"
    }
    prefixedValue(value, "text:")?.let {
        return "[text=\"${escapeSelectorValue(it)}\"]"
    }
    return if (value.contains(":id/")) {
        "[id=\"${escapeSelectorValue(value)}\"]"
    } else {
        "[text=\"${escapeSelectorValue(value)}\"]"
    }
}

/** 只有关闭方式为空时才用返回键；否则点击匹配到的关闭控件。 */
fun simpleRuleAction(closeLocator: String): String =
    if (simpleRuleSelector(closeLocator) == null) "back" else "click"

/**
 * 生成简易规则的 matches：
 * - 只填关闭方式：直接匹配并点击它
 * - 只填定位：匹配弹窗，动作为返回
 * - 都填：点击关闭控件，同时要求它与弹窗定位共享祖先节点（即弹窗确实出现过）
 */
fun buildSimpleRuleMatches(dialogLocator: String, closeLocator: String): String? {
    val dialogSelector = simpleRuleSelector(dialogLocator)
    val closeSelector = simpleRuleSelector(closeLocator)
    return when {
        closeSelector == null -> dialogSelector
        dialogSelector == null -> closeSelector
        else -> "@$closeSelector <<n * >n $dialogSelector"
    }
}

fun isInvalidSimpleRuleBounds(input: String): Boolean {
    val value = input.trim()
    if (!value.startsWith("bounds:", ignoreCase = true)) return false
    val bounds = value.substring("bounds:".length).trim()
    if (!simpleRuleBoundsPattern.matches(bounds)) return true
    return bounds.split(',').any { it.trim().toIntOrNull() == null }
}

private fun escapeSelectorValue(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

private fun prefixedValue(value: String, prefix: String): String? =
    if (value.startsWith(prefix, ignoreCase = true)) {
        value.substring(prefix.length).trim().takeIf { it.isNotEmpty() }
    } else {
        null
    }
