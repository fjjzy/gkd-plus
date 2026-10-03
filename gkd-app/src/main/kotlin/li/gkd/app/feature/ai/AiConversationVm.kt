package li.gkd.app.feature.ai

import kotlinx.coroutines.launch
import li.gkd.app.appScope
import li.gkd.app.ui.share.BaseViewModel
import li.gkd.app.ui.share.launchUi
import li.gkd.app.util.AiRuleGenerator
import li.gkd.app.util.ToastUtils.toast
import li.gkd.app.util.launchLogged

class AiConversationVm : BaseViewModel() {

    fun removeSelectedComponent(snapshotId: Long, index: Int) {
        scope.launchUi {
            val current = AiConversationStore.get(snapshotId)
            val components = current?.selectedComponents.orEmpty()
            if (index in components.indices) {
                AiConversationStore.setSelectedComponents(
                    snapshotId,
                    components.filterIndexed { i, _ -> i != index },
                )
            }
        }
    }

    fun sendUserMessage(snapshotId: Long, text: String) {
        scope.launchUi {
            val existing = AiConversationStore.get(snapshotId)
            if (existing == null) {
                toast("会话不存在")
                return@launchUi
            }
            // 选中组件随消息一起进入上下文（消息文本内联），发送后清空选中列表
            val components = existing.selectedComponents
            val content = buildString {
                components.forEach { c ->
                    append("[选中组件] text=${c.text ?: "null"} desc=${c.desc ?: "null"} ")
                    append("id=${c.vid ?: c.name ?: "null"} bounds=(${c.bounds})\n")
                }
                append(text)
            }
            AiConversationStore.appendMessage(
                snapshotId = snapshotId,
                message = AiMessage(
                    role = "user",
                    content = content,
                    time = System.currentTimeMillis(),
                ),
                status = AiConversation.STATUS_GENERATING,
            )
            if (components.isNotEmpty()) {
                AiConversationStore.setSelectedComponents(snapshotId, emptyList())
            }
        }
        // 生成任务独立于页面生命周期，退到后台/杀页面也不会中断
        appScope.launchLogged {
            AiRuleGenerator.generateConversationRule(snapshotId, text)
        }
    }

    fun acceptRule(snapshotId: Long) {
        appScope.launchLogged {
            AiRuleGenerator.acceptGeneratedRule(snapshotId)
        }
    }
}
