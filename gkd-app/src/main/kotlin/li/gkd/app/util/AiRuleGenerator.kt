package li.gkd.app.util

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.readUTF8Line
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import li.gkd.app.a11y.A11yContext
import li.gkd.app.a11y.A11yRuntime
import li.gkd.app.app
import li.gkd.app.appScope
import li.gkd.app.data.ActionPerformer
import li.gkd.app.data.GkdAction
import li.gkd.app.data.NodeInfo
import li.gkd.app.data.RawSubscription
import li.gkd.app.data.info2nodeList
import li.gkd.app.data.settings.AiConfig
import li.gkd.app.data.snapshot.SnapshotRepository
import li.gkd.app.data.subscription.SubscriptionRepository
import li.gkd.app.feature.ai.AiConversation
import li.gkd.app.feature.ai.AiConversationStore
import li.gkd.app.feature.ai.AiMessage
import li.gkd.app.snapshot.SnapshotCapture
import li.gkd.app.store.AppStore.storeFlow
import li.gkd.db.LOCAL_SUBS_ID
import li.gkd.selector.MatchOptions
import li.gkd.selector.Selector
import li.gkd.selector.SelectorCompileResult
import java.util.concurrent.TimeUnit

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Float = 0f,
    val top_p: Float = 1f,
    val max_tokens: Int = 4096,
)

@Serializable
data class AnthropicMessage(
    val role: String,
    val content: String,
)

@Serializable
data class AnthropicRequest(
    val model: String,
    val messages: List<AnthropicMessage>,
    val temperature: Float = 0f,
    val top_p: Float = 1f,
    val max_tokens: Int = 4096,
)

object AiRuleGenerator {

    val isGenerating = MutableStateFlow(false)
    val pendingCount = MutableStateFlow(0)

    private val taskQueue = Channel<Long>(Channel.UNLIMITED)

    init {
        appScope.launchLogged {
            for (snapshotId in taskQueue) {
                processRule(snapshotId)
            }
        }
    }

    private var cachedPrompt: String? = null

    private suspend fun loadPrompt(): String = withContext(Dispatchers.IO) {
        cachedPrompt?.let { return@withContext it }
        val text = app.assets.open("gkd-rule-generator-prompt.md").bufferedReader().readText()
        cachedPrompt = text
        text
    }

    fun fixApiUrl(url: String, protocol: String): String {
        var fixed = url.trim().trimEnd('/')
        // If URL already contains a full path, don't modify
        if (fixed.contains("/chat/completions") || fixed.contains("/messages")) {
            return fixed
        }
        // If URL already ends with /v1, keep it
        if (fixed.endsWith("/v1")) {
            return fixed
        }
        // If URL already contains /v1 somewhere, don't add it again
        if (fixed.contains("/v1/")) {
            return fixed
        }
        return "$fixed/v1"
    }

    private fun buildApiEndpoint(config: AiConfig): String {
        val baseUrl = fixApiUrl(config.apiUrl, config.protocol)
        return when (config.protocol) {
            "anthropic" -> "$baseUrl/messages"
            else -> "$baseUrl/chat/completions"
        }
    }

