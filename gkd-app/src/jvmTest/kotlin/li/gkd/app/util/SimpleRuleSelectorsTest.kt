package li.gkd.app.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import li.gkd.selector.Selector
import li.gkd.selector.SelectorParseResult
import li.gkd.selector.SelectorTypeResult
import li.gkd.selector.createDefaultSelectorTypeModel

/**
 * 简易规则会直接生成选择器写进订阅，语法/类型错误必须在生成阶段就被测试挡住。
 */
class SimpleRuleSelectorsTest {
    private val typeModel = createDefaultSelectorTypeModel()

    private fun assertValidSelector(selector: String) {
        val parsed = Selector.parse(selector)
        assertTrue(parsed is SelectorParseResult.Success, "parse failed: $selector")
        val typeResult = parsed.value.validateType(typeModel)
        assertTrue(typeResult is SelectorTypeResult.Success, "type failed: $selector -> $typeResult")
    }

    @Test
    fun textAndIdSelectors() {
        assertEquals("[text=\"跳过\"]", simpleRuleSelector("跳过"))
        assertEquals("[id=\"com.example:id/skip\"]", simpleRuleSelector("com.example:id/skip"))
        assertEquals("[vid=\"skip\"]", simpleRuleSelector("vid:skip"))
        assertEquals("[text=\"id:skip\"]", simpleRuleSelector("text:id:skip"))
        assertNull(simpleRuleSelector("   "))
    }

    @Test
    fun boundsSelector() {
        assertEquals(
            "[left=10&&top=20&&right=30&&bottom=40]",
            simpleRuleSelector("10,20,30,40"),
        )
        assertEquals(
            "[left=10&&top=20&&right=30&&bottom=40]",
            simpleRuleSelector("bounds: 10, 20, 30, 40"),
        )
        assertValidSelector(simpleRuleSelector("bounds:10,20,30,40")!!)
    }

    @Test
    fun clickCloseWhenDialogMatches() {
        val matches = assertNotNull(buildSimpleRuleMatches("青少年模式", "我知道了"))
        assertEquals("@[text=\"我知道了\"] <<n * >n [text=\"青少年模式\"]", matches)
        assertEquals("click", simpleRuleAction("我知道了"))
        assertValidSelector(matches)
    }

    @Test
    fun backWhenCloseIsEmpty() {
        assertEquals("[text=\"青少年模式\"]", buildSimpleRuleMatches("青少年模式", ""))
        assertEquals("back", simpleRuleAction(""))
        assertNull(buildSimpleRuleMatches("", ""))
    }

    @Test
    fun invalidBoundsAreDetected() {
        assertTrue(isInvalidSimpleRuleBounds("bounds:1,2,3"))
        assertTrue(isInvalidSimpleRuleBounds("bounds:a,b,c,d"))
        assertFalse(isInvalidSimpleRuleBounds("bounds:1,2,3,4"))
        assertFalse(isInvalidSimpleRuleBounds("1,2,3,4"))
        assertFalse(isInvalidSimpleRuleBounds("青少年模式"))
    }
}
