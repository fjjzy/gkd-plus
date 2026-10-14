package li.gkd.app.util

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.readUTF8Line
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import li.gkd.app.app
import li.gkd.app.appScope
import li.gkd.app.data.RawSubscription
import li.gkd.app.data.settings.AiConfig
import li.gkd.app.data.snapshot.SnapshotRepository
import li.gkd.app.data.subscription.SubscriptionRepository
import li.gkd.app.feature.ai.AiConversation
import li.gkd.app.feature.ai.AiConversationStore
import li.gkd.app.feature.ai.toGenerateConfig
import li.gkd.app.feature.ai.AiMessage
import li.gkd.app.store.AppStore
import li.gkd.app.store.AppStore.storeFlow
import li.gkd.db.LOCAL_SUBS_ID
import java.util.concurrent.ConcurrentHashMap
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
    val max_tokens: Int = 8192,
    val stream: Boolean = false,
)

@Serializable
data class AnthropicRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val system: String? = null,
    val temperature: Float = 0f,
    val top_p: Float = 1f,
    val max_tokens: Int = 8192,
    val stream: Boolean = false,
)

/** AI 接口返回非 2xx 时的异常，携带状态码与响应体，便于给出可读提示 */
class AiApiException(
    val statusCode: Int,
    val responseBody: String,
) : Exception("HTTP $statusCode ${responseBody.take(600)}")

/**
 * 一次 AI 回复。部分思考模型会把内容放在 reasoning_content 里，
 * 且思考内容同样消耗 max_tokens，因此需要单独记录以便给出准确提示。
 */
data class AiReply(
    val content: String,
    val reasoning: String,
    val finishReason: String?,
)

private fun JsonElement?.strOrNull(): String? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    return primitive.content
}

/** 把思考等额外参数合并进请求体；空 Map 时原样返回 */
private fun JsonObject.mergeParams(extra: Map<String, JsonElement>): JsonObject =
    if (extra.isEmpty()) this else JsonObject(this + extra)

private fun HttpRequestBuilder.applyAiAuth(config: AiConfig) {
    when (config.protocol) {
        "anthropic" -> {
            header("x-api-key", config.apiKey)
            header("anthropic-version", "2023-06-01")
        }
        else -> header("Authorization", "Bearer ${config.apiKey}")
    }
}

/** 一次 AI 调用的输入: 配置、系统提示词与历史消息总是一起传递 */
private data class AiCallRequest(
    val config: AiConfig,
    val system: String?,
    val messages: List<ChatMessage>,
)

object AiRuleGenerator {

    /** 每个会话当前在跑的生成任务；键在任务结束时移除 */
    private val runningJobs = ConcurrentHashMap<Long, Job>()

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
        // 用户可能已经把完整端点粘进来了（此时 fixApiUrl 会原样返回），不能再拼一次
        val suffix = if (config.protocol == "anthropic") "/messages" else "/chat/completions"
        return if (baseUrl.endsWith(suffix)) baseUrl else baseUrl + suffix
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

    /** Anthropic 要求 system 单独传递，且相邻同角色消息需要合并、首条必须是 user */
    private fun toAnthropicMessages(messages: List<ChatMessage>): List<ChatMessage> {
        val result = mutableListOf<ChatMessage>()
        messages.filter { it.role != "system" }.forEach { message ->
            val last = result.lastOrNull()
            if (last != null && last.role == message.role) {
                result[result.size - 1] = last.copy(content = last.content + "\n\n" + message.content)
            } else {
                result.add(message)
            }
        }
        if (result.firstOrNull()?.role == "assistant") {
            result.add(0, ChatMessage("user", "（继续）"))
        }
        return result
    }

