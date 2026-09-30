package li.gkd.app.feature.ai

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 悬浮窗「我来选择」捕获快照后，由 Service 记录待跳转的快照，
 * MainActivity 恢复前台时消费一次并导航到组件选择页。
 */
object AiPendingNav {
    val pendingPickerSnapshotId = MutableStateFlow<Long?>(null)

    fun consume(): Long? {
        val value = pendingPickerSnapshotId.value
        pendingPickerSnapshotId.value = null
        return value
    }
}
