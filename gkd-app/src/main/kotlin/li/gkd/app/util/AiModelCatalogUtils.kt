package li.gkd.app.util

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * models.dev 的模型能力表，用来**补充** [AiThinkingUtils] 里按模型名写死的那张映射表。
 *
 * 它能告诉我们两件事：这个模型会不会思考、能不能关掉思考（有没有 toggle，
 * 或强度档位里含 `none`/`minimal`）。
 *
 * 注意它的能力边界：**不含请求字段名**，所以拼不出未知模型的参数——未知模型仍然只能靠
 * 「自定义 JSON」。因此这里只用于「内置表认不出时给用户一句解释」，不参与参数拼装。
 *
 * 拉取、解析、匹配任何一步失败都静默降级，绝不影响规则生成。
 */
object AiModelCatalogUtils {

    private const val CATALOG_URL = "https://models.dev/api.json"
    private const val REFRESH_TIMEOUT_MS = 15_000L

    /** 包含关系的覆盖度下限，避免 `glm` 这类短 id 把不相干的模型配进来 */
    private const val MIN_COVERAGE = 0.5f

    /** 代理名里的分隔符：厂商前缀、量化/免费后缀都从这里切开 */
    private val nameSeparators = Regex("""[/:@]""")

    /** 强度档位里代表「不思考」的取值 */
    private val noThinkingValues = setOf("none", "minimal")

    data class ModelCapability(
        val reasoning: Boolean,
        /** 能真正关掉思考：不思考，或有 toggle，或强度档位里有 none/minimal */
        val canDisable: Boolean,
        val effortValues: List<String>,
    )

    /** 拉到的能力表（key 是规范化后的模型 id）；null 表示还没拉到或拉取失败 */
    val catalogFlow: StateFlow<Map<String, ModelCapability>?>
        field = MutableStateFlow(null)

    private val refreshMutex = Mutex()
    private var refreshAttempted = false

    /**
     * 拉取并缓存能力表。无论成功失败都只尝试一次，失败不重试也不报错——
     * 这份数据只是锦上添花，缺了就用内置表。
     */
    suspend fun refresh() {
        refreshMutex.withLock {
            if (refreshAttempted) return
            refreshAttempted = true
            runCatching {
                val text = withTimeoutOrNull(REFRESH_TIMEOUT_MS) {
                    client.get(CATALOG_URL).bodyAsText()
                }
                if (text == null) {
                    LogUtils.d("models.dev 能力表拉取超时")
                    return@runCatching
                }
                val catalog = parseCatalog(text)
                LogUtils.d("models.dev 能力表已加载: ${catalog.size} 个模型")
                if (catalog.isNotEmpty()) catalogFlow.value = catalog
            }.onFailure { LogUtils.d("models.dev 能力表不可用", it) }
        }
    }

    /** 内置表认不出该模型时补一句说明；查不到或没拉到返回 null */
    fun describeSupplement(model: String): String? {
        val capability = lookup(model) ?: return null
        return when {
            !capability.reasoning -> "该模型不启用思考，无需设置"
            capability.canDisable -> "该模型支持开关思考，但内置表没识别出它的参数名；可以用「自定义 JSON」指定"
            capability.effortValues.isNotEmpty() ->
                "该模型不能关闭思考，只能调推理强度（${capability.effortValues.joinToString("/")}）；" +
                    "可以用「自定义 JSON」指定字段"
            else -> "该模型不能关闭思考，也没有可调档位"
        }
    }

    /**
     * 按模型名查能力。代理常改名（加厂商前缀、加 `-latest`/`:free` 之类后缀），所以依次尝试：
     * 整名精确匹配 → 按分隔符切开逐段精确匹配 → 包含关系取最长且覆盖度达标的一个。
     */
    fun lookup(model: String): ModelCapability? {
        val catalog = catalogFlow.value
        if (catalog.isNullOrEmpty() || model.isBlank()) return null
        catalog[normalize(model)]?.let { return it }
        nameSeparators.split(model)
            .map { normalize(it) }
            .filter { it.isNotEmpty() }
            .forEach { part -> catalog[part]?.let { return it } }
        val query = normalize(model)
        if (query.isEmpty()) return null
        return catalog.entries
            .mapNotNull { (key, capability) ->
                val coverage = when {
                    query.contains(key) -> key.length.toFloat() / query.length
                    key.contains(query) -> query.length.toFloat() / key.length
                    else -> 0f
                }
                if (coverage >= MIN_COVERAGE) key.length to capability else null
            }
            .maxByOrNull { it.first }
            ?.second
    }

    private fun parseCatalog(text: String): Map<String, ModelCapability> {
        val catalog = json.decodeFromString<Map<String, RawProvider>>(text)
        return buildMap {
            catalog.values.forEach { provider ->
                provider.models.forEach { (key, model) ->
                    val id = model.id?.takeIf { it.isNotBlank() } ?: key
                    val normalized = normalize(id)
                    if (normalized.isNotEmpty()) putIfAbsent(normalized, model.toCapability())
                }
            }
        }
    }

    private fun RawModel.toCapability(): ModelCapability {
        val effort = reasoningOptions.firstOrNull { it.type == "effort" }?.values.orEmpty()
        val hasToggle = reasoningOptions.any { it.type == "toggle" }
        return ModelCapability(
            reasoning = reasoning,
            canDisable = !reasoning || hasToggle || effort.any { it in noThinkingValues },
            effortValues = effort,
        )
    }

    private fun normalize(value: String): String =
        value.lowercase().filter { it.isLetterOrDigit() }

    // 只声明用得到的字段，其余靠 ignoreUnknownKeys 跳过，避免把 5MB 的 JSON 全建成对象
    @Serializable
    private data class RawProvider(
        val models: Map<String, RawModel> = emptyMap(),
    )

    @Serializable
    private data class RawModel(
        val id: String? = null,
        val reasoning: Boolean = false,
        @SerialName("reasoning_options")
        val reasoningOptions: List<RawReasoningOption> = emptyList(),
    )

    @Serializable
    private data class RawReasoningOption(
        val type: String? = null,
        val values: List<String> = emptyList(),
    )
}