    private fun buildRequestBody(
        request: AiCallRequest,
        maxTokensOverride: Int? = null,
        stream: Boolean = false,
    ): String {
        val config = request.config
        val messages = request.messages
        val system = request.system
        val maxTokens = maxTokensOverride ?: config.maxTokens
        // 思考参数各家字段不同，由 AiThinkingUtils 按模型名翻译；空表示不发送
        val thinkingParams = AiThinkingUtils.buildParams(config)
        // 实际发出去的思考字段是最容易被服务端静默忽略/拒绝的地方，必须留痕
        LogUtils.d(
            "AI thinking params: model=${config.model}, level=${config.thinkingLevel}," +
                " sent=${if (thinkingParams.isEmpty()) "(none)" else JsonObject(thinkingParams)}"
        )
        // 注意不能只看「有没有 thinking 字段」：关闭档也会发 thinking（disabled），
        // 那种情况温度不该被顶成 1
        val thinkingOn = thinkingParams["thinking"]?.jsonObject?.get("type").strOrNull() == "enabled"
        return when (config.protocol) {
            "anthropic" -> {
                val request = AnthropicRequest(
                    model = config.model,
                    system = system,
                    messages = toAnthropicMessages(messages),
                    // Anthropic 开启 extended thinking 时 temperature 只能是 1
                    temperature = if (thinkingOn) 1f else config.temperature,
                    top_p = config.topP,
                    max_tokens = maxTokens,
                    stream = stream,
                )
                json.encodeToString(
                    JsonObject.serializer(),
                    json.encodeToJsonElement(AnthropicRequest.serializer(), request)
                        .jsonObject.mergeParams(thinkingParams),
                )
            }
            else -> {
                val request = ChatRequest(
                    model = config.model,
                    messages = buildList {
                        if (!system.isNullOrBlank()) add(ChatMessage("system", system))
                        addAll(messages)
                    },
                    temperature = config.temperature,
                    top_p = config.topP,
                    max_tokens = maxTokens,
                    stream = stream,
                )
                json.encodeToString(
                    JsonObject.serializer(),
                    json.encodeToJsonElement(ChatRequest.serializer(), request)
                        .jsonObject.mergeParams(thinkingParams),
                )
            }
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
            msg.contains("model_not_found") || msg.contains("No available channel", ignoreCase = true) ||
                msg.contains("无可用") ->
                "模型不可用：服务方没有这个模型名，或该 Key 无权限使用它（换一个模型试试）"
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
            msg.contains("max_tokens", ignoreCase = true) || msg.contains("max_output_tokens", true) ||
                msg.contains("max tokens", true) || msg.contains("maximum context", true) ->
                "服务端不接受这个 Max Tokens（超过该模型的上限）：请在 AI 规则设置里调小 Max Tokens"
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
            msg.startsWith("HTTP 5") || msg.contains("500") || msg.contains("502") ||
                msg.contains("503") || msg.contains("504") ->
                "服务方返回错误：$msg"
            else -> "连接失败：请检查网络、API 地址、Key 和模型配置"
        }
    }

    /**
     * 把完整报错写进剪贴板（不弹提示，toast 交给调用方说「看得懂的那句」）。
     * 服务方的原始报文往往很长又夹着 request id，只有拿去问 AI 或提 issue 才有用。
     */
    fun copyFailureToClipboard(title: String, e: Throwable, reason: String) {
        val detail = e.message ?: e.toString()
        runCatching { ToastUtils.copyTextSilently("$title：\n$detail\n\n分析：$reason") }
    }

    /**
     * 生成失败的统一反馈：toast 只给可读原因，完整报错进剪贴板。返回原因，供会话消息复用。
     */
    fun reportAiFailure(e: Throwable): String {
        val reason = analyzeConnectionError(e)
        copyFailureToClipboard("AI 规则生成失败", e, reason)
        ToastUtils.toast("生成失败：$reason\n（完整报错已复制，可粘贴去问其它 AI）")
        return reason
    }