    private fun createHttpClient(): HttpClient {
        return HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(30, TimeUnit.SECONDS)
                    readTimeout(0, TimeUnit.SECONDS) // no timeout for AI response
                    writeTimeout(30, TimeUnit.SECONDS)
                }
            }
        }
    }

    private fun buildRequestBody(config: AiConfig, content: String, maxTokensOverride: Int? = null): String {
        val maxTokens = maxTokensOverride ?: config.maxTokens
        return when (config.protocol) {
            "anthropic" -> json.encodeToString(
                AnthropicRequest(
                    model = config.model,
                    messages = listOf(AnthropicMessage("user", content)),
                    temperature = config.temperature,
                    top_p = config.topP,
                    max_tokens = maxTokens,
                )
            )
            else -> json.encodeToString(
                ChatRequest(
                    model = config.model,
                    messages = listOf(ChatMessage("user", content)),
                    temperature = config.temperature,
                    top_p = config.topP,
                    max_tokens = maxTokens,
                )
            )
        }
    }

    /**
     * 常见配置错误的本地预检，返回错误提示；null 表示通过。
     */
    fun validateAiConfig(config: AiConfig): String? {
        val url = config.apiUrl.trim()
        val key = config.apiKey
        return when {
            !url.startsWith("http") -> "API 地址应以 http:// 或 https:// 开头"
            url.startsWith("http://") -> "API 地址建议使用 https"
            url.contains("//") && !url.startsWith("http://") && !url.startsWith("https://") ->
                "API 地址中有多余斜杠，请检查"
            url.contains("/v1/v1") -> "API 地址中 /v1 重复（地址本身可能已自带 /v1）"
            key != key.trim() -> "API Key 前后有空白字符，已自动去除"
            key.contains(' ') -> "API Key 中含空格，绝大多数服务方 Key 不含空格"
            config.model.isBlank() -> "模型名称为空"
            else -> null
        }
    }

    /**
     * 分析连接测试失败的常见原因，给用户一个可读的提示。
     */
    fun analyzeConnectionError(e: Throwable): String {
        val msg = e.message ?: e.toString()
        return when {
            msg.contains("401") || msg.contains("403") ||
                msg.contains("Unauthorized", ignoreCase = true) ||
                msg.contains("Forbidden", ignoreCase = true) ->
                "鉴权失败（401/403）：API Key 填错了，或该 Key 无权访问此模型"
            msg.contains("402") ->
                "服务方返回 402：账号余额不足或未开通付费"
            msg.contains("429") ->
                "请求被限流（429）：额度用完或请求太频繁，稍后再试"
            msg.contains("404") ->
                "接口不存在（404）：API 地址或协议选错了"
            msg.contains("model", ignoreCase = true) &&
                (msg.contains("not found", true) || msg.contains("does not exist", true) ||
                    msg.contains("deactivated", true)) ->
                "模型名配置错误：服务方没有此模型，或该 Key 无权使用此模型"
            msg.contains("timed out", ignoreCase = true) || msg.contains("timeout", true) ->
                "连接超时：网络不通，或 API 地址写错"
            msg.contains("Unable to resolve host", ignoreCase = true) ->
                "域名解析失败：API 地址可能拼写错误，或当前网络无法访问"
            msg.contains("SSL") || msg.contains("certificate", true) ->
                "SSL 证书错误：API 地址应为 https 开头"
            else -> "连接失败：请检查网络、API 地址、Key 和模型配置"
        }
    }

    suspend fun testConnection(config: AiConfig): Result<String> = runCatching {
        val httpClient = createHttpClient()
        try {
            val endpoint = buildApiEndpoint(config)
            val response = httpClient.post(endpoint) {
                when (config.protocol) {
                    "anthropic" -> {
                        header("x-api-key", config.apiKey)
                        header("anthropic-version", "2023-06-01")
                    }
                    else -> header("Authorization", "Bearer ${config.apiKey}")
                }
                contentType(ContentType.Application.Json)
                setBody(buildRequestBody(config, "hi", maxTokensOverride = 1))
            }
            response.bodyAsText()
        } finally {
            httpClient.close()
        }
    }

    suspend fun fetchModelList(config: AiConfig): Result<List<String>> = runCatching {
        val baseUrl = fixApiUrl(config.apiUrl, config.protocol)
        val modelsEndpoint = "$baseUrl/models"
        val httpClient = createHttpClient()
        try {
            val response = httpClient.get(modelsEndpoint) {
                when (config.protocol) {
                    "anthropic" -> {
                        header("x-api-key", config.apiKey)
                        header("anthropic-version", "2023-06-01")
                    }
                    else -> header("Authorization", "Bearer ${config.apiKey}")
                }
            }
            val body = response.bodyAsText()
            val jsonElement = json.parseToJsonElement(body)
            val data = jsonElement.jsonObject["data"]
                ?: throw Exception("No data field in response")
            data.jsonArray.mapNotNull { element ->
                try {
                    element.jsonObject["id"]?.jsonPrimitive?.content
                } catch (_: Exception) {
                    null
                }
            }
        } finally {
            httpClient.close()
        }
    }

    suspend fun generateRule(snapshotId: Long) {
        val config = storeFlow.value.aiConfig
        if (config.apiUrl.isBlank() || config.apiKey.isBlank() || config.model.isBlank()) {
            ToastUtils.toast("请先配置 AI 规则设置")
            return
        }
        pendingCount.value++
        taskQueue.send(snapshotId)
        if (isGenerating.value) {
            ToastUtils.toast("AI 任务已加入队列（排队中：${pendingCount.value}）")
        }
    }

    /**
     * 会话式生成：在工作台/选择器中对某个快照发起带用户要求的生成。
     * 在 appScope 中运行，退出前台不会失败；每个快照一个会话，可并行。
     */
    suspend fun generateConversationRule(snapshotId: Long, userRequirement: String?) {
        val config = storeFlow.value.aiConfig
        if (config.apiUrl.isBlank() || config.apiKey.isBlank() || config.model.isBlank()) {
            AiConversationStore.appendMessage(
                snapshotId = snapshotId,
                message = AiMessage(
                    role = "assistant",
                    content = "请先配置 AI 规则设置",
                    time = System.currentTimeMillis(),
                ),
                status = AiConversation.STATUS_FAILED,
            )
            return
        }
        val conversation = AiConversationStore.get(snapshotId)
        if (conversation == null) {
            LogUtils.d("AI conversation missing: $snapshotId")
            return
        }
        try {
            AiConversationStore.updateStatus(snapshotId, AiConversation.STATUS_GENERATING)
            val prompt = loadPrompt()
            val snapshotJson = withContext(Dispatchers.IO) {
                SnapshotRepository.snapshotFile(snapshotId).readText()
            }
            // 取会话中所有用户消息作为上下文 + 选中的目标组件
            val userContext = conversation.messages
                .filter { it.role == "user" }
                .joinToString("\n") { it.content }
            val componentsBlock = if (conversation.selectedComponents.isEmpty()) {
                ""
            } else {
                val lines = conversation.selectedComponents.mapIndexed { index, c ->
                    "${index + 1}. text=${c.text ?: "null"} desc=${c.desc ?: "null"} " +
                        "id=${c.vid ?: c.name ?: "null"} bounds=(${c.bounds})"
                }
                "\n---\n用户在快照中选中的目标组件（请针对这些组件生成规则）：\n" +
                    lines.joinToString("\n") + "\n"
            }
            val requirementBlock = buildString {
                if (componentsBlock.isNotBlank()) append(componentsBlock)
                if (userContext.isNotBlank()) {
                    append("\n---\n用户要求与历史：\n")
                    append(userContext)
                    append("\n")
                }
                if (userRequirement?.isNotBlank() == true) {
                    append("\n---\n本次要求：$userRequirement\n")
                }
            }
            val userContent = "$prompt$requirementBlock\n$snapshotJson"
            // 流式生成：每个 delta 追加到会话最后一条 assistant 消息（实时渲染）
            val result = callAiApiStream(config, userContent) { delta ->
                AiConversationStore.appendToLastMessage(snapshotId, delta)
            }
            val ruleText = extractContent(result)
            val rule = parseAndValidateRule(ruleText)
            if (rule == null) {
                AiConversationStore.appendMessage(
                    snapshotId = snapshotId,
                    message = AiMessage(
                        role = "assistant",
                        content = "生成的规则 JSON 不合法，请调整要求后重试。",
                        time = System.currentTimeMillis(),
                    ),
                    status = AiConversation.STATUS_FAILED,
                )
                return
            }
            insertRuleToSubscription(rule)
            AiConversationStore.updateStatus(
                snapshotId,
                AiConversation.STATUS_SUCCESS,
                lastRule = ruleText,
            )
        } catch (e: Exception) {
            LogUtils.d("AI conversation generate failed", e)
            val reason = analyzeConnectionError(e)
            // 完整错误进剪贴板，方便用户拿去问其它 AI
            val fullError = "AI 规则生成失败：\n${e.message ?: e.toString()}\n\n分析：$reason"
            runCatching { ToastUtils.copyText(fullError) }
            ToastUtils.toast("生成失败：$reason\n（完整错误已复制，可粘贴去问豆包）")
            AiConversationStore.appendMessage(
                snapshotId = snapshotId,
                message = AiMessage(
                    role = "assistant",
                    content = "生成失败：$reason\n（完整错误已复制到剪贴板）",
                    time = System.currentTimeMillis(),
                ),
                status = AiConversation.STATUS_FAILED,
            )
        }
    }

    private suspend fun processRule(snapshotId: Long) {
        val config = storeFlow.value.aiConfig
        isGenerating.value = true
        try {
            ToastUtils.toast("AI 正在生成规则...", forced = true)
            val prompt = loadPrompt()
            val snapshotJson = withContext(Dispatchers.IO) {
                SnapshotRepository.snapshotFile(snapshotId).readText()
            }
            val userContent = "$prompt\n$snapshotJson"
            LogUtils.d("AI generateRule: prompt length=${prompt.length}, snapshot length=${snapshotJson.length}, total=${userContent.length}")

            val result = callAiApi(config, userContent)
            LogUtils.d("AI extracted content (first 2000 chars): ${result.take(2000)}")
            val ruleText = extractContent(result)
            LogUtils.d("AI after extractContent (first 2000 chars): ${ruleText.take(2000)}")

            val currentRule = parseAndValidateRule(ruleText)
            if (currentRule == null) {
                ToastUtils.toast("AI 生成的规则 JSON 不合法，重试中...")
                val retryResult = callAiApi(config, userContent)
                val retryText = extractContent(retryResult)
                val retryParsed = parseAndValidateRule(retryText)
                if (retryParsed == null) {
                    ToastUtils.toast("AI 生成规则失败：JSON 解析错误")
                    return
                }
                insertRuleToSubscription(retryParsed)
            } else {
                insertRuleToSubscription(currentRule)
            }
            ToastUtils.toast("AI 规则生成成功并已添加到本地规则")
        } catch (e: Exception) {
            ToastUtils.toast("AI 生成规则失败：${e.message}")
            LogUtils.d("AI rule generation failed", e)
        } finally {
            pendingCount.value--
            isGenerating.value = pendingCount.value > 0
        }
    }





    /**
     * 流式调用 OpenAI 兼容接口（SSE），逐 delta 回调。
     * Anthropic 不支持同样的简化路径时由调用方回退非流式。
     */
    private suspend fun callAiApiStream(
        config: AiConfig,
        userContent: String,
        onDelta: suspend (String) -> Unit = {},
    ): String = runCatching {
        val httpClient = createHttpClient()
        try {
            val endpoint = buildApiEndpoint(config)
            val requestBody = when (config.protocol) {
                "anthropic" -> json.encodeToString(
                    AnthropicRequest(
                        model = config.model,
                        messages = listOf(AnthropicMessage("user", userContent)),
                        temperature = config.temperature,
                        top_p = config.topP,
                        max_tokens = config.maxTokens,
                    )
                )
                else -> {
                    val request = ChatRequest(
                        model = config.model,
                        messages = listOf(ChatMessage("user", userContent)),
                        temperature = config.temperature,
                        top_p = config.topP,
                        max_tokens = config.maxTokens,
                    )
                    val obj = json.parseToJsonElement(json.encodeToString(request))
                        .jsonObject.toMutableMap()
                    obj["stream"] = kotlinx.serialization.json.JsonPrimitive(true)
                    json.encodeToString(
                        kotlinx.serialization.json.JsonObject(obj)
                    )
                }
            }
            val full = StringBuilder()
            httpClient.preparePost(endpoint) {
                when (config.protocol) {
                    "anthropic" -> {
                        header("x-api-key", config.apiKey)
                        header("anthropic-version", "2023-06-01")
                    }
                    else -> header("Authorization", "Bearer ${config.apiKey}")
                }
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }.execute { response ->
                val bodyChannel = response.bodyAsChannel()
                while (!bodyChannel.isClosedForRead) {
                    val line = bodyChannel.readUTF8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val deltaText = runCatching {
                        val element = json.parseToJsonElement(data)
                        when (config.protocol) {
                            "anthropic" -> {
                                val type = element.jsonObject["type"]?.jsonPrimitive?.content
                                if (type == "content_block_delta") {
                                    element.jsonObject["delta"]?.jsonObject?.get("text")
                                        ?.jsonPrimitive?.content
                                } else {
                                    null
                                }
                            }
                            else -> {
                                element.jsonObject["choices"]?.jsonArray?.firstOrNull()
                                    ?.jsonObject?.get("delta")?.jsonObject?.get("content")
                                    ?.jsonPrimitive?.content
                            }
                        }
                    }.getOrNull() ?: continue
                    if (deltaText != null) {
                        full.append(deltaText)
                        onDelta(deltaText)
                    }
                }
            }
            full.toString()
        } finally {
            httpClient.close()
        }
    }.getOrElse { e ->
        // 不支持流式（接口不支持/代理剥离 SSE），回退整体返回
        LogUtils.d("AI stream failed, fallback to non-stream: ${e.message}")
        callAiApi(config, userContent)
    }

    private suspend fun callAiApi(config: AiConfig, userContent: String): String {
        val endpoint = buildApiEndpoint(config)
        val requestBody = buildRequestBody(config, userContent)
        LogUtils.d("AI Request endpoint: $endpoint")
        LogUtils.d("AI Request body (first 2000 chars): ${requestBody.take(2000)}")
        val httpClient = createHttpClient()
        try {
            val response = httpClient.post(endpoint) {
                when (config.protocol) {
                    "anthropic" -> {
                        header("x-api-key", config.apiKey)
                        header("anthropic-version", "2023-06-01")
                    }
                    else -> header("Authorization", "Bearer ${config.apiKey}")
                }
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }
            val body = response.bodyAsText()
            LogUtils.d("AI Response (first 3000 chars): ${body.take(3000)}")
            val jsonElement = json.parseToJsonElement(body)

            return when (config.protocol) {
                "anthropic" -> {
                    val contentArr = jsonElement.jsonObject["content"]
                        ?: throw Exception("No content in Anthropic response")
                    contentArr.jsonArray.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
                        ?: throw Exception("No text in Anthropic content")
                }
                else -> {
                    val choices = jsonElement.jsonObject["choices"]
                        ?: throw Exception("No choices in OpenAI response")
                    choices.jsonArray.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
                        ?: throw Exception("No content in OpenAI response")
                }
            }
        } finally {
            httpClient.close()
        }
    }

    private fun extractContent(text: String): String {
        var content = text.trim()
        // Try to extract JSON from markdown code block anywhere in the text
        val codeBlockRegex = Regex("```(?:json5?)?\\s*\\n?([\\s\\S]*?)\\n?```")
        val match = codeBlockRegex.find(content)
        if (match != null) {
            content = match.groupValues[1].trim()
        } else {
            // Remove markdown code block markers at start/end
            if (content.startsWith("```json5") || content.startsWith("```json") || content.startsWith("```")) {
                content = content.removePrefix("```json5").removePrefix("```json").removePrefix("```")
            }
            if (content.endsWith("```")) {
                content = content.removeSuffix("```")
            }
            content = content.trim()
        }
        // If content doesn't start with '{', try to find the first '{' and last '}'
        if (!content.startsWith("{")) {
            val firstBrace = content.indexOf('{')
            val lastBrace = content.lastIndexOf('}')
            if (firstBrace >= 0 && lastBrace > firstBrace) {
                content = content.substring(firstBrace, lastBrace + 1)
            }
        }
        return content
    }

    private fun parseAndValidateRule(text: String): RawSubscription? {
        // First try parsing as full subscription
        try {
            return RawSubscription.parse(text, json5 = true)
        } catch (_: Exception) {}
        try {
            return RawSubscription.parse(text, json5 = false)
        } catch (_: Exception) {}

        // AI typically outputs an App-level JSON (id=packageName, groups=[...])
        // Wrap it into a subscription structure
        try {
            val jsonElement = json.parseToJsonElement(text)
            val app = RawSubscription.parseApp(jsonElement.jsonObject)
            LogUtils.d("AI parsed as RawApp: id=${app.id}, groups=${app.groups.size}")
            // Build a minimal subscription containing this app
            return RawSubscription(
                id = -1L,
                name = "AI Generated",
                version = 1,
                apps = listOf(app),
            )
        } catch (e: Exception) {
            LogUtils.d("AI parse as app failed: ${e.message}")
            LogUtils.d("AI failed text (first 500 chars): ${text.take(500)}")
            return null
        }
    }

    private suspend fun insertRuleToSubscription(newSubs: RawSubscription) {
        val newApp = newSubs.apps.firstOrNull() ?: return
        SubscriptionRepository.update(LOCAL_SUBS_ID) { currentSubs ->
            val existingApp = currentSubs.apps.find { it.id == newApp.id }

            // Reassign group keys to avoid conflicts with existing groups
            val existingKeys = existingApp?.groups?.map { it.key }?.toSet() ?: emptySet()
            var nextKey = (existingKeys.maxOrNull() ?: -1) + 1
            val fixedGroups = newApp.groups.map { group ->
                val newKey = nextKey++
                group.copy(key = newKey)
            }
            val fixedApp = newApp.copy(groups = fixedGroups)

            if (existingApp != null) {
                val updatedApp = existingApp.copy(groups = existingApp.groups + fixedGroups)
                currentSubs.copy(
                    apps = currentSubs.apps.map { if (it.id == updatedApp.id) updatedApp else it }
                )
            } else {
                currentSubs.copy(
                    apps = (currentSubs.apps + fixedApp).filterIfNotAll { it.groups.isNotEmpty() }
                )
            }
        }
    }
}
