package li.gkd.app.util

import kotlinx.serialization.json.JsonPrimitive
import li.gkd.app.data.settings.AiConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiThinkingUtilsTest {

    private fun config(
        model: String,
        level: String,
        custom: String = "",
        protocol: String = "openai",
    ) = AiConfig(
        protocol = protocol,
        model = model,
        thinkingLevel = level,
        thinkingCustom = custom,
    )

    @Test
    fun autoSendsDisableFieldForRecognizedFamily() {
        // 自动档的意图是「别思考」：识别到模型就发关闭字段
        assertEquals(
            mapOf("reasoning_effort" to JsonPrimitive("minimal")),
            AiThinkingUtils.buildParams(config("gpt-5.1", AiThinkingLevelOption.Auto.value)),
        )
        assertEquals(
            mapOf("enable_thinking" to JsonPrimitive(false)),
            AiThinkingUtils.buildParams(config("qwen3-max", AiThinkingLevelOption.Auto.value)),
        )
    }

    @Test
    fun unrecognizedModelSendsNothing() {
        // 认不出来时宁可不发：发错字段会让请求直接 400
        assertTrue(
            AiThinkingUtils.buildParams(config("deepseek-reasoner", AiThinkingLevelOption.Auto.value)).isEmpty(),
        )
        assertTrue(
            AiThinkingUtils.buildParams(config("", AiThinkingLevelOption.Off.value)).isEmpty(),
        )
    }

    @Test
    fun noneNeverSendsAnythingEvenForKnownModel() {
        assertTrue(
            AiThinkingUtils.buildParams(config("gpt-5.1", AiThinkingLevelOption.None.value)).isEmpty(),
        )
        assertTrue(
            AiThinkingUtils.buildParams(
                config("gpt-5.1", AiThinkingLevelOption.None.value, custom = """{"thinking":{"type":"disabled"}}"""),
            ).isEmpty(),
        )
    }

    @Test
    fun oSeriesHasNoMinimalEffort() {
        // o 系不支持 minimal，关闭档必须退到 low，否则服务端 400
        assertEquals(
            mapOf("reasoning_effort" to JsonPrimitive("low")),
            AiThinkingUtils.buildParams(config("o3-mini", AiThinkingLevelOption.Off.value)),
        )
    }

    @Test
    fun unsupportedLevelFallsBackInsteadOfSendingInvalidValue() {
        // grok 没有 medium 档，退到 low 而不是发一个会被拒绝的值
        assertEquals(
            mapOf("reasoning_effort" to JsonPrimitive("low")),
            AiThinkingUtils.buildParams(config("grok-4", AiThinkingLevelOption.Medium.value)),
        )
    }

    @Test
    fun anthropicOffMeansNoFieldAndEnabledCarriesBudget() {
        val off = config("claude-sonnet-4", AiThinkingLevelOption.Off.value, protocol = "anthropic")
        assertTrue(AiThinkingUtils.buildParams(off).isEmpty())
        assertEquals(
            """{"type":"enabled","budget_tokens":4096}""",
            AiThinkingUtils.buildParams(off.copy(thinkingLevel = AiThinkingLevelOption.Medium.value))["thinking"]
                .toString(),
        )
    }

    @Test
    fun anthropicProtocolStillDisablesThinkingForNonClaudeModel() {
        // 中转常把 DeepSeek/GLM 挂在 Anthropic 兼容端点上，那里只有显式发 disabled 才会真的关；
        // 若跟着 Claude 一起「什么都不发」，用户选了关闭思考却还在烧 token
        assertEquals(
            """{"type":"disabled"}""",
            AiThinkingUtils.buildParams(
                config("DeepSeek-V4.1-Flash", AiThinkingLevelOption.Off.value, protocol = "anthropic"),
            )["thinking"].toString(),
        )
    }

    @Test
    fun customJsonIsMergedVerbatimAndInvalidJsonSendsNothing() {
        val custom = config(
            "gpt-5.1",
            AiThinkingLevelOption.Custom.value,
            custom = """{"reasoning_effort":"high"}""",
        )
        assertEquals(mapOf("reasoning_effort" to JsonPrimitive("high")), AiThinkingUtils.buildParams(custom))

        val broken = custom.copy(thinkingCustom = "{ not json")
        assertTrue(AiThinkingUtils.buildParams(broken).isEmpty())
        assertTrue(AiThinkingUtils.validateCustom(broken.thinkingCustom) != null)
    }

    @Test
    fun newGptAndGlmVersionsAreRecognizedWithoutEditingTheTable() {
        // 门槛匹配的意义：新版本发布后不用改代码也能识别出来
        assertEquals(AiThinkingUtils.Family.Gpt5, AiThinkingUtils.familyOf("gpt-6", "openai"))
        assertEquals(AiThinkingUtils.Family.Gpt5, AiThinkingUtils.familyOf("openai/gpt-7.2-codex", "openai"))
        assertEquals(AiThinkingUtils.Family.Zhipu, AiThinkingUtils.familyOf("glm-5.1", "openai"))
        assertTrue(AiThinkingUtils.familyOf("zhipu/glm-6", "openai") != AiThinkingUtils.Family.Unknown)
    }

    @Test
    fun modelsThatRejectTheThinkingFieldStayUnknown() {
        // gpt-4o / gpt-4.1 不支持 reasoning_effort，误判会让请求 400
        assertEquals(AiThinkingUtils.Family.Unknown, AiThinkingUtils.familyOf("gpt-4o", "openai"))
        assertEquals(AiThinkingUtils.Family.Unknown, AiThinkingUtils.familyOf("gpt-4.1-mini", "openai"))
        // glm-4-flash 不支持 thinking
        assertEquals(AiThinkingUtils.Family.Unknown, AiThinkingUtils.familyOf("glm-4-flash", "openai"))
        // 关键词后面不是版本号的也认不出
        assertEquals(AiThinkingUtils.Family.Unknown, AiThinkingUtils.familyOf("gpt-oss-120b", "openai"))
        assertEquals(AiThinkingUtils.Family.Unknown, AiThinkingUtils.familyOf("glm-z1-air", "openai"))
    }

    @Test
    fun oSeriesIsDetectedWithProviderPrefixButNotFromGpt4o() {
        assertEquals(AiThinkingUtils.Family.OpenAiReasoning, AiThinkingUtils.familyOf("o3-mini", "openai"))
        // 带厂商前缀时 startsWith 会漏掉，这里必须还能认出来
        assertEquals(AiThinkingUtils.Family.OpenAiReasoning, AiThinkingUtils.familyOf("openai/o5-preview", "openai"))
        // gpt-4o 里的 "4o" 不能被当成 o 系
        assertTrue(AiThinkingUtils.familyOf("gpt-4o", "openai") != AiThinkingUtils.Family.OpenAiReasoning)
    }

    @Test
    fun deepSeekV4CanDisableThinkingButEarlierVersionsAreLeftAlone() {
        // V4 思考默认开启，不发参数就会一直白烧 token
        assertEquals(
            mapOf("thinking" to """{"type":"disabled"}"""),
            AiThinkingUtils.buildParams(config("deepseek-v4-flash", AiThinkingLevelOption.Auto.value))
                .mapValues { it.value.toString() },
        )
        assertEquals(
            AiThinkingUtils.Family.DeepSeek,
            AiThinkingUtils.familyOf("deepseek/deepseek-v4.1-pro", "openai"),
        )
        // V3 及更早用模型名区分思考模式，deepseek-reasoner / r1 更不能被当成可关闭
        assertEquals(AiThinkingUtils.Family.Unknown, AiThinkingUtils.familyOf("deepseek-v3.2", "openai"))
        assertEquals(AiThinkingUtils.Family.Unknown, AiThinkingUtils.familyOf("deepseek-reasoner", "openai"))
        assertEquals(AiThinkingUtils.Family.Unknown, AiThinkingUtils.familyOf("deepseek-r1", "openai"))
    }

    @Test
    fun miniMaxVariantsAreSplitByThinkingSupport() {
        // M3.1-Flash-Preview 发 disabled 会 400，只能降 effort
        assertEquals(
            mapOf("reasoning_effort" to JsonPrimitive("low")),
            AiThinkingUtils.buildParams(config("MiniMax-M3.1-Flash-Preview", AiThinkingLevelOption.Auto.value)),
        )
        // M3 可以真的关掉
        assertEquals(
            mapOf("thinking" to """{"type":"disabled"}"""),
            AiThinkingUtils.buildParams(config("MiniMax-M3", AiThinkingLevelOption.Auto.value))
                .mapValues { it.value.toString() },
        )
        // M2.x 接受但忽略这个参数，没必要发
        assertEquals(AiThinkingUtils.Family.Unknown, AiThinkingUtils.familyOf("MiniMax-M2.7", "openai"))
    }

    @Test
    fun miMoUsesDifferentFieldDependingOnHost() {
        // 小米自家接口
        assertEquals(
            mapOf("thinking" to """{"type":"disabled"}"""),
            AiThinkingUtils.buildParams(config("mimo-v2.5-pro", AiThinkingLevelOption.Auto.value))
                .mapValues { it.value.toString() },
        )
        // 百炼托管的同一个模型用 enable_thinking
        assertEquals(
            mapOf("enable_thinking" to JsonPrimitive(false)),
            AiThinkingUtils.buildParams(config("xiaomi/mimo-v2.5-pro", AiThinkingLevelOption.Auto.value)),
        )
    }

    @Test
    fun onlyThinkingParameterErrorsCountAsRejection() {
        // 各家报错文案不统一，这里列出实际会遇到的几种
        assertTrue(AiThinkingUtils.isThinkingRejection(Exception("Unrecognized request argument supplied: reasoning_effort")))
        assertTrue(AiThinkingUtils.isThinkingRejection(Exception("""unknown field "thinking"""")))
        assertTrue(AiThinkingUtils.isThinkingRejection(Exception("unsupported parameter: enable_thinking")))
        assertTrue(
            AiThinkingUtils.isThinkingRejection(
                Exception("""model "MiniMax-M3.1-Flash-Preview" requires adaptive thinking"""),
            ),
        )
        // 智谱强制思考模型的原文报错
        assertTrue(
            AiThinkingUtils.isThinkingRejection(
                Exception("该模型始终思考，不支持关闭思考；请使用 low、high 或 max。"),
            ),
        )
        // 别的 400 不能被误判成思考参数问题，否则会把好配置改掉
        assertTrue(!AiThinkingUtils.isThinkingRejection(Exception("HTTP 401 invalid api key")))
        assertTrue(!AiThinkingUtils.isThinkingRejection(Exception("HTTP 429 rate limit exceeded")))
        assertTrue(!AiThinkingUtils.isThinkingRejection(Exception("model not found: gpt-9")))
        assertTrue(!AiThinkingUtils.isThinkingRejection(Exception("invalid max_tokens value")))
    }

    @Test
    fun glmVersionsAreSplitByThinkingCapability() {
        // 5.3 起强制思考：发 disabled 会 400，只能降 reasoning_effort
        assertEquals(
            mapOf(
                "thinking" to """{"type":"enabled"}""",
                "reasoning_effort" to """"low"""",
            ),
            AiThinkingUtils.buildParams(config("glm-5.3-flash", AiThinkingLevelOption.Auto.value))
                .mapValues { it.value.toString() },
        )
        // 新版本同样按强制思考处理，不用改代码
        assertEquals(
            AiThinkingUtils.Family.ZhipuForced,
            AiThinkingUtils.familyOf("glm-6", "openai"),
        )
        // 4.7 / 4.5V 强制思考且不可调档，发什么都没用
        assertEquals(AiThinkingUtils.Family.ZhipuForcedFixed, AiThinkingUtils.familyOf("glm-4.7", "openai"))
        assertTrue(AiThinkingUtils.buildParams(config("glm-4.7", AiThinkingLevelOption.Off.value)).isEmpty())
        // 5.2 可切换思考，且支持 reasoning_effort
        assertEquals(
            mapOf("thinking" to """{"type":"disabled"}"""),
            AiThinkingUtils.buildParams(config("glm-5.2", AiThinkingLevelOption.Auto.value))
                .mapValues { it.value.toString() },
        )
        // 4.5–5.1 只能切 thinking.type
        assertEquals(
            mapOf("thinking" to """{"type":"disabled"}"""),
            AiThinkingUtils.buildParams(config("glm-5.1", AiThinkingLevelOption.Auto.value))
                .mapValues { it.value.toString() },
        )
        assertEquals(
            mapOf("thinking" to """{"type":"enabled"}"""),
            AiThinkingUtils.buildParams(config("glm-4.6", AiThinkingLevelOption.High.value))
                .mapValues { it.value.toString() },
        )
    }

    @Test
    fun forcedThinkingModelsAreMarkedSoTheSettingsCanWarn() {
        assertTrue(!AiThinkingUtils.familyOf("glm-5.3-flash", "openai").supportsDisable)
        assertTrue(!AiThinkingUtils.familyOf("glm-4.7", "openai").supportsDisable)
        assertTrue(!AiThinkingUtils.familyOf("MiniMax-M3.1-Flash-Preview", "openai").supportsDisable)
        assertTrue(AiThinkingUtils.familyOf("glm-5.2", "openai").supportsDisable)
        assertTrue(AiThinkingUtils.familyOf("glm-5.1", "openai").supportsDisable)
        // 关不掉的模型要在设置页里说明，否则用户以为选了关闭就真关了
        val description = AiThinkingUtils.describe(config("glm-5.3-flash", AiThinkingLevelOption.Auto.value))
        assertTrue(description, description.contains("不支持关闭思考"))
    }

    @Test
    fun describeNamesTheModelFamilyAndWhatWillBeSent() {
        val description = AiThinkingUtils.describe(config("glm-4.6", AiThinkingLevelOption.Auto.value))
        assertTrue(description, description.contains("智谱 GLM"))
        assertTrue(description, description.contains("disabled"))

        val unknown = AiThinkingUtils.describe(config("some-model", AiThinkingLevelOption.Auto.value))
        assertTrue(unknown, unknown.contains("未识别"))
    }
}
