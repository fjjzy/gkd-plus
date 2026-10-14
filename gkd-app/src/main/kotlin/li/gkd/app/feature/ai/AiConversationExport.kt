package li.gkd.app.feature.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import li.gkd.app.META
import li.gkd.app.data.settings.AiConfig
import li.gkd.app.data.snapshot.SnapshotRepository
import li.gkd.app.util.ExportFileNames
import li.gkd.app.util.FolderUtils
import java.io.File

/**
 * 把一次 AI 会话导出成可分享的 JSON，用于用户反馈。
 *
 * 内容是完整对话 + 参数 + 快照节点树，所以**不能带 apiKey**：[AiGenerateConfig] 里
 * 本来就没有这个字段，落盘前还会再过一遍 [FolderUtils.redactSensitiveText] 兜底。
 */
object AiConversationExport {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** 导出文件的字段保持稳定，便于照着反馈文件排查 */
    @Serializable
    private data class ExportFile(
        val exportedAt: Long,
        val appVersion: String,
        val snapshotId: Long,
        val appId: String,
        val conversationStatus: String,
        /** 生成时记录的参数；老会话没有这个字段，此时为 null，看 currentConfig */
        val generateConfig: AiGenerateConfig?,
        /** 参数是否来自生成时记录；false 表示退回了导出时的当前配置 */
        val generateConfigFromGeneration: Boolean,
        val currentConfig: AiGenerateConfig,
        val selectedComponents: List<AiSelectedComponent>,
        val lastRule: String?,
        val messages: List<AiMessage>,
        /** 快照节点树；文件缺失或损坏时为 null */
        val snapshot: JsonElement?,
    )

    /** 读快照、拼 JSON、写进 [FolderUtils.sharedDir]，返回可分享的文件 */
    suspend fun build(conversation: AiConversation, currentConfig: AiConfig): File =
        withContext(Dispatchers.IO) {
            val snapshotJson = runCatching {
                SnapshotRepository.snapshotFile(conversation.snapshotId).readText()
            }.getOrNull()
            val payload = ExportFile(
                exportedAt = System.currentTimeMillis(),
                appVersion = "${META.versionName} (${META.versionCode})",
                snapshotId = conversation.snapshotId,
                appId = conversation.appId,
                conversationStatus = conversation.status,
                generateConfig = conversation.generateConfig,
                generateConfigFromGeneration = conversation.generateConfig != null,
                currentConfig = currentConfig.toGenerateConfig(),
                selectedComponents = conversation.selectedComponents,
                lastRule = conversation.lastRule,
                messages = conversation.messages,
                snapshot = snapshotJson?.let {
                    runCatching { json.parseToJsonElement(it) }.getOrNull()
                },
            )
            val text = FolderUtils.redactSensitiveText(
                json.encodeToString(ExportFile.serializer(), payload)
            )
            ExportFileNames.reserve(
                FolderUtils.sharedDir,
                "gkd-plus-conversation-${conversation.appId.substringAfterLast('.')}",
                "json",
            ).apply { writeText(text) }
        }
}