    suspend fun testConnection(config: AiConfig): Result<String> = runCatching {
        val httpClient = createHttpClient()
        try {
            val endpoint = buildApiEndpoint(config)
            val response = httpClient.post(endpoint) {
                applyAiAuth(config)
                contentType(ContentType.Application.Json)
                setBody(
                    buildRequestBody(
                        request = AiCallRequest(
                            config = config,
                            system = null,
                            messages = listOf(ChatMessage("user", "hi")),
                        ),
                        maxTokensOverride = 1,
                    )
                )
            }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw AiApiException(response.status.value, body)
            }
            body
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
                applyAiAuth(config)
            }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw AiApiException(response.status.value, body)
            }
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

    /**
     * 会话式生成：在会话中追加一条用户消息后调用。
     * 使用 system（规则知识 + 当前快照）+ 真实多轮历史，
     * 模型可以正常对话；只有确实给出规则时才附上 json 代码块。
     * 在 appScope 中运行，退出前台不会失败；每个快照一个会话，可并行。
     *
     * [notifyUser]：从「直接生成」这类不落在会话页的入口发起时为 true，
     * 用 toast 反馈开始与结束；在会话页里发起时由页面自身的状态提示，无需 toast。
     * [autoAccept]：自动来源（无障碍自动截图 / 音量键）为 true，产出规则后直接入库并标记已加入；
     * 手动发起为 false，等待用户在会话里点「加入规则」。
     */
    suspend fun generateConversationRule(
        snapshotId: Long,
        notifyUser: Boolean = false,
        autoAccept: Boolean = false,
    ) {
        val config = tunedConfig()
        if (config.apiUrl.isBlank() || config.apiKey.isBlank() || config.model.isBlank()) {
            AiConversationStore.appendMessage(
                snapshotId = snapshotId,
                message = AiMessage(
                    role = "assistant",
                    content = "请先在 AI 规则设置里填写 API 地址、Key 和模型。",
                    time = System.currentTimeMillis(),
                ),
                status = AiConversation.STATUS_FAILED,
            )
            return
        }
        if (AiConversationStore.get(snapshotId) == null) {
            LogUtils.d("AI conversation missing: $snapshotId")
            return
        }
        if (notifyUser) {
            ToastUtils.toast("AI 正在生成规则...", forced = true)
        }
        try {
            AiConversationStore.updateStatus(snapshotId, AiConversation.STATUS_GENERATING)
            // 记下本次生成用的参数（不含 apiKey），随对话一起导出用于反馈
            AiConversationStore.setGenerateConfig(snapshotId, config.toGenerateConfig())
            LogUtils.d(
                "AI generate start: protocol=${config.protocol}, model=${config.model}," +
                    " temperature=${config.temperature}, topP=${config.topP}," +
                    " maxTokens=${config.maxTokens}, thinkingLevel=${config.thinkingLevel}," +
                    " autoAccept=$autoAccept"
            )
            val prompt = loadPrompt()
            val snapshotJson = withContext(Dispatchers.IO) {
                SnapshotRepository.snapshotFile(snapshotId).readText()
            }
            // 选中的目标组件作为会话级上下文放进 system 消息（格式与 prompt 的「上下文说明」一致）
            val selected = AiConversationStore.get(snapshotId)?.selectedComponents.orEmpty()
            val componentsBlock = buildString {
                append("\n\n## 用户当前选中的组件\n")
                if (selected.isEmpty()) {
                    append("（用户当前没有选中任何组件）\n")
                } else {
                    selected.forEachIndexed { index, c ->
                        append("${index + 1}. 文本: ${c.text?.let { "\"$it\"" } ?: "null"} | ")
                        append("描述: ${c.desc?.let { "\"$it\"" } ?: "null"} | ")
                        append("id: ${c.vid ?: "null"} | ")
                        append("类名: ${c.name ?: "null"} | ")
                        append("可点击: ${c.clickable} | ")
                        append("可长按: ${c.longClickable} | ")
                        append("区域: ${c.bounds}\n")
                    }
                }
            }
            val system = buildString {
                append(prompt)
                append("\n\n## 当前快照（快照 id: ")
                append(snapshotId)
                append("）\n")
                append(snapshotJson)
                append(componentsBlock)
            }
            val history = AiConversationStore.get(snapshotId)?.messages.orEmpty()
                .filter { it.role == "user" || it.role == "assistant" }
                .map { ChatMessage(role = it.role, content = it.content) }
                .let { messages ->
                    if (messages.none { it.role == "user" }) {
                        listOf(ChatMessage("user", "请为该快照生成关闭/点击规则。")) + messages
                    } else {
                        messages
                    }
                }
            // 流式生成：每个 delta 追加到会话最后一条 assistant 消息（实时渲染）
            // 被截断时用更大预算重试，重试前丢弃上一次的残留内容，避免拼接
            val reply = callAiApiStreamWithMaxTokensTuning(
                request = AiCallRequest(config = config, system = system, messages = history),
                onRetry = { AiConversationStore.dropLastMessage(snapshotId) },
                onReasoning = { delta ->
                    AiConversationStore.appendReasoningToLastMessage(snapshotId, delta)
                },
            ) { delta ->
                AiConversationStore.appendToLastMessage(snapshotId, delta)
            }
            val content = reply.content.trim()
            if (content.isEmpty()) {
                failConversation(snapshotId, emptyReplyReason(reply))
                return
            }
            val ruleText = extractRuleJson(content)
            val rule = ruleText?.let { parseAndValidateRule(it) }
            if (rule != null) {
                if (autoAccept) {
                    // 自动来源：直接入库并标记「已加入」，用户在工作台能看到这轮对话和结果
                    insertRuleToSubscription(rule)
                    LogUtils.d("AI rule accepted: snapshotId=$snapshotId, ruleChars=${ruleText.length}")
                    AiConversationStore.appendMessage(
                        snapshotId = snapshotId,
                        message = AiMessage(
                            role = "assistant",
                            content = "已加入本地规则。",
                            time = System.currentTimeMillis(),
                        ),
                        status = AiConversation.STATUS_ACCEPTED,
                    )
                    if (notifyUser) {
                        ToastUtils.toast("AI 规则已生成并加入本地规则")
                    }
                } else {
                    // 不自动入库：等待用户在会话里点「加入规则」
                    AiConversationStore.updateStatus(
                        snapshotId,
                        AiConversation.STATUS_RULE_READY,
                        lastRule = ruleText,
                    )
                    if (notifyUser) {
                        ToastUtils.toast("AI 规则已生成，可在 AI 规则工作台查看")
                    }
                }
            } else {
                // 只是普通对话回复，没有规则，属于正常情况
                AiConversationStore.updateStatus(snapshotId, AiConversation.STATUS_SUCCESS)
                if (notifyUser) {
                    ToastUtils.toast("AI 已回复，可在 AI 规则工作台查看")
                }
            }
        } catch (e: CancellationException) {
            // 用户点了停止：保留已经流式写进去的内容，状态回到可继续发送
            LogUtils.d("AI conversation generate cancelled: $snapshotId")
            AiConversationStore.updateStatus(snapshotId, AiConversation.STATUS_IDLE)
            throw e
        } catch (e: Exception) {
            LogUtils.d("AI conversation generate failed", e)
            val reason = reportAiFailure(e)
            val detail = e.message ?: e.toString()
            AiConversationStore.appendMessage(
                snapshotId = snapshotId,
                message = AiMessage(
                    role = "assistant",
                    content = "生成失败：$reason\n\n$detail",
                    time = System.currentTimeMillis(),
                ),
                status = AiConversation.STATUS_FAILED,
            )
        }
    }

    /**
     * 发起一轮生成，并按快照持有它的 Job。
     *
     * 页面退出不应该中断生成（原来直接 launch 在 appScope），但用户要能主动停止，
     * 所以 Job 必须由这里持有；同时它也是「这个会话是否真的在跑」的唯一依据——
     * 进程被杀后残留的 generating 状态就是靠它识别的。
     */
    private fun launchConversationRule(
        snapshotId: Long,
        notifyUser: Boolean = false,
        autoAccept: Boolean = false,
        beforeGenerate: suspend () -> Unit = {},
    ) {
        if (runningJobs[snapshotId]?.isActive == true) return
        runningJobs[snapshotId] = appScope.launchLogged {
            try {
                beforeGenerate()
                generateConversationRule(snapshotId, notifyUser = notifyUser, autoAccept = autoAccept)
            } finally {
                runningJobs.remove(snapshotId)
            }
        }
    }

    /** 手动发起（会话页发送、快照记录「直接生成」等） */
    fun startConversationRule(snapshotId: Long, notifyUser: Boolean = false) =
        launchConversationRule(snapshotId, notifyUser = notifyUser)

    /**
     * 自动来源（无障碍自动截图 / 音量键）触发的识别：
     * 会话已由 SnapshotCapture 建好，这里补一条发起消息，生成后直接加入规则。
     */
    fun startAutoRule(snapshotId: Long) = launchConversationRule(
        snapshotId = snapshotId,
        notifyUser = true,
        autoAccept = true,
        beforeGenerate = {
            AiConversationStore.appendMessage(
                snapshotId = snapshotId,
                message = AiMessage(
                    role = "user",
                    content = "请为该快照生成关闭/点击规则",
                    time = System.currentTimeMillis(),
                ),
            )
        },
    )

    /** 用户点「停止」：取消这一轮的生成 */
    fun stopConversationRule(snapshotId: Long) {
        runningJobs.remove(snapshotId)?.cancel()
    }

    /** 该会话当前是否真的有生成任务在跑（进程重启后一定是 false） */
    fun isConversationRunning(snapshotId: Long): Boolean =
        runningJobs[snapshotId]?.isActive == true

    private suspend fun failConversation(snapshotId: Long, reason: String) {
        ToastUtils.toast(reason)
        AiConversationStore.appendMessage(
            snapshotId = snapshotId,
            message = AiMessage(
                role = "assistant",
                content = reason,
                time = System.currentTimeMillis(),
            ),
            status = AiConversation.STATUS_FAILED,
        )
    }

    /** 模型返回空正文时，给出具体原因而不是笼统地说 JSON 不合法 */
    private fun emptyReplyReason(reply: AiReply): String = when {
        reply.finishReason == "length" ->
            "模型输出被 Max Tokens 截断（思考内容也占用额度）。可在 AI 规则设置的「高级设置」里关闭思考，或继续调大 Max Tokens。"
        reply.reasoning.isNotBlank() ->
            "模型只返回了思考内容、没有返回正文。可在 AI 规则设置的「高级设置」里把思考等级设为「关闭思考」，或换一个非思考模型。"
        else ->
            "模型没有返回任何内容（finish_reason=${reply.finishReason ?: "null"}）。请确认模型名称是否正确。"
    }

    /**
     * 用户点击「加入规则」：把上次生成的规则（conversation.lastRule）真正写入本地订阅。
     */
    suspend fun acceptGeneratedRule(snapshotId: Long) {
        val conversation = AiConversationStore.get(snapshotId) ?: return
        val ruleText = conversation.lastRule
        if (conversation.status != AiConversation.STATUS_RULE_READY || ruleText.isNullOrBlank()) {
            return
        }
        val rule = parseAndValidateRule(ruleText)
        if (rule == null) {
            ToastUtils.toast("规则数据已失效，请重新生成")
            AiConversationStore.updateStatus(snapshotId, AiConversation.STATUS_FAILED)
            return
        }
        insertRuleToSubscription(rule)
        AiConversationStore.appendMessage(
            snapshotId = snapshotId,
            message = AiMessage(
                role = "assistant",
                content = "已加入本地规则。",
                time = System.currentTimeMillis(),
            ),
            status = AiConversation.STATUS_SUCCESS,
        )
        ToastUtils.toast("规则已加入本地订阅")
    }

    /**
     * 流式调用 OpenAI 兼容接口（SSE），逐 delta 回调。
     * 若接口不支持流式（返回整体 JSON / 代理剥离 SSE），回退到非流式；
     * 但 HTTP 层面的错误会直接抛出，不会被当成"内容为空"。
     */
    private suspend fun callAiApiStream(
        request: AiCallRequest,
        maxTokensOverride: Int? = null,
        onReasoning: suspend (String) -> Unit = {},
        onDelta: suspend (String) -> Unit = {},
    ): AiReply {
        val streamed = runCatching {
            streamOnce(request, maxTokensOverride, onReasoning, onDelta)
        }
        val streamError = streamed.exceptionOrNull()
        if (streamError is AiApiException) {
            throw streamError
        }
        val result = streamed.getOrNull()
        if (result != null &&
            (result.content.isNotBlank() || result.reasoning.isNotBlank() || result.finishReason != null)
        ) {
            return result
        }
        LogUtils.d("AI stream unavailable, fallback to non-stream: ${streamError?.message}")
        val text = callAiApi(request, maxTokensOverride)
        return AiReply(content = text, reasoning = "", finishReason = null)
    }

    /**
     * 首次请求被拒时按错误类型降级重试，并把有效的配置**永久**写回设置，
     * 免得用户每次生成都重新撞一次同样的墙。三条路径：
     *
     * - 服务端不认思考参数 → 改成「不发送」重试，成功后记住。
     * - 服务端不接受当前 Max Tokens → 降到保守值重试，成功后记住。
     * - 输出被 Max Tokens 截断 → 用更大预算重试，成功后记住。
     *
     * 一律**先重试、成功才写回**：失败时留下一个坏配置，会让之后每次生成都失败。
     */
    private suspend fun callAiApiStreamWithMaxTokensTuning(
        request: AiCallRequest,
        onRetry: suspend () -> Unit = {},
        onReasoning: suspend (String) -> Unit = {},
        onDelta: suspend (String) -> Unit = {},
    ): AiReply {
        val config = request.config
        val first = try {
            callAiApiStream(
                request = request,
                onReasoning = onReasoning,
                onDelta = onDelta,
            )
        } catch (e: AiApiException) {
            // 服务端不认我们发的思考参数：改成什么都不发，并记住这个模型不能发
            if (AiThinkingUtils.buildParams(config).isNotEmpty() && AiThinkingUtils.isThinkingRejection(e)) {
                ToastUtils.toast("服务端不接受思考参数，已改为不发送并保存")
                val retried = callAiApiStream(
                    request = request.copy(
                        config = config.copy(thinkingLevel = AiThinkingLevelOption.None.value),
                    ),
                    onReasoning = onReasoning,
                    onDelta = onDelta,
                )
                persistThinkingLevel(AiThinkingLevelOption.None.value)
                return retried
            }
            // 服务端上限比配置低：降到保守值，并记住这个值被拒过
            if (config.maxTokens <= AiMaxTokensUtils.FALLBACK_MAX_TOKENS || !isMaxTokensRejection(e)) throw e
            LogUtils.d(
                "AI maxTokens rejected by server: ${config.maxTokens}" +
                    " -> ${AiMaxTokensUtils.FALLBACK_MAX_TOKENS}"
            )
            ToastUtils.toast(
                "服务端不接受 ${config.maxTokens} Max Tokens，已改用 ${AiMaxTokensUtils.FALLBACK_MAX_TOKENS}"
            )
            val retried = callAiApiStream(
                request = request,
                maxTokensOverride = AiMaxTokensUtils.FALLBACK_MAX_TOKENS,
                onReasoning = onReasoning,
                onDelta = onDelta,
            )
            persistMaxTokens(AiMaxTokensUtils.FALLBACK_MAX_TOKENS, onlyRaise = false)
            persistMaxTokensCeiling(config.maxTokens)
            return retried
        }
        if (first.finishReason != "length") return first
        val next = AiMaxTokensUtils.nextBudget(config)
        LogUtils.d(
            "AI output truncated: maxTokens=${config.maxTokens}," +
                " ceiling=${config.maxTokensCeiling}, next=$next"
        )
        if (next == null) {
            val reason = if (config.maxTokensCeiling > 0) {
                "服务端上限已知（${config.maxTokensCeiling} 曾被拒）"
            } else {
                "已达上限 ${AiMaxTokensUtils.MAX_TOKENS_CEILING}"
            }
            ToastUtils.toast("输出被截断，Max Tokens 不再上调：$reason")
            return first
        }
        ToastUtils.toast("输出被截断，已用 $next Max Tokens 重试")
        onRetry()
        val retried = try {
            callAiApiStream(
                request = request,
                maxTokensOverride = next,
                onReasoning = onReasoning,
                onDelta = onDelta,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogUtils.d("AI truncation retry failed", e)
            if (isMaxTokensRejection(e)) {
                ToastUtils.toast("服务端不接受 $next Max Tokens，已保留 ${config.maxTokens}")
            }
            first
        }
        if (retried.finishReason != "length" && retried.content.isNotBlank()) {
            persistMaxTokens(next, onlyRaise = true)
            ToastUtils.toast("已把 Max Tokens 调整为 $next 并保存")
        }
        return retried
    }

    /** 把 Max Tokens 写回设置；[onlyRaise] 为 true 时不覆盖用户已调得更大的值 */
    private fun persistMaxTokens(value: Int, onlyRaise: Boolean) {
        runCatching {
            AppStore.updateSettings {
                val current = it.aiConfig.maxTokens
                val next = if (onlyRaise && current >= value) current else value
                if (next == current) it else it.copy(aiConfig = it.aiConfig.copy(maxTokens = next))
            }
        }.onFailure { LogUtils.d("persist max tokens failed", it) }
    }

    /** 把思考等级写回设置：服务端拒过一次之后，没必要每次都再发一遍 */
    private fun persistThinkingLevel(value: String) {
        runCatching {
            AppStore.updateSettings {
                if (it.aiConfig.thinkingLevel == value) it
                else it.copy(aiConfig = it.aiConfig.copy(thinkingLevel = value))
            }
        }.onFailure { LogUtils.d("persist thinking level failed", it) }
    }

    /** 记住被服务端拒过的 Max Tokens，抬底和上调都不再越过它 */
    private fun persistMaxTokensCeiling(rejected: Int) {
        runCatching {
            AppStore.updateSettings {
                val current = it.aiConfig.maxTokensCeiling
                val next = if (current in 1 until rejected) current else rejected
                if (next == current) it else it.copy(aiConfig = it.aiConfig.copy(maxTokensCeiling = next))
            }
        }.onFailure { LogUtils.d("persist max tokens ceiling failed", it) }
    }

    /**
     * 生成前把明显偏小的 Max Tokens 抬到下限并记住。
     * 用户不必先白跑一次被截断的生成，旧配置也会被自动纠正。
     */
    private fun tunedConfig(): AiConfig {
        val config = storeFlow.value.aiConfig
        val raised = AiMaxTokensUtils.raisedBudget(config) ?: return config
        LogUtils.d("AI maxTokens raised before generate: ${config.maxTokens} -> $raised")
        persistMaxTokens(raised, onlyRaise = true)
        return config.copy(maxTokens = raised)
    }

    /** 判断错误是否是「这个 Max Tokens 服务端不认」，而不是限流、鉴权之类的别的问题 */
    private fun isMaxTokensRejection(e: Throwable): Boolean {
        val msg = (e.message ?: "").lowercase()
        if (!msg.contains("token")) return false
        return msg.contains("max_tokens") || msg.contains("max_output_tokens") ||
            msg.contains("max tokens") || msg.contains("maximum context") ||
            msg.contains("too large") || msg.contains("exceeds")
    }

    private suspend fun streamOnce(
        request: AiCallRequest,
        maxTokensOverride: Int?,
        onReasoning: suspend (String) -> Unit,
        onDelta: suspend (String) -> Unit,
    ): AiReply {
        val config = request.config
        val httpClient = createHttpClient()
        try {
            val endpoint = buildApiEndpoint(config)
            val requestBody =
                buildRequestBody(request, maxTokensOverride, stream = true)
            val content = StringBuilder()
            val reasoning = StringBuilder()
            var finishReason: String? = null
            httpClient.preparePost(endpoint) {
                applyAiAuth(config)
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    throw AiApiException(response.status.value, response.bodyAsText())
                }
                val bodyChannel = response.bodyAsChannel()
                while (!bodyChannel.isClosedForRead) {
                    val line = bodyChannel.readUTF8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val element = runCatching { json.parseToJsonElement(data).jsonObject }
                        .getOrNull() ?: continue
                    when (config.protocol) {
                        "anthropic" -> {
                            when (element["type"].strOrNull()) {
                                "content_block_delta" -> {
                                    val deltaObj = element["delta"]?.jsonObject
                                    // 思考增量是 thinking_delta、正文是 text_delta，字段名不同；
                                    // 只判断 "thinking" 会把思考全丢掉
                                    if (deltaObj?.get("type").strOrNull() == "thinking_delta") {
                                        val thinking = deltaObj?.get("thinking").strOrNull()
                                        if (!thinking.isNullOrEmpty()) {
                                            reasoning.append(thinking)
                                            onReasoning(thinking)
                                        }
                                    } else if (deltaObj != null) {
                                        val text = deltaObj["text"].strOrNull()
                                        if (!text.isNullOrEmpty()) {
                                            content.append(text)
                                            onDelta(text)
                                        }
                                    }
                                }
                                // stop_reason 只在 message_delta 里；不读就永远不知道是否被 max_tokens
                                // 截断，「加大预算重试」在 Anthropic 协议下会完全失效
                                "message_delta" -> {
                                    val stop = element["delta"]?.jsonObject?.get("stop_reason").strOrNull()
                                    if (stop != null) {
                                        finishReason = if (stop == "max_tokens") "length" else stop
                                    }
                                }
                            }
                        }
                        else -> {
                            val choice = element["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                                ?: continue
                            choice["finish_reason"].strOrNull()?.let { finishReason = it }
                            val delta = choice["delta"]?.jsonObject ?: continue
                            delta["reasoning_content"].strOrNull()?.let {
                                reasoning.append(it)
                                onReasoning(it)
                            }
                            val text = delta["content"].strOrNull()
                            if (!text.isNullOrEmpty()) {
                                content.append(text)
                                onDelta(text)
                            }
                        }
                    }
                }
            }
            return AiReply(
                content = content.toString(),
                reasoning = reasoning.toString(),
                finishReason = finishReason,
            )
        } finally {
            httpClient.close()
        }
    }

    private suspend fun callAiApi(
        request: AiCallRequest,
        maxTokensOverride: Int? = null,
    ): String {
        val config = request.config
        val endpoint = buildApiEndpoint(config)
        val requestBody = buildRequestBody(request, maxTokensOverride)
        LogUtils.d("AI Request endpoint: $endpoint")
        val httpClient = createHttpClient()
        try {
            val response = httpClient.post(endpoint) {
                applyAiAuth(config)
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw AiApiException(response.status.value, body)
            }
            LogUtils.d("AI Response (first 3000 chars): ${body.take(3000)}")
            val jsonElement = json.parseToJsonElement(body)

            return when (config.protocol) {
                "anthropic" -> {
                    val contentArr = jsonElement.jsonObject["content"]
                        ?: throw Exception("Anthropic 响应中没有 content 字段")
                    // 思考模型会先返回 thinking 块，正文在 text 块里；只看第一块会误判成「没有内容」
                    val text = contentArr.jsonArray.joinToString("") { block ->
                        runCatching {
                            val obj = block.jsonObject
                            if (obj["type"].strOrNull() == "text") obj["text"].strOrNull().orEmpty() else ""
                        }.getOrDefault("")
                    }
                    if (text.isEmpty()) throw Exception("Anthropic 响应中没有文本内容")
                    text
                }
                else -> {
                    val choices = jsonElement.jsonObject["choices"]
                        ?: throw Exception("OpenAI 响应中没有 choices 字段")
                    choices.jsonArray.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
                        ?: throw Exception("OpenAI 响应中没有内容")
                }
            }
        } finally {
            httpClient.close()
        }
    }

    /**
     * 从回复中提取规则 JSON：优先取最后一个 ```json 围栏代码块，
     * 其次要求整段回复本身就是 JSON。普通对话不会被误判成规则。
     */
    private fun extractRuleJson(text: String): String? {
        val trimmed = text.trim()
        val fenceRegex = Regex("```(?:json5?)?\\s*\\n?([\\s\\S]*?)\\n?```")
        val blocks = fenceRegex.findAll(trimmed).toList()
        for (match in blocks.asReversed()) {
            val body = match.groupValues[1].trim()
            if (body.startsWith("{") && body.endsWith("}")) {
                return body
            }
        }
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed
        }
        return null
    }

    private fun parseAndValidateRule(text: String): RawSubscription? {
        // First try parsing as full subscription
        try {
            val subs = RawSubscription.parse(text, json5 = true)
            if (subs.hasRule) return subs
        } catch (_: Exception) {}
        try {
            val subs = RawSubscription.parse(text, json5 = false)
            if (subs.hasRule) return subs
        } catch (_: Exception) {}

        // AI typically outputs an App-level JSON (id=packageName, groups=[...])
        // Wrap it into a subscription structure
        try {
            val jsonElement = json.parseToJsonElement(text)
            val app = RawSubscription.parseApp(jsonElement.jsonObject)
            LogUtils.d("AI parsed as RawApp: id=${app.id}, groups=${app.groups.size}")
            if (app.groups.isEmpty()) {
                return null
            }
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
