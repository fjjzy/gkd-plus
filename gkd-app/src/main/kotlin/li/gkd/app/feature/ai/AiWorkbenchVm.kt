package li.gkd.app.feature.ai

import li.gkd.app.store.AppStore

class AiWorkbenchVm : li.gkd.app.ui.share.BaseViewModel() {

    fun setAiEnable(enabled: Boolean) {
        AppStore.updateSettings { it.copy(aiEnable = enabled) }
    }
}
