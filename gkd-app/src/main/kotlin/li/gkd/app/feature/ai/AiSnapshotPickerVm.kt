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

fun NodeInfo.toSelectedComponent(): AiSelectedComponent = AiSelectedComponent(
    text = attr.text,
    desc = attr.desc,
    vid = attr.vid ?: attr.id?.substringAfterLast('/', attr.id),
    name = attr.name,
    bounds = "${attr.left},${attr.top},${attr.right},${attr.bottom}",
)

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
     * 对话生成：记录选中的组件列表到会话，跳转会话页输入要求
     */
    fun startConversation(snapshotId: Long, appId: String, nodes: List<NodeInfo>) {
        scope.launchUi {
            AiRuleLauncher.ensureConversation(snapshotId, appId)
            AiConversationStore.setSelectedComponents(
                snapshotId,
                nodes.map { it.toSelectedComponent() },
            )
        }
    }

    /**
     * 直接生成：记录选中组件列表并后台生成规则
     */
    fun generateDirect(snapshotId: Long, appId: String, nodes: List<NodeInfo>) {
        scope.launchUi {
            AiRuleLauncher.ensureConversation(snapshotId, appId)
            AiConversationStore.setSelectedComponents(
                snapshotId,
                nodes.map { it.toSelectedComponent() },
            )
        }
        appScope.launchLogged {
            AiRuleGenerator.generateConversationRule(snapshotId, null)
        }
    }
}
