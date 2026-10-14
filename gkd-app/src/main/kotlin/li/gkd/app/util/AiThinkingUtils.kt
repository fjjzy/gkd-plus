package li.gkd.app.util

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import li.gkd.app.data.settings.AiConfig

/**
 * 把思考等级翻译成各家服务端的请求字段。
 *
 * 各家的字段名和取值都不统一（`reasoning_effort` / `thinking.type` / `enable_thinking`），
 * 而且随时可能变，所以这里只收录**高置信度**的模型名特征：
 * 识别得出就发送对应字段，识别不出一律不发送。
 *
 * 这个方向是有意选的——不发送只是拿服务端默认值（可能仍然思考、多花 token），
 * 而发错字段会让请求直接 400、功能彻底不可用。所以宁可少发，也不猜。
 * 表里没有的模型用 [AiThinkingLevelOption.Custom] 手写字段。
 */
object AiThinkingUtils {

    /**
     * 识别出的模型家族，决定思考参数用哪个字段。
     *
     * [supportsDisable] 表示「发关闭取值不会被服务端拒绝」。为 false 的模型只能降到最低档，
     * 设置页会据此提示用户；关闭档也不会再发那些会被拒的字段。
     */
    enum class Family(val label: String, val supportsDisable: Boolean = true) {
        Anthropic("Anthropic Claude"),
        Gpt5("OpenAI GPT-5 及更新"),
        OpenAiReasoning("OpenAI o 系"),
        Grok("xAI Grok"),
        Zhipu("智谱 GLM 4.5–5.1"),
        ZhipuEffort("智谱 GLM 5.2"),
        ZhipuForced("智谱 GLM 5.3 及更新（强制思考）", supportsDisable = false),
        ZhipuForcedFixed("智谱 GLM 4.7 / 4.5V（强制思考且不可调档）", supportsDisable = false),
        Volc("火山方舟豆包"),
        Qwen("阿里通义千问"),
        DeepSeek("DeepSeek V4 及更新"),
        MiniMax("MiniMax M3"),
        MiniMaxEffort("MiniMax M3.1-Flash-Preview（不能关闭，只能降档）", supportsDisable = false),
        MiMo("小米 MiMo"),
        MiMoDashScope("小米 MiMo（百炼托管）"),
        Unknown("未识别"),
    }

    private val effortAll = setOf("minimal", "low", "medium", "high")
    private val effortWithoutMinimal = setOf("low", "medium", "high")

    /** 开启思考时的预算；这些接口没有档位，只能用 token 预算表达强弱 */
    private val anthropicBudgets = mapOf("low" to 1024, "medium" to 4096, "high" to 16384)
    private val qwenBudgets = mapOf("low" to 1024, "medium" to 4096, "high" to 16384)

    /** o1/o3/... 推理模型；要求 o 前是名字边界，避免把 gpt-4o 认成 o 系 */
    private val oSeriesPattern = Regex("""(^|[/\-_.])o\d""")

    /** 火山 seed 线（seed-1.6 / seed-2 / seed-3）；要求后面跟数字，避免撞上普通单词 */
    private val seedPattern = Regex("""seed[-_.]?\d""")

    /**
     * Claude 的常见叫法。用来区分「原生 Anthropic 行为」和「挂在 Anthropic 兼容端点上的别家模型」：
     * 前者不发送 thinking 就是关闭，后者只有显式发 disabled 才会真的关。
     */
    private val claudeNamePattern = Regex("""claude|opus|sonnet|haiku""")

