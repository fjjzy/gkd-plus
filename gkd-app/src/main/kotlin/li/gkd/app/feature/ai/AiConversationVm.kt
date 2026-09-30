package li.gkd.app.feature.ai

import kotlinx.coroutines.launch
import li.gkd.app.appScope
import li.gkd.app.ui.share.BaseViewModel
import li.gkd.app.ui.share.launchUi
import li.gkd.app.util.AiRuleGenerator
import li.gkd.app.util.ToastUtils.toast
import li.gkd.app.util.launchLogged

class AiConversationVm : BaseViewModel() {

    fun sendUserMessage(snapshotId: Long, text: String) {
        scope.launchUi {
            val existing = AiConversationStore.get(snapshotId)
            if (existing == null) {
                toast("会话不存在")
                return@launchUi
            }
            AiConversationStore.appendMessage(
                snapshotId = snapshotId,
                message = AiMessage(role = "user", content = text, time = System.currentTimeMillis()),
                status = AiConversation.STATUS_GENERATING,
            )
        }
        // 生成任务独立于页面生命周期，退到后台/杀页面也不会中断
        appScope.launchLogged {
            AiRuleGenerator.generateConversationRule(snapshotId, text)
        }
    }
}
