package li.gkd.app.feature.ai

import li.gkd.app.app.applicationScope
import li.gkd.app.util.AiRuleGenerator
import li.gkd.app.ui.share.launchUi

/**
 * 从快照记录发起 AI 规则生成的入口：
 * - [directGenerate]：不落页面，后台生成后 toast 提醒
 * - [ensureConversation]：确认会话存在（供「对话生成」跳转前调用）
 */
object AiRuleLauncher {

    suspend fun ensureConversation(snapshotId: Long, appId: String): AiConversation {
        AiConversationStore.get(snapshotId)?.let { return it }
        val conversation = AiConversation(snapshotId = snapshotId, appId = appId)
        AiConversationStore.upsert(conversation)
        return conversation
    }

    fun directGenerate(snapshotId: Long, appId: String) {
        applicationScope().launchUi {
            ensureConversation(snapshotId, appId)
            AiConversationStore.appendMessage(
                snapshotId = snapshotId,
                message = AiMessage(
                    role = "user",
                    content = "从快照记录发起：请直接为该快照生成关闭/点击规则",
                    time = System.currentTimeMillis(),
                ),
            )
            AiRuleGenerator.startConversationRule(snapshotId, notifyUser = true)
        }
    }
}
