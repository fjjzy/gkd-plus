package li.gkd.app.service

import kotlinx.coroutines.Dispatchers
import li.gkd.app.matching.AndroidAutomationSessions
import li.gkd.app.matching.action.performActionCollapseShade
import li.gkd.app.app.applicationScope
import li.gkd.app.settings.SettingsRepository.settings
import li.gkd.app.ui.share.launchUi
import li.gkd.app.util.Constants

class ButtonTileService : BaseTileService() {
    override val activeFlow
        get() = ButtonService.isRunning

    // 未开启则启动并展开菜单，已开启则只展开菜单；关闭悬浮按钮走按钮自身长按 / 通知「停止」/ 高级页开关
    override fun onTileClick() {
        if (settings.value.aiTileDirectPicker) {
            // 直达「我来选择」：跳过菜单，不再需要先点磁贴再点菜单项
            ButtonService.start(ButtonStartMode.PickComponents, fromTile = true)
        } else {
            ButtonService.start(ButtonStartMode.Menu, fromTile = true)
        }
        collapseQuickSettingsPanel()
    }
}

/**
 * 点磁贴后控制栏不会自动收起，展开的悬浮菜单会被挡在后面，所以主动收起。
 * 只在无障碍根节点仍是 SystemUI（即控制栏确实展开）时按返回，
 * 否则返回键会退掉用户当前所在的应用。
 */
private fun collapseQuickSettingsPanel() {
    applicationScope().launchUi(Dispatchers.IO) {
        if (AndroidAutomationSessions.getRoot()?.packageName?.toString() == Constants.systemUiAppId) {
            performActionCollapseShade()
        }
    }
}
