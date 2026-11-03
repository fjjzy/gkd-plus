package li.gkd.app.util

import li.gkd.app.data.settings.AiConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AiApiUrlTest {

    private fun config(
        url: String,
        protocol: String = "openai",
        model: String = "glm-4.7",
    ) = AiConfig(protocol = protocol, apiUrl = url, apiKey = "test-key", model = model)

    @Test
    fun providerVersionPathsAreNotRewrittenToV1() {
        // 各家路径版本不同：一律补 /v1 会把智谱、火山、千问的地址拼坏
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4",
            AiRuleGenerator.fixApiUrl("https://open.bigmodel.cn/api/paas/v4", "openai"),
        )
        assertEquals(
            "https://ark.cn-beijing.volces.com/api/v3",
            AiRuleGenerator.fixApiUrl("https://ark.cn-beijing.volces.com/api/v3", "openai"),
        )
        assertEquals(
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            AiRuleGenerator.fixApiUrl("https://dashscope.aliyuncs.com/compatible-mode/v1", "openai"),
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai",
            AiRuleGenerator.fixApiUrl("https://generativelanguage.googleapis.com/v1beta/openai", "openai"),
        )
    }

    @Test
    fun hostOnlyInputUsesProviderDefaultPath() {
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4",
            AiRuleGenerator.fixApiUrl("open.bigmodel.cn", "openai"),
        )
        assertEquals(
            "https://open.bigmodel.cn/api/coding/paas/v4",
            AiRuleGenerator.fixApiUrl("https://open.bigmodel.cn/api/coding/paas/v4", "openai"),
        )
    }

    @Test
    fun fullEndpointIsNotAppendedTwice() {
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            AiRuleGenerator.resolveEndpoint("https://open.bigmodel.cn/api/paas/v4/chat/completions", "openai"),
        )
    }

    @Test
    fun baseUrlGetsChatCompletionsOrMessages() {
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            AiRuleGenerator.resolveEndpoint("https://open.bigmodel.cn/api/paas/v4", "openai"),
        )
        // 智谱的 Anthropic 兼容端点和 Claude Code 一样是 /api/anthropic/v1/messages
        assertEquals(
            "https://open.bigmodel.cn/api/anthropic/v1/messages",
            AiRuleGenerator.resolveEndpoint("https://open.bigmodel.cn/api/anthropic", "anthropic"),
        )
    }

    @Test
    fun messyInputIsRepaired() {
        // 少了 scheme、多个斜杠、全角符号、结尾句号都是常见粘贴错误
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4",
            AiRuleGenerator.fixApiUrl("open.bigmodel.cn//api//paas//v4/", "openai"),
        )
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4",
            AiRuleGenerator.fixApiUrl("https：//open.bigmodel.cn／api／paas／v4。", "openai"),
        )
        assertEquals(
            "https://api.openai.com/v1",
            AiRuleGenerator.fixApiUrl("  \"api.openai.com/v1\"  ", "openai"),
        )
    }

    @Test
    fun repeatedVersionSegmentIsCollapsed() {
        assertEquals(
            "https://api.openai.com/v1",
            AiRuleGenerator.fixApiUrl("https://api.openai.com/v1/v1", "openai"),
        )
    }

    @Test
    fun localServiceDefaultsToPlainHttp() {
        // Ollama 这类本机服务通常没有 TLS，默认 https 会直接连不上
        assertEquals(
            "http://localhost:11434/v1",
            AiRuleGenerator.fixApiUrl("localhost:11434", "openai"),
        )
        assertEquals(
            "http://192.168.1.10:8000/v1",
            AiRuleGenerator.fixApiUrl("192.168.1.10:8000/v1", "openai"),
        )
    }

    @Test
    fun modelListEndpointFallsBackToBaseUrl() {
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/models",
            AiRuleGenerator.resolveModelsEndpoint("https://open.bigmodel.cn/api/paas/v4/chat/completions", "openai"),
        )
    }

    @Test
    fun zhipuConfigPassesValidationAfterNormalization() {
        assertNull(AiRuleGenerator.validateAiConfig(config("open.bigmodel.cn")))
    }

    @Test
    fun unsupportedOrMismatchedEndpointsAreReported() {
        assertNotNull(AiRuleGenerator.validateAiConfig(config("https://api.openai.com/v1/responses")))
        assertNotNull(
            AiRuleGenerator.validateAiConfig(config("https://open.bigmodel.cn/api/anthropic", "openai")),
        )
        assertNotNull(
            AiRuleGenerator.validateAiConfig(config("https://api.openai.com/v1/chat/completions", "anthropic")),
        )
        // 智谱有 Anthropic 端点，但填的是 OpenAI 路径
        assertNotNull(
            AiRuleGenerator.validateAiConfig(config("https://open.bigmodel.cn/api/paas/v4", "anthropic")),
        )
        // DeepSeek 没有 Anthropic 兼容端点
        assertNotNull(
            AiRuleGenerator.validateAiConfig(config("https://api.deepseek.com/v1", "anthropic")),
        )
        // 智谱旧版 /v3 不是 OpenAI 兼容接口
        assertNotNull(
            AiRuleGenerator.validateAiConfig(config("https://open.bigmodel.cn/api/paas/v3")),
        )
    }
}
