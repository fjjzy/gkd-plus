package li.gkd.app.matching.action

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import li.gkd.app.matching.diagnostics.observeActionOperation
import li.gkd.app.priv.privilegeContextFlow
import li.gkd.app.priv.uiAutomationFlow
import li.gkd.app.service.A11yService
import li.gkd.app.util.AndroidTarget

suspend fun performActionBack(): Boolean {
    val result =
        privilegeContextFlow.value?.let { privilege ->
            observeActionOperation("PrivilegedBack") { privilege.keyevent(KeyEvent.KEYCODE_BACK) }
        }
    if (result != null) return result
    return A11yService.instance?.let { service ->
        observeActionOperation("AccessibilityService.performGlobalAction:BACK") {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }
    } == true
}

/**
 * 收起通知栏 / 控制栏。返回键在部分 ROM（ColorOS 17）上收不起控制栏，
 * 所以 Android 12+ 优先用系统的收起动作，不可用时再退回返回键。
 */
suspend fun performActionCollapseShade(): Boolean {
    if (AndroidTarget.S) {
        val action = AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE
        val result =
            A11yService.instance?.let { service ->
                observeActionOperation("AccessibilityService.performGlobalAction:DISMISS_SHADE") {
                    service.performGlobalAction(action)
                }
            }
                ?: uiAutomationFlow.value?.let { automation ->
                    observeActionOperation("UiAutomation.performGlobalAction:DISMISS_SHADE") {
                        automation.performGlobalAction(action)
                    }
                }
        if (result == true) return true
    }
    return performActionBack()
}