    /**
     * 按模型名识别家族。
     *
     * 这里用「品牌 + 版本门槛」而不是列版本号，也不是裸匹配品牌：
     * - 列版本号（`glm-4.5 || glm-4.6 || glm-4.7`）会在新版本发布时静默失效，思考又默认开着白烧 token。
     * - 裸匹配品牌（`gpt`）会把 `gpt-4o` / `gpt-4.1` 也算进来，而它们不支持 `reasoning_effort`，
     *   发过去直接 400，比不发送糟得多。
     *
     * 所以对能确定门槛的家族用版本比较：`gpt-5` 起、`glm-4.5` 起。
     * grok / 豆包 / 通义不按版本卡：它们的品牌名本身就对应支持该字段的系列，
     * 代价是这些品牌下的老模型可能被多发送一次参数（由测试连接和「不发送」档兜住）。
     */
    fun familyOf(model: String, protocol: String): Family {
        if (protocol == "anthropic") return Family.Anthropic
        val name = model.lowercase()
        return when {
            name.contains("gpt") -> if (versionAtLeast(name, "gpt", 5)) Family.Gpt5 else Family.Unknown
            oSeriesPattern.containsMatchIn(name) -> Family.OpenAiReasoning
            name.contains("grok") -> Family.Grok
            name.contains("glm") -> zhipuFamily(name)
            name.contains("doubao") || seedPattern.containsMatchIn(name) -> Family.Volc
            // V4 起思考默认开启、由 thinking 参数控制；更早的版本用模型名区分思考模式，不能发这个字段
            name.contains("deepseek") -> if (versionAtLeast(name, "deepseek", 4)) Family.DeepSeek else Family.Unknown
            name.contains("minimax") -> miniMaxFamily(name)
            name.contains("mimo") -> if (name.contains("xiaomi/")) Family.MiMoDashScope else Family.MiMo
            name.contains("qwen") -> Family.Qwen
            else -> Family.Unknown
        }
    }

    /**
     * 智谱各代的思考能力差别很大，按能力分档（依据官方文档）：
     * - `glm-5.3` 及更新：强制思考，`thinking.type=disabled` 会报错，只能靠 `reasoning_effort` 降档。
     * - `glm-4.7` / `glm-4.5v`：强制思考，且不支持 `reasoning_effort`，没有任何可调档位。
     * - `glm-5.2`：可切换思考，且支持 `reasoning_effort`。
     * - `glm-4.5`–`glm-5.1`：只能切 `thinking.type`，不支持 `reasoning_effort`。
     */
    private fun zhipuFamily(name: String): Family = when {
        versionAtLeast(name, "glm", 5, 3) -> Family.ZhipuForced
        name.contains("glm-4.7") || name.contains("glm-4.5v") -> Family.ZhipuForcedFixed
        versionAtLeast(name, "glm", 5, 2) -> Family.ZhipuEffort
        versionAtLeast(name, "glm", 4, 5) -> Family.Zhipu
        else -> Family.Unknown
    }

    /**
     * MiniMax 各代对 `thinking` 的支持不一样，只能分开处理：
     * - M3.1-Flash-Preview 不能关闭思考，发 `disabled` 会 400，且 effort 默认是 max，
     *   所以只能靠降 `reasoning_effort` 省 token。
     * - M3 支持 `thinking.type = disabled`。
     * - M2.x 接受这个参数但忽略，发了也没用。
     */
    private fun miniMaxFamily(name: String): Family = when {
        name.contains("preview") -> Family.MiniMaxEffort
        name.contains("m3") -> Family.MiniMax
        else -> Family.Unknown
    }

    /**
     * 判断模型名里 [keyword] 后面跟的版本号是否不低于 `major.minor`。
     * 支持 `gpt-5`、`gpt-5.2`、`gpt5`、`deepseek-v4-flash`、`openai/gpt-5.1` 这些写法；
     * `gpt-oss`、`deepseek-r1`、`glm-z1` 这类关键词后不是版本号的取不到，一律算不满足。
     */
    private fun versionAtLeast(name: String, keyword: String, major: Int, minor: Int = 0): Boolean {
        val match = Regex("""$keyword[-_.]?v?(\d+)(?:[.\-_](\d+))?""").find(name) ?: return false
        val foundMajor = match.groupValues[1].toIntOrNull() ?: return false
        val foundMinor = match.groupValues[2].toIntOrNull() ?: 0
        return foundMajor > major || (foundMajor == major && foundMinor >= minor)
    }

