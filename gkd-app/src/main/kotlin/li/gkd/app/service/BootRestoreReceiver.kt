package li.gkd.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import li.gkd.app.app
import li.gkd.app.appScope
import li.gkd.app.permission.PermissionStates
import li.gkd.app.store.AppStore
import li.gkd.app.util.LogUtils
import li.gkd.app.util.launchLogged
import kotlin.time.Duration.Companion.milliseconds

/**
 * 开机后把 GKD 的无障碍服务写回系统设置。
 *
 * ColorOS 等系统会在重启/清理后台时移除第三方无障碍条目；只要曾经拿到
 * WRITE_SECURE_SETTINGS（Root 自授，或 Shizuku 下由特权服务执行 pm grant），
 * 这条权限会持久化，开机时不需要特权服务在线也能直接写回。
 */
class BootRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != ACTION_QUICKBOOT_POWERON
        ) {
            return
        }
        val pendingResult = goAsync()
        appScope.launchLogged(Dispatchers.IO) {
            try {
                restoreA11yAfterBoot()
            } catch (e: Throwable) {
                LogUtils.d("restoreA11yAfterBoot failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

private const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"

// goAsync 的广播执行窗口约 10s，这里的总耗时控制在窗口内。
private const val RESTORE_ATTEMPTS = 3
private val PERMISSION_WAIT = 800.milliseconds
private val FIX_DELAY = 600.milliseconds
private val START_DELAY = 1200.milliseconds

private suspend fun restoreA11yAfterBoot() {
    val settings = AppStore.storeFlow.value
    if (!settings.enableAutomator || !settings.useA11y) {
        LogUtils.d("restoreA11yAfterBoot skipped: automation disabled or not a11y mode")
        return
    }
    repeat(RESTORE_ATTEMPTS) { attempt ->
        if (A11yService.isRunning.value) {
            return
        }
        if (!PermissionStates.writeSecureSettings.updateAndGet()) {
            // 权限由 Root 自授或 Shizuku pm grant 持久化，通常瞬间可得；
            // 等不到就放弃，避免在广播窗口内反复空转。
            delay(PERMISSION_WAIT)
            return@repeat
        }
        val names = app.getSecureA11yServices()
        if (names.contains(A11yService.a11yCn)) {
            // 条目在但服务没跑，按"故障重启"处理：先移除，等待系统反应，再加回。
            names.remove(A11yService.a11yCn)
            app.putSecureA11yServices(names)
            delay(FIX_DELAY)
        }
        names.add(A11yService.a11yCn)
        app.putSecureA11yServices(names)
        delay(START_DELAY)
        LogUtils.d(
            "restoreA11yAfterBoot",
            "attempt=${attempt + 1} running=${A11yService.isRunning.value}",
        )
    }
}
