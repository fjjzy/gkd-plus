package li.gkd.app.util

import li.gkd.app.model.Option
import li.gkd.app.resources.*
import org.jetbrains.compose.resources.StringResource

/**
 * 思考等级。各家服务端的思考参数名和取值都不一样，
 * 具体会发送什么由 [AiThinkingUtils.buildParams] 按模型名翻译。
 */
sealed class AiThinkingLevelOption(
    override val value: String,
    val label: String,
    override val labelResource: StringResource,
) : Option<String> {
    override val options get() = objects

    data object Auto :
        AiThinkingLevelOption("auto", "自动关闭（按模型名）", Res.string.ai_thinking_auto)
    data object None :
        AiThinkingLevelOption("none", "不发送（用服务端默认）", Res.string.ai_thinking_none)
    data object Off : AiThinkingLevelOption("off", "关闭思考", Res.string.ai_thinking_off)
    data object Low : AiThinkingLevelOption("low", "低", Res.string.ai_thinking_low)
    data object Medium : AiThinkingLevelOption("medium", "中", Res.string.ai_thinking_medium)
    data object High : AiThinkingLevelOption("high", "高", Res.string.ai_thinking_high)
    data object Custom :
        AiThinkingLevelOption("custom", "自定义 JSON", Res.string.ai_thinking_custom)

    companion object {
        val objects by lazy { listOf(Auto, None, Off, Low, Medium, High, Custom) }
    }
}
