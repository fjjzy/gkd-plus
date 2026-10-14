package li.gkd.app.service

import kotlinx.coroutines.Dispatchers
import li.gkd.app.a11y.A11yRuntime
import li.gkd.app.appScope
import li.gkd.app.ui.share.launchUi
import li.gkd.app.util.systemUiAppId

class ButtonTileService : BaseTileService() {
    override val activeFlow = ButtonService.isRunning

    // 未开启则启动并展开菜单，已开启则只展开菜单；关闭悬浮按钮走按钮自身长按 / 通知「停止」/ 高级页开关
    override fun onTileClick() {
        ButtonService.start(expandMenu = true)
        collapseQuickSettingsPanel()
    }
}

/**
 * 点磁贴后控制栏不会自动收起，展开的悬浮菜单会被挡在后面，所以主动收起。
 * 只在无障碍根节点仍是 SystemUI（即控制栏确实展开）时按返回，
 * 否则返回键会退掉用户当前所在的应用。
 */
private fun collapseQuickSettingsPanel() {
    appScope.launchUi(Dispatchers.IO) {
        if (A11yRuntime.getRoot()?.packageName?.toString() == systemUiAppId) {
            A11yRuntime.performActionBack()
        }
    }
}