    /**
     * 生成要合并进请求体的思考参数。空 Map 表示不发送任何字段。
     */
    fun buildParams(config: AiConfig): Map<String, JsonElement> {
        val level = config.thinkingLevel
        if (level == AiThinkingLevelOption.None.value) return emptyMap()
        if (level == AiThinkingLevelOption.Custom.value) return parseCustom(config.thinkingCustom)
        // 自动档的意图就是「别思考」：识别到模型就发关闭字段，识别不到就什么都不发
        val want = if (level == AiThinkingLevelOption.Auto.value) "off" else level
        return when (familyOf(config.model, config.protocol)) {
            Family.Unknown -> emptyMap()
            Family.Anthropic -> anthropicParams(want, isClaudeModel(config.model))
            Family.Gpt5 -> effortParams(want, off = "minimal", supported = effortAll)
            Family.OpenAiReasoning -> effortParams(want, off = "low", supported = effortWithoutMinimal)
            Family.Grok -> effortParams(want, off = "low", supported = setOf("low", "high"))
            // 不能关闭思考，只能把 effort 从默认的 max 降下来
            Family.MiniMaxEffort -> effortParams(want, off = "low", supported = setOf("low", "medium", "high"))
            Family.ZhipuForced -> zhipuForcedParams(want)
            Family.ZhipuEffort -> zhipuEffortParams(want)
            // 强制思考且没有任何档位可调，发什么都是多余的
            Family.ZhipuForcedFixed -> emptyMap()
            Family.Zhipu, Family.Volc, Family.DeepSeek, Family.MiniMax, Family.MiMo ->
                thinkingTypeParams(want)
            Family.Qwen, Family.MiMoDashScope -> qwenParams(want)
        }
    }

    /**
     * 设置页里展示「实际会发送什么」，让自动识别可核对。
     */
    fun describe(config: AiConfig): String {
        val level = config.thinkingLevel
        if (level == AiThinkingLevelOption.None.value) {
            return "不发送思考参数，使用服务端默认"
        }
        if (level == AiThinkingLevelOption.Custom.value) {
            val params = parseCustom(config.thinkingCustom)
            return if (params.isEmpty()) {
                "自定义 JSON 为空或解析失败，将不发送"
            } else {
                "将发送：" + formatParams(params)
            }
        }
        val family = familyOf(config.model, config.protocol)
        val label = AiThinkingLevelOption.objects.findOption(level).label
        val params = buildParams(config)
        val head = "模型：${family.label}｜等级：$label"
        val tail = if (params.isEmpty()) {
            "将不发送思考参数（未识别该模型，或该等级在这个模型上没有对应字段）"
        } else {
            "将发送：" + formatParams(params)
        }
        // 关不掉的模型要明说，否则用户以为选了关闭就真关了
        val forced = if (!family.supportsDisable) {
            "\n该模型不支持关闭思考，关闭档只会落到最低档；要彻底不思考只能换模型"
        } else {
            ""
        }
        // 内置表认不出时，用 models.dev 的能力表补一句（查不到就什么都不加）
        val supplement = if (family == Family.Unknown) {
            AiModelCatalogUtils.describeSupplement(config.model)?.let { "\n$it" }.orEmpty()
        } else {
            ""
        }
        return "$head\n$tail$forced$supplement"
    }

    /** 自定义 JSON 是否可用；不可用时给出提示，null 表示通过（空串交给 [describe] 说明） */
    fun validateCustom(text: String): String? {
        if (text.isBlank()) return null
        return runCatching { json.parseToJsonElement(text.trim()).jsonObject }
            .fold(onSuccess = { null }, onFailure = { "自定义 JSON 解析失败：${it.message}" })
    }

    /**
     * 判断错误是不是「服务端不认我们发的思考参数」。
     * 各家的报错文案不统一（有英文也有中文），这里要求同时出现参数名和否定词，
     * 避免把鉴权、限流之类的错判成参数问题。
     */
    fun isThinkingRejection(e: Throwable): Boolean {
        val msg = (e.message ?: "").lowercase()
        val mentionsParam = msg.contains("thinking") || msg.contains("reasoning_effort") ||
            msg.contains("enable_thinking") || msg.contains("思考") || msg.contains("思维链")
        if (!mentionsParam) return false
        return msg.contains("unrecognized") || msg.contains("unknown") || msg.contains("unsupported") ||
            msg.contains("not permitted") || msg.contains("unexpected") ||
            msg.contains("invalid") || msg.contains("requires") ||
            // Anthropic/校验器的原文：「thinking.type: Input should be 'enabled'」
            msg.contains("input should be") || msg.contains("literal_error") ||
            msg.contains("not a valid") ||
            // 智谱强制思考模型的原文：「该模型始终思考，不支持关闭思考；请使用 low、high 或 max」
            msg.contains("不支持") || msg.contains("始终思考") || msg.contains("不能关闭")
    }

