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
        assertEquals("[id=\"com.example:id/skip\"]", simpleRuleSelector("com.example:id/skip"))
        assertEquals("[vid=\"skip\"]", simpleRuleSelector("vid:skip"))
        assertEquals("[text=\"id:skip\"]", simpleRuleSelector("text:id:skip"))
        assertNull(simpleRuleSelector("   "))
    }

    /** 文字写法对齐李跳跳：默认包含匹配，同时比对 text 与 desc */
    @Test
    fun liTiaotiaoTextSyntax() {
        assertEquals("[text*=\"跳过\" || desc*=\"跳过\"]", simpleRuleSelector("跳过"))
        assertEquals("[text^=\"检测到\" || desc^=\"检测到\"]", simpleRuleSelector("+检测到"))
        assertEquals("[text$=\"体验\" || desc$=\"体验\"]", simpleRuleSelector("-体验"))
        assertEquals("[text=\"以后再说\" || desc=\"以后再说\"]", simpleRuleSelector("=以后再说"))
        assertEquals(
            "[(text^=\"还剩\" || desc^=\"还剩\") && (text*=\"秒\" || desc*=\"秒\")]",
            simpleRuleSelector("+还剩&秒"),
        )
        // 李跳跳允许直接写短 ID
        assertEquals(
            "[text*=\"tv_close\" || desc*=\"tv_close\" || vid=\"tv_close\"]",
            simpleRuleSelector("tv_close"),
        )
        // 单独的符号按普通文字处理，不能变成匹配一切的空关键字
        assertEquals("[text*=\"+\" || desc*=\"+\"]", simpleRuleSelector("+"))
        listOf("跳过", "+检测到&-体验", "=确定", "tv_close", "+").forEach {
            assertValidSelector(simpleRuleSelector(it)!!)
        }
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
        val matches = assertNotNull(buildSimpleRuleMatches("移动文件", "确定"))
        assertEquals(
            listOf(
                "[text*=\"移动文件\" || desc*=\"移动文件\"]",
                "[text*=\"确定\" || desc*=\"确定\"]",
            ),
            matches,
        )
        assertEquals("click", simpleRuleAction("确定"))
        matches.forEach(::assertValidSelector)
    }

    /**
     * 定位弹窗里的 `&` 表示屏幕上同时出现这几个条件，可以分属不同控件；
     * 回归：MT 管理器的「移动」「取消」「确定」是三个控件，写成同一控件条件时永远匹配不到
     */
    @Test
    fun dialogConditionsCanBeOnDifferentNodes() {
        val matches = assertNotNull(buildSimpleRuleMatches("移动&取消&确定", "=确定"))
        assertEquals(
            listOf(
                "[text*=\"移动\" || desc*=\"移动\"]",
                "[text*=\"取消\" || desc*=\"取消\"]",
                "[text*=\"确定\" || desc*=\"确定\"]",
                "[text=\"确定\" || desc=\"确定\"]",
            ),
            matches,
        )
        matches.forEach(::assertValidSelector)
    }

    @Test
    fun backWhenCloseIsEmpty() {
        assertEquals(
            listOf("[text*=\"青少年模式\" || desc*=\"青少年模式\"]"),
            buildSimpleRuleMatches("青少年模式", ""),
        )
        assertEquals("back", simpleRuleAction(""))
        assertNull(buildSimpleRuleMatches("", ""))
    }

    /** 李跳跳用 GLOBAL_ACTION_BACK 表示返回键，不能被当成文字去点击 */
    @Test
    fun globalActionBackMeansBack() {
        assertEquals(
            listOf("[text=\"天天神券\" || desc=\"天天神券\"]"),
            buildSimpleRuleMatches("=天天神券", "GLOBAL_ACTION_BACK"),
        )
        assertEquals("back", simpleRuleAction("GLOBAL_ACTION_BACK"))
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
