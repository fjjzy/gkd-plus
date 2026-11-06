package li.gkd.app.feature.ai

import li.gkd.app.settings.SettingsRepository
import li.gkd.app.ui.state.BaseViewModel
import li.gkd.app.ui.share.launchUi

class AiWorkbenchVm : BaseViewModel() {

    fun setAiEnable(enabled: Boolean) {
        SettingsRepository.updateSettings { it.copy(aiEnable = enabled) }
    }

    fun setAiTileDirectPicker(enabled: Boolean) {
        SettingsRepository.updateSettings { it.copy(aiTileDirectPicker = enabled) }
    }

    fun deleteConversation(snapshotId: Long) {
        scope.launchUi {
            AiConversationStore.delete(snapshotId)
        }
    }
}
