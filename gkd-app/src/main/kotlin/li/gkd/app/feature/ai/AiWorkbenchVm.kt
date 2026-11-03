package li.gkd.app.feature.ai

import li.gkd.app.store.AppStore
import li.gkd.app.ui.share.BaseViewModel
import li.gkd.app.ui.share.launchUi

class AiWorkbenchVm : BaseViewModel() {

    fun setAiEnable(enabled: Boolean) {
        AppStore.updateSettings { it.copy(aiEnable = enabled) }
    }

    fun setAiTileDirectPicker(enabled: Boolean) {
        AppStore.updateSettings { it.copy(aiTileDirectPicker = enabled) }
    }

    fun deleteConversation(snapshotId: Long) {
        scope.launchUi {
            AiConversationStore.delete(snapshotId)
        }
    }
}
