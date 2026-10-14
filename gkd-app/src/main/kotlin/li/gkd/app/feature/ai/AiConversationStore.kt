package li.gkd.app.feature.ai

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import li.gkd.app.data.settings.AiConfig
import li.gkd.app.store.AppStore

@Serializable
data class AiMessage(
    val role: String, // user / assistant
    val content: String,
    val time: Long,
    /** 思考模型的思考过程；正文之前流式到达，默认折叠展示 */
    val reasoning: String = "",
)

/**
 * 会话中选中的目标组件（精简后的节点信息，可序列化持久化）
 */
@Serializable
data class AiSelectedComponent(
    val text: String? = null,
    val desc: String? = null,
    val vid: String? = null,
    val name: String? = null,
    val bounds: String = "",
    /** 节点自身是否可点击；false 时模型需要从快照里找可点击的父/子节点 */
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
) {
    /**
     * 展示文本：组件 text 若全是私用区字符（应用自定义 emoji），渲染会变成问号，
     * 此时降级用 desc / vid 标识。
     */
    fun displayText(): String {
        val t = text?.takeIf { it.isNotBlank() && it.any { ch -> ch !in '\uE000'..'\uF8FF' } }
        return when {
            !t.isNullOrBlank() -> t
            !desc.isNullOrBlank() -> desc
            !vid.isNullOrBlank() -> "id=$vid"
            text?.isNotBlank() == true -> "表情图标($bounds)"
            else -> "组件($bounds)"
        }
    }
}

/**
 * 生成时实际使用的参数快照。**故意不含 apiKey**——它会随对话一起被导出成可分享的文件。
 */
@Serializable
data class AiGenerateConfig(
    val protocol: String = "",
    val model: String = "",
    val temperature: Float = 0f,
    val topP: Float = 1f,
    val maxTokens: Int = 0,
    val thinkingLevel: String = "",
    val thinkingCustom: String = "",
)

@Serializable
data class AiConversation(
    val snapshotId: Long,
    val appId: String,
    val messages: List<AiMessage> = emptyList(),
    val status: String = STATUS_IDLE,
    val lastRule: String? = null,
    val updateTime: Long = 0L,
    val selectedComponents: List<AiSelectedComponent> = emptyList(),
    /** 最近一次生成使用的参数；老会话为空，导出时回退到当前配置 */
    val generateConfig: AiGenerateConfig? = null,
) {
    companion object {
        const val STATUS_IDLE = "idle"
        const val STATUS_GENERATING = "generating"
        const val STATUS_SUCCESS = "success"
        // 规则已生成但等待用户点「加入规则」
        const val STATUS_RULE_READY = "rule_ready"
        // 规则已自动入库（自动来源触发的识别）
        const val STATUS_ACCEPTED = "accepted"
        const val STATUS_FAILED = "failed"
    }
}

/** 生成时把配置压成可导出的快照；apiKey 不参与 */
fun AiConfig.toGenerateConfig(): AiGenerateConfig = AiGenerateConfig(
    protocol = protocol,
    model = model,
    temperature = temperature,
    topP = topP,
    maxTokens = maxTokens,
    thinkingLevel = thinkingLevel,
    thinkingCustom = thinkingCustom,
)

/**
 * AI 会话的内存门面：真实状态持久化在 SettingsRepository（ai_conversations.json），
 * 随设置的备份/导入恢复流程一起导出与还原。
 */
object AiConversationStore {

    private const val MAX_CONVERSATIONS = 100

    val conversationsFlow: StateFlow<List<AiConversation>>
        get() = AppStore.aiConversationsFlow

    private val mutationMutex = Mutex()

    fun get(snapshotId: Long): AiConversation? =
        conversationsFlow.value.find { it.snapshotId == snapshotId }

    suspend fun upsert(conversation: AiConversation) = mutationMutex.withLock {
        val stamped = conversation.copy(updateTime = System.currentTimeMillis())
        AppStore.updateAiConversations { current ->
            buildList {
                add(stamped)
                current
                    .filter { it.snapshotId != conversation.snapshotId }
                    .sortedByDescending { it.updateTime }
                    .take(MAX_CONVERSATIONS - 1)
                    .forEach { add(it) }
            }
        }
    }

    suspend fun appendMessage(
        snapshotId: Long,
        message: AiMessage,
        status: String? = null,
    ) = mutationMutex.withLock {
        AppStore.updateAiConversations { current ->
            current.map {
                if (it.snapshotId == snapshotId) {
                    it.copy(
                        messages = it.messages + message,
                        status = status ?: it.status,
                        updateTime = System.currentTimeMillis(),
                    )
                } else {
                    it
                }
            }
        }
    }