    /** 5.2：可切换思考，且支持 reasoning_effort */
    private fun zhipuEffortParams(level: String): Map<String, JsonElement> = when (level) {
        "off" -> mapOf("thinking" to disabledThinking())
        "low", "medium", "high" -> buildMap {
            put("thinking", enabledThinking(null))
            putAll(zhipuEffort(level))
        }
        else -> emptyMap()
    }

    /**
     * 5.3 及更新：强制思考，只能降 reasoning_effort；关闭档落到最低的 low
     * （官方给的迁移做法就是 `thinking.type` 改 enabled + `reasoning_effort: low`）。
     */
    private fun zhipuForcedParams(level: String): Map<String, JsonElement> {
        val effort = when (level) {
            "off", "low" -> "low"
            "medium" -> "high"
            "high" -> "max"
            else -> return emptyMap()
        }
        return buildMap {
            put("thinking", enabledThinking(null))
            putAll(zhipuEffort(effort))
        }
    }

    /** 智谱只认 low / high / max 三档 */
    private fun zhipuEffort(effort: String): Map<String, JsonElement> =
        mapOf("reasoning_effort" to JsonPrimitive(effort))

    private fun parseCustom(text: String): Map<String, JsonElement> {
        if (text.isBlank()) return emptyMap()
        return runCatching { json.parseToJsonElement(text.trim()).jsonObject.toMap() }
            .getOrElse { emptyMap() }
    }

    private fun formatParams(params: Map<String, JsonElement>): String =
        params.entries.joinToString(", ") { "${it.key} = ${it.value}" }

    private fun effortParams(
        level: String,
        off: String,
        supported: Set<String>,
    ): Map<String, JsonElement> {
        var value = when (level) {
            "off" -> off
            "low" -> "low"
            "medium" -> "medium"
            "high" -> "high"
            else -> return emptyMap()
        }
        // 该模型没有这个档位时退回低档，而不是发一个会 400 的值
        if (value !in supported) value = if ("low" in supported) "low" else off
        return mapOf("reasoning_effort" to JsonPrimitive(value))
    }

    /**
     * Anthropic 原生不发送 thinking 即为关闭；但不少中转把 DeepSeek/GLM 等挂在 Anthropic 兼容
     * 端点上，那里只有显式发 `disabled` 才会真的关（实测发 OpenAI 风格的字段一律被忽略），
     * 所以非 Claude 模型在关闭档要显式发一次。
     */
    private fun anthropicParams(level: String, isClaude: Boolean): Map<String, JsonElement> {
        if (level == "off") {
            return if (isClaude) emptyMap() else mapOf("thinking" to disabledThinking())
        }
        val budget = anthropicBudgets[level] ?: return emptyMap()
        return mapOf("thinking" to enabledThinking(budget))
    }

    private fun isClaudeModel(model: String): Boolean =
        claudeNamePattern.containsMatchIn(model.lowercase())

    private fun thinkingTypeParams(level: String): Map<String, JsonElement> = when (level) {
        "off" -> mapOf("thinking" to disabledThinking())
        "low", "medium", "high" -> mapOf("thinking" to enabledThinking(null))
        else -> emptyMap()
    }

    private fun qwenParams(level: String): Map<String, JsonElement> = when (level) {
        "off" -> mapOf("enable_thinking" to JsonPrimitive(false))
        "low", "medium", "high" -> buildMap {
            put("enable_thinking", JsonPrimitive(true))
            qwenBudgets[level]?.let { put("thinking_budget", JsonPrimitive(it)) }
        }
        else -> emptyMap()
    }

    private fun enabledThinking(budgetTokens: Int?) = buildJsonObject {
        put("type", JsonPrimitive("enabled"))
        if (budgetTokens != null) put("budget_tokens", JsonPrimitive(budgetTokens))
    }

    private fun disabledThinking() = buildJsonObject {
        put("type", JsonPrimitive("disabled"))
    }
}
