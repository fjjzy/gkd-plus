package li.gkd.app.util

private val simpleRuleBoundsPattern = Regex("""^\s*\d+\s*,\s*\d+\s*,\s*\d+\s*,\s*\d+\s*$""")
private val simpleRuleShortIdPattern = Regex("""^[A-Za-z_][A-Za-z0-9_]*$""")

/** 李跳跳规则里表示返回键的关闭方式 */
private const val SIMPLE_RULE_BACK = "GLOBAL_ACTION_BACK"

/**
 * 把简易规则输入转成选择器：
 * - `left,top,right,bottom` 或 `bounds:...` → bounds 匹配
 * - `vid:xxx` / `id:xxx` / `text:xxx` → 显式指定属性
 * - 含 `:id/` 的完整 ID → `[id="..."]`
 * - 其余按李跳跳的文字规则：默认包含匹配，`+` 开头、`-` 结尾、`=` 完全相同，`&` 连接多个条件；
 *   同时比对 text 与 desc，形如短 ID 的词也匹配 vid
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
    if (value.contains(":id/")) {
        return "[id=\"${escapeSelectorValue(value)}\"]"
    }
    val conditions = value.split('&').map { it.trim() }.filter { it.isNotEmpty() }
    if (conditions.isEmpty()) return null
    return conditions.joinToString(separator = " && ", prefix = "[", postfix = "]") {
        if (conditions.size == 1) textCondition(it) else "(${textCondition(it)})"
    }
}

private fun textCondition(term: String): String {
    val (operator, keyword) =
        when (term.first().takeIf { term.length > 1 }) {
            '+' -> "^=" to term.substring(1)
            '-' -> "$=" to term.substring(1)
            '=' -> "=" to term.substring(1)
            else -> "*=" to term
        }
    val escaped = escapeSelectorValue(keyword)
    val parts = mutableListOf("text$operator\"$escaped\"", "desc$operator\"$escaped\"")
    if (operator == "*=" && simpleRuleShortIdPattern.matches(keyword)) {
        parts += "vid=\"$escaped\""
    }
    return parts.joinToString(" || ")
}

private fun isSimpleRuleBack(closeLocator: String): Boolean =
    closeLocator.trim().equals(SIMPLE_RULE_BACK, ignoreCase = true)

/** 关闭方式为空或为 GLOBAL_ACTION_BACK 时用返回键；否则点击匹配到的关闭控件 */
fun simpleRuleAction(closeLocator: String): String =
    if (isSimpleRuleBack(closeLocator) || simpleRuleSelector(closeLocator) == null) "back" else "click"

/**
 * 生成简易规则的 matches（每一条都要在屏幕上找到，最后一条是点击目标）：
 * - 定位弹窗里的 `&` 拆成多条，表示屏幕上同时出现这几个条件，不要求是同一个控件
 * - 关闭方式是点击目标，里面的 `&` 仍要求同一个控件满足全部条件
 * - 只填定位：匹配弹窗，动作为返回；只填关闭方式：直接匹配并点击它
 */
fun buildSimpleRuleMatches(dialogLocator: String, closeLocator: String): List<String>? {
    val dialogSelectors =
        dialogLocator.split('&').mapNotNull { simpleRuleSelector(it) }
    val closeSelector = if (isSimpleRuleBack(closeLocator)) null else simpleRuleSelector(closeLocator)
    val matches = dialogSelectors + listOfNotNull(closeSelector)
    return matches.ifEmpty { null }
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