    suspend fun updateStatus(
        snapshotId: Long,
        status: String,
        lastRule: String? = null,
    ) = mutationMutex.withLock {
        AppStore.updateAiConversations { current ->
            current.map {
                if (it.snapshotId == snapshotId) {
                    it.copy(
                        status = status,
                        lastRule = lastRule ?: it.lastRule,
                        updateTime = System.currentTimeMillis(),
                    )
                } else {
                    it
                }
            }
        }
    }

    /**
     * 记录本次生成使用的参数快照，随对话一起导出用于反馈。
     */
    suspend fun setGenerateConfig(
        snapshotId: Long,
        config: AiGenerateConfig,
    ) = mutationMutex.withLock {
        AppStore.updateAiConversations { current ->
            current.map {
                if (it.snapshotId == snapshotId) it.copy(generateConfig = config) else it
            }
        }
    }

    /**
     * 流式更新最后一条消息的内容（追加），用于 SSE 增量渲染。
     */
    suspend fun appendToLastMessage(
        snapshotId: Long,
        delta: String,
        role: String = "assistant",
    ) = updateLastMessage(snapshotId, role) { it.copy(content = it.content + delta) }

    /**
     * 流式追加思考内容。思考先于正文到达，会先建出正文为空的 assistant 消息，
     * 随后的正文增量再追加到同一条消息上。
     */
    suspend fun appendReasoningToLastMessage(
        snapshotId: Long,
        delta: String,
        role: String = "assistant",
    ) = updateLastMessage(snapshotId, role) { it.copy(reasoning = it.reasoning + delta) }

    private suspend fun updateLastMessage(
        snapshotId: Long,
        role: String,
        transform: (AiMessage) -> AiMessage,
    ) = mutationMutex.withLock {
        AppStore.updateAiConversations { current ->
            current.map { conversation ->
                if (conversation.snapshotId == snapshotId) {
                    val messages = conversation.messages
                    if (messages.isNotEmpty() && messages.last().role == role) {
                        conversation.copy(
                            messages = messages.subList(0, messages.size - 1) +
                                transform(messages.last()),
                        )
                    } else {
                        conversation.copy(
                            messages = messages + transform(
                                AiMessage(role = role, content = "", time = System.currentTimeMillis()),
                            ),
                        )
                    }
                } else {
                    conversation
                }
            }
        }
    }

    /**
     * 丢弃最后一条指定角色的消息。用于流式重试：把上一次被截断的残留内容清掉，
     * 避免重试的增量与残留拼在一起。
     */
    suspend fun dropLastMessage(
        snapshotId: Long,
        role: String = "assistant",
    ) = mutationMutex.withLock {
        AppStore.updateAiConversations { current ->
            current.map { conversation ->
                if (conversation.snapshotId == snapshotId &&
                    conversation.messages.lastOrNull()?.role == role
                ) {
                    conversation.copy(messages = conversation.messages.dropLast(1))
                } else {
                    conversation
                }
            }
        }
    }

    suspend fun delete(snapshotId: Long) = mutationMutex.withLock {
        AppStore.updateAiConversations { current ->
            current.filter { it.snapshotId != snapshotId }
        }
    }

    /**
     * 清空对话内容，但保留会话本身：快照、应用、已选组件都还在，
     * 用户可以直接重新开始提问，不用再选一次目标组件。
     */
    suspend fun clearMessages(snapshotId: Long) = mutationMutex.withLock {
        AppStore.updateAiConversations { current ->
            current.map {
                if (it.snapshotId == snapshotId) {
                    it.copy(
                        messages = emptyList(),
                        status = AiConversation.STATUS_IDLE,
                        lastRule = null,
                        updateTime = System.currentTimeMillis(),
                    )
                } else {
                    it
                }
            }
        }
    }

    /**
     * 会话页打开时，记一次「选中多个组件可以用序号指代」的提示次数。
     * 需要提示时返回选中的组件数；未达 2 个或已提示够两次返回 null。
     * 写入放在 Store，页面与 ViewModel 只负责触发。
     */
    fun consumeMultiSelectHint(snapshotId: Long): Int? {
        val count = get(snapshotId)?.selectedComponents?.size ?: return null
        if (count < 2) return null
        if (AppStore.storeFlow.value.aiMultiSelectHintCount >= 2) return null
        AppStore.updateSettings { it.copy(aiMultiSelectHintCount = it.aiMultiSelectHintCount + 1) }
        return count
    }

    /** 设置会话选中的目标组件（选择页多选 / chips 删除共用） */
    suspend fun setSelectedComponents(
        snapshotId: Long,
        components: List<AiSelectedComponent>,
    ) = mutationMutex.withLock {
        AppStore.updateAiConversations { current ->
            current.map {
                if (it.snapshotId == snapshotId) {
                    it.copy(selectedComponents = components, updateTime = System.currentTimeMillis())
                } else {
                    it
                }
            }
        }
    }
}
