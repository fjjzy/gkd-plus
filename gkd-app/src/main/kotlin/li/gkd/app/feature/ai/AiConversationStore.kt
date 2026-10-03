package li.gkd.app.feature.ai

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import li.gkd.app.store.AppStore

@Serializable
data class AiMessage(
    val role: String, // user / assistant
    val content: String,
    val time: Long,
)

@Serializable
data class AiConversation(
    val snapshotId: Long,
    val appId: String,
    val messages: List<AiMessage> = emptyList(),
    val status: String = STATUS_IDLE,
    val lastRule: String? = null,
    val updateTime: Long = 0L,
) {
    companion object {
        const val STATUS_IDLE = "idle"
        const val STATUS_GENERATING = "generating"
        const val STATUS_SUCCESS = "success"
        const val STATUS_FAILED = "failed"
    }
}

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
     * 流式更新最后一条消息的内容（追加），用于 SSE 增量渲染。
     */
    suspend fun appendToLastMessage(
        snapshotId: Long,
        delta: String,
        role: String = "assistant",
    ) = mutationMutex.withLock {
        AppStore.updateAiConversations { current ->
            current.map { conversation ->
                if (conversation.snapshotId == snapshotId) {
                    val messages = conversation.messages
                    if (messages.isNotEmpty() && messages.last().role == role) {
                        conversation.copy(
                            messages = messages.subList(0, messages.size - 1) + messages.last().copy(
                                content = messages.last().content + delta,
                            ),
                        )
                    } else {
                        conversation.copy(
                            messages = messages + AiMessage(
                                role = role,
                                content = delta,
                                time = System.currentTimeMillis(),
                            ),
                        )
                    }
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
}
