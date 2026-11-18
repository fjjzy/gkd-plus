package li.gkd.app.feature.ai

import kotlinx.coroutines.launch
import li.gkd.app.app.applicationScope
import li.gkd.app.ui.state.BaseViewModel
import li.gkd.app.ui.share.launchUi
import li.gkd.app.util.AiRuleGenerator
import li.gkd.app.util.toast
import li.gkd.app.ui.share.launchUi

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
            // 只发送用户原话；选中组件是会话级上下文，由 generateConversationRule 放进 system 消息，
            // 不拼进用户消息，否则模型分不清哪些是用户说的。
            AiConversationStore.appendMessage(
                snapshotId = snapshotId,
                message = AiMessage(
                    role = "user",
                    content = text,
                    time = System.currentTimeMillis(),
                ),
                status = AiConversation.STATUS_GENERATING,
            )
        }
        // 生成任务独立于页面生命周期，退到后台/退出页面都不会中断；
        // 但要能主动停止，所以交给 AiRuleGenerator 持有 Job
        AiRuleGenerator.startConversationRule(snapshotId)
    }

    fun clearConversation(snapshotId: Long) {
        scope.launchUi {
            AiConversationStore.clearMessages(snapshotId)
        }
    }

    /** 用户点「停止」：取消当前这一轮生成 */
    fun stopGeneration(snapshotId: Long) {
        AiRuleGenerator.stopConversationRule(snapshotId)
    }

    /**
     * 进程被杀等情况会让会话停在 generating，重新进来就既发不出消息、也没有任务在跑。
     * 这里把它恢复成可发送状态。
     */
    fun recoverInterruptedGeneration(snapshotId: Long) {
        scope.launchUi {
            val conversation = AiConversationStore.get(snapshotId) ?: return@launchUi
            if (conversation.status != AiConversation.STATUS_GENERATING) return@launchUi
            if (AiRuleGenerator.isConversationRunning(snapshotId)) return@launchUi
            AiConversationStore.updateStatus(snapshotId, AiConversation.STATUS_IDLE)
            toast("上次的生成已中断，可以重新发送")
        }
    }

    /**
     * 会话页打开：选中多个组件时提示可以用「组件 1」「组件 2」指代。
     * 是否提示与次数由 Store 决定，页面只在进入时触发一次，不监听状态。
     */
    fun notifyMultiSelectHint(snapshotId: Long) {
        val count = AiConversationStore.consumeMultiSelectHint(snapshotId) ?: return
        toast("已选中 $count 个组件：发消息时可以用「组件 1」「组件 2」分别指代")
    }

    /** 用户点「规则没生效？重新生成」建议 /「生成失败，请重试」卡片 */
    fun retryRule(snapshotId: Long, feedback: String) {
        AiRuleGenerator.retryConversationRule(snapshotId, feedback)
    }

    /** 空会话里的「为我生成规则」：带一条用户消息直接发起生成，不用先输入 */
    fun generateRule(snapshotId: Long) {
        val components = AiConversationStore.get(snapshotId)?.selectedComponents.orEmpty()
        sendUserMessage(
            snapshotId = snapshotId,
            text = if (components.isEmpty()) {
                "请直接为该快照生成关闭/点击规则"
            } else {
                "请针对我选中的组件生成规则"
            },
        )
    }

    fun acceptRule(snapshotId: Long) {
        applicationScope().launchUi {
            AiRuleGenerator.acceptGeneratedRule(snapshotId)
        }
    }
}
