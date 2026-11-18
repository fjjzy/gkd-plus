package li.gkd.app.util

import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import kotlin.reflect.KClass
import li.gkd.app.META
import li.gkd.app.app
import li.gkd.app.logging.LogUtils
import li.gkd.app.permission.PermissionStates
import li.gkd.app.platform.lifecycle.MainActivityVisibility
import li.gkd.app.priv.PrivilegeContext
import li.gkd.app.resources.*
import li.songe.codeorigin.CallSite

object IntentUtils {
    fun openWeChatScanner() {
        val intent =
            app.packageManager.getLaunchIntentForPackage("com.tencent.mm")?.apply {
                putExtra("LauncherUI.From.Scaner.Shortcut", true)
            }
        if (intent == null) {
            ToastUtils.show(Res.string.wechat_unavailable.getSync())
            return
        }
        app.tryStartActivity(intent)
    }

    fun openA11ySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        app.tryStartActivity(intent)
    }

    /**
     * 各厂商的自启动管理页没有统一 API，只能按顺序逐个探测。
     * 都打不开时：ColorOS 跳系统设置首页（应用详情里没有自启动入口，跳过去是死路），
     * 其余厂商退回应用详情。
     */
    fun openAutoStartSettings(): Boolean {
        // MIUI/HyperOS 的自启动开关就在应用详情页里，不必再跳安全中心的自启动列表
        if (isMiuiFamily()) {
            openAppDetailsSettings()
            return true
        }
        for (intent in autoStartIntents) {
            try {
                app.startActivity(intent)
                return true
            } catch (e: Exception) {
                LogUtils.d("openAutoStartSettings", e)
            }
        }
        ToastUtils.show(autoStartFallbackHint())
        // ColorOS 的应用详情页里没有自启动/耗电管理入口，跳过去等于把用户扔进死胡同
        if (isColorOsBrand()) {
            openSystemSettings()
        } else {
            openAppDetailsSettings()
        }
        return false
    }

    fun openSystemSettings() {
        val intent =
            Intent(Settings.ACTION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        app.tryStartActivity(intent)
    }

    /**
     * ColorOS 等系统把自启动页设为签名权限，普通应用无法启动。
     * 仅有 Root 特权服务时通过 `am start` 以 uid 0 打开；Shizuku 的 shell 身份同样没有该权限。
     */
    suspend fun openAutoStartSettingsByRoot(privilegeContext: PrivilegeContext): Boolean {
        if (privilegeContext.serverInfo.uid != 0 || !isColorOsBrand()) return false
        for (intent in autoStartIntents) {
            val component = intent.component ?: continue
            if (privilegeContext.startActivityByRoot(component)) return true
        }
        return false
    }

    fun openAppDetailsSettings() {
        val intent =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = "package:${app.packageName}".toUri()
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        app.tryStartActivity(intent)
    }

    fun <T : Service> stopService(clazz: KClass<T>) {
        val intent = Intent(app, clazz.java)
        app.stopService(intent)
    }

    fun <T : Service> startForegroundService(
        clazz: KClass<T>,
        @CallSite loc: String = "",
        configure: Intent.() -> Unit = {},
    ) {
        if (!PermissionStates.notification.checkOrToast(loc = loc)) return
        if (!PermissionStates.foregroundServiceSpecialUse.checkOrToast(loc = loc)) return
        val intent = Intent(app, clazz.java).apply(configure)
        try {
            app.startForegroundService(intent)
        } catch (e: Throwable) {
            LogUtils.e(e, loc = loc)
            val prefix = if (MainActivityVisibility.isVisible) "" else "${META.appName}: "
            ToastUtils.show(
                Res.string.service_launch_failed.getSync(prefix, e.message),
                forced = true,
                loc = loc,
            )
        }
    }
}


/** MIUI/HyperOS 把自启动开关放在应用详情页，没有独立的自启动列表入口 */
private fun isMiuiFamily(): Boolean =
    Build.MANUFACTURER.equals("xiaomi", ignoreCase = true) ||
        Build.BRAND.equals("xiaomi", ignoreCase = true) ||
        Build.BRAND.equals("redmi", ignoreCase = true) ||
        Build.BRAND.equals("poco", ignoreCase = true)

private fun isColorOsBrand(): Boolean {
    val brand = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase()
    return brand.contains("oppo") ||
        brand.contains("oneplus") ||
        brand.contains("realme") ||
        brand.contains("oplus")
}

private fun autoStartFallbackHint(): String =
    if (isColorOsBrand()) {
        Res.string.keep_alive_guide_autostart_fallback_oppo.getSync()
    } else {
        Res.string.keep_alive_guide_autostart_fallback.getSync()
    }

/** 按市场保有量排序；同一厂商的主入口在前，fallback 紧跟其后。 */private val autoStartIntents by lazy {
    listOf(
        autoStartComponent("com.oplus.battery", "com.oplus.startupapp.view.StartupAppListActivity"),
        autoStartAction("com.oplus.battery.permission.startup.StartupAppListActivity", "com.oplus.battery"),
        autoStartComponent("com.oplus.safecenter", "com.oplus.safecenter.startupapp.view.StartupAppListActivity"),
        autoStartComponent("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"),
        autoStartComponent("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        autoStartComponent("com.coloros.safecenter", "com.coloros.safecenter.permission.startupapp.StartupAppListActivity"),
        autoStartComponent("com.coloros.safecenter", "com.coloros.safecenter.permission.startupmanager.StartupAppListActivity"),
        autoStartComponent("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        autoStartComponent("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
        autoStartComponent("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        autoStartComponent("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        autoStartComponent("com.miui.securitycenter", "com.miui.powercenter.PowerSettings"),
        autoStartAction("miui.intent.action.OP_AUTO_START"),
        autoStartComponent("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        autoStartComponent("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        autoStartComponent("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
        autoStartComponent("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        autoStartComponent("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        autoStartComponent("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity"),
        autoStartComponent("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        autoStartComponent("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
        autoStartComponent("com.iqoo.secure", "com.iqoo.secure.safeguard.PurviewTabActivity"),
        autoStartComponent("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        autoStartComponent("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.usage.CheckableAppListActivity"),
        autoStartComponent("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
        autoStartComponent("com.samsung.android.sm_cn", "com.samsung.android.sm.ui.ram.AutoRunActivity"),
        autoStartComponent("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"),
        autoStartComponent("com.meizu.safe", "com.meizu.safe.permission.PermissionMainActivity"),
        autoStartComponent("com.meizu.safe", "com.meizu.safe.security.SHOW_APPSEC"),
        autoStartComponent("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
        autoStartAction("com.android.settings.action.BACKGROUND_OPTIMIZE"),
        autoStartComponent("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"),
        autoStartComponent("com.asus.mobilemanager", "com.asus.mobilemanager.powersaver.PowerSaverSettings"),
        autoStartComponent("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity"),
        autoStartComponent("com.evenwell.powersaving.g3", "com.evenwell.powersaving.g3.exception.PowerSaverExceptionActivity"),
        autoStartComponent("com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity"),
        autoStartComponent("com.yulong.android.coolsafe", "com.yulong.android.coolsafe.ui.activity.autorun.AutoRunListActivity"),
    )
}

private fun autoStartComponent(packageName: String, className: String) =
    Intent().apply {
        component = ComponentName(packageName, className)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

private fun autoStartAction(action: String, packageName: String? = null) =
    Intent(action).apply {
        if (packageName != null) setPackage(packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
