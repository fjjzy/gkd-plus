package li.gkd.app.feature.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import li.gkd.app.appScope
import li.gkd.app.data.ComplexSnapshot
import li.gkd.app.data.NodeInfo
import li.gkd.app.data.snapshot.SnapshotRepository
import li.gkd.app.ui.share.BaseViewModel
import li.gkd.app.ui.share.launchUi
import li.gkd.app.util.AiRuleGenerator
import li.gkd.app.util.keepNullJson
import li.gkd.app.util.launchLogged

class AiSnapshotPickerVm : BaseViewModel() {

    private val snapshotMutable = MutableStateFlow<ComplexSnapshot?>(null)
    val snapshot: StateFlow<ComplexSnapshot?> = snapshotMutable

    fun loadSnapshot(snapshotId: Long) {
        if (snapshotMutable.value?.id == snapshotId) return
        scope.launchUi {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    keepNullJson.decodeFromString<ComplexSnapshot>(
                        SnapshotRepository.snapshotFile(snapshotId).readText()
                    )
                }.getOrNull()
            }
            snapshotMutable.value = loaded
        }
    }

    fun screenshotFile(snapshotId: Long) = SnapshotRepository.screenshotFile(snapshotId)

    /**
     * 对话生成：创建/复用该快照的会话，记录选中节点，跳转会话页输入要求
     */
    fun startConversation(snapshotId: Long, appId: String, node: NodeInfo) {
        scope.launchUi {
            if (AiConversationStore.get(snapshotId) == null) {
                AiConversationStore.upsert(
                    AiConversation(snapshotId = snapshotId, appId = appId)
                )
            }
            AiConversationStore.appendMessage(
                snapshotId = snapshotId,
                message = AiMessage(
                    role = "user",
                    content = "已选中组件：${nodeDescription(node)}",
                    time = System.currentTimeMillis(),
                ),
            )
        }
    }

    /**
     * 直接生成：把选中节点信息注入 prompt，后台生成规则
     */
    fun generateDirect(snapshotId: Long, node: NodeInfo) {
        scope.launchUi {
            val appId = snapshotMutable.value?.appId ?: return@launchUi
            if (AiConversationStore.get(snapshotId) == null) {
                AiConversationStore.upsert(
                    AiConversation(snapshotId = snapshotId, appId = appId)
                )
            }
            AiConversationStore.appendMessage(
                snapshotId = snapshotId,
                message = AiMessage(
                    role = "user",
                    content = "直接生成：目标组件 ${nodeDescription(node)}",
                    time = System.currentTimeMillis(),
                ),
                status = AiConversation.STATUS_GENERATING,
            )
        }
        appScope.launchLogged {
            AiRuleGenerator.generateConversationRule(
                snapshotId,
                "请针对以下组件生成关闭/点击规则：${nodeDescription(node)}",
            )
        }
    }

    private fun nodeDescription(node: NodeInfo): String {
        val attr = node.attr
        return buildString {
            append(attr.text ?: attr.desc ?: attr.id ?: "(无文本)")
            attr.vid?.let { append(" [id=$it]") }
            append(" [name=${attr.name}]")
            append(" [bounds=${attr.left},${attr.top},${attr.right},${attr.bottom}]")
        }
    }
}
