package li.gkd.app.settings

import kotlinx.serialization.Serializable
import li.gkd.app.util.AiThinkingLevelOption

@Serializable
data class AiConfig(
    val protocol: String = "openai",
    val apiUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val temperature: Float = 0f,
    val topP: Float = 1f,
    val maxTokens: Int = 32768,
    /**
     * 服务端拒绝过的 Max Tokens（0 = 还没探到）。
     * 记下来才能避免「抬底 → 被拒 → 回退」每次生成都往返一次。
     */
    val maxTokensCeiling: Int = 0,
    /** 思考等级，取值见 [AiThinkingLevelOption]；默认自动关闭思考 */
    val thinkingLevel: String = AiThinkingLevelOption.Auto.value,
    /** thinkingLevel 为自定义时，直接合并进请求体的 JSON 片段 */
    val thinkingCustom: String = "",
)
