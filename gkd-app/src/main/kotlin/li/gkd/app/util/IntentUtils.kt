package li.gkd.app.util

import li.gkd.app.text.UiStrings
import li.gkd.app.util.ToastUtils.toast

import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import li.gkd.app.META
import li.gkd.app.app
import li.gkd.app.permission.PermissionStates
import li.gkd.app.platform.lifecycle.MainActivityVisibility
import li.gkd.app.priv.PrivilegeContext
import li.songe.codeorigin.CallSite
import kotlin.reflect.KClass

object IntentUtils {
    fun openWeChatScaner() {
        val intent = app.packageManager.getLaunchIntentForPackage("com.tencent.mm")?.apply {
            putExtra("LauncherUI.From.Scaner.Shortcut", true)
        }
        if (intent == null) {
            toast(UiStrings.wechat_unavailable)
            return
        }
        app.tryStartActivity(intent)
    }

    fun openA11ySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        app.tryStartActivity(intent)
    }

    fun openAppDetailsSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = "package:${app.packageName}".toUri()
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        app.tryStartActivity(intent)
    }

    /**
     * 各厂商的自启动管理页没有统一 API，只能按顺序逐个探测；
     * 都打不开时退回应用详情，由用户自行查找。
     */
    fun openAutoStartSettings() {
        for (intent in autostartIntents) {
            try {
                app.startActivity(intent)
                return
            } catch (e: Exception) {
                // 组件不存在、未导出或被系统签名权限拦截，继续尝试下一个
                LogUtils.d("openAutoStartSettings", e)
            }
        }
        toast(autostartFallbackHint())
        openAppDetailsSettings()
    }

    /**
     * ColorOS 等系统把自启动页设为签名权限，普通应用无法启动。
     * 仅有 Root 特权服务时通过 `am start` 以 uid 0 打开；Shizuku 的 shell 身份同样没有该权限。
     */
    suspend fun openAutoStartSettingsByRoot(privilegeContext: PrivilegeContext): Boolean {
        if (privilegeContext.serverInfo.uid != 0 || !isColorOsBrand()) return false
        for (intent in autostartIntents) {
            val component = intent.component ?: continue
            if (privilegeContext.startActivityByRoot(component)) return true
        }
        return false
    }

    // 按市场保有量排序；同一厂商的主入口在前，fallback 紧跟其后
    private val autostartIntents by lazy {
        listOf(
            // OPPO / 一加 / realme（ColorOS 13+ 自启动页在电池 App，且受系统签名权限保护）
            autostartIntent(
                "com.oplus.battery",
                "com.oplus.startupapp.view.StartupAppListActivity",
            ),
            autostartActionIntent(
                action = "com.oplus.battery.permission.startup.StartupAppListActivity",
                packageName = "com.oplus.battery",
            ),
            autostartIntent(
                "com.oplus.safecenter",
                "com.oplus.safecenter.startupapp.view.StartupAppListActivity",
            ),
            autostartIntent(
                "com.oplus.safecenter",
                "com.oplus.safecenter.startupapp.StartupAppListActivity",
            ),
            autostartIntent(
                "com.coloros.safecenter",
                "com.coloros.safecenter.startupapp.StartupAppListActivity",
            ),
            autostartIntent(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startupapp.StartupAppListActivity",
            ),
            autostartIntent(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startupmanager.StartupAppListActivity",
            ),
            autostartIntent(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            ),
            autostartIntent(
                "com.oplus.safecenter",
                "com.oplus.safecenter.permission.startup.StartupAppListActivity",
            ),
            autostartIntent(
                "com.oppo.safe",
                "com.oppo.safe.permission.startup.StartupAppListActivity",
            ),
            // 小米 / Redmi / POCO
            autostartIntent(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            ),
            autostartIntent(
                "com.miui.securitycenter",
                "com.miui.powercenter.PowerSettings",
            ),
            autostartActionIntent("miui.intent.action.OP_AUTO_START"),
            // 华为 / 荣耀
            autostartIntent(
                "com.hihonor.systemmanager",
                "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            ),
            autostartIntent(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            ),
            autostartIntent(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
            ),
            autostartIntent(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.process.ProtectActivity",
            ),
            // vivo / iQOO
            autostartIntent(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            ),
            autostartIntent(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.PurviewTabActivity",
            ),
            autostartIntent(
                "com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            ),
            autostartIntent(
                "com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
            ),
            autostartIntent(
                "com.iqoo.secure",
                "com.iqoo.secure.safeguard.PurviewTabActivity",
            ),
            // 三星
            autostartIntent(
                "com.samsung.android.lool",
                "com.samsung.android.sm.ui.battery.BatteryActivity",
            ),
            autostartIntent(
                "com.samsung.android.lool",
                "com.samsung.android.sm.battery.ui.usage.CheckableAppListActivity",
            ),
            autostartIntent(
                "com.samsung.android.lool",
                "com.samsung.android.sm.battery.ui.BatteryActivity",
            ),
            autostartIntent(
                "com.samsung.android.sm_cn",
                "com.samsung.android.sm.ui.ram.AutoRunActivity",
            ),
            // 魅族
            autostartIntent(
                "com.meizu.safe",
                "com.meizu.safe.permission.SmartBGActivity",
            ),
            autostartIntent(
                "com.meizu.safe",
                "com.meizu.safe.permission.PermissionMainActivity",
            ),
            autostartIntent(
                "com.meizu.safe",
                "com.meizu.safe.security.SHOW_APPSEC",
            ),
            // 一加旧系统
            autostartIntent(
                "com.oneplus.security",
                "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
            ),
            autostartActionIntent("com.android.settings.action.BACKGROUND_OPTIMIZE"),
            // 华硕 / ROG
            autostartIntent(
                "com.asus.mobilemanager",
                "com.asus.mobilemanager.autostart.AutoStartActivity",
            ),
            autostartIntent(
                "com.asus.mobilemanager",
                "com.asus.mobilemanager.powersaver.PowerSaverSettings",
            ),
            // 乐视
            autostartIntent(
                "com.letv.android.letvsafe",
                "com.letv.android.letvsafe.AutobootManageActivity",
            ),
            // 诺基亚
            autostartIntent(
                "com.evenwell.powersaving.g3",
                "com.evenwell.powersaving.g3.exception.PowerSaverExceptionActivity",
            ),
            // 传音 / itel / TECNO / Infinix
            autostartIntent(
                "com.transsion.phonemaster",
                "com.cyin.himgr.autostart.AutoStartActivity",
            ),
            // 360 / 酷派（未验证）
            autostartIntent(
                "com.yulong.android.coolsafe",
                "com.yulong.android.coolsafe.ui.activity.autorun.AutoRunListActivity",
            ),
        )
    }

    fun openUri(uri: String) {
        val parsedUri = try {
            uri.toUri()
        } catch (e: Exception) {
            e.printStackTrace()
            toast(UiStrings.link_invalid)
            return
        }
        openUri(parsedUri)
    }

    fun openUri(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, uri)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.tryStartActivity(intent)
    }

    fun <T : Service> stopServiceByClass(clazz: KClass<T>) {
        val intent = Intent(app, clazz.java)
        app.stopService(intent)
    }

    fun <T : Service> startForegroundServiceByClass(
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
            LogUtils.d(e, loc = loc)
            val prefix = if (MainActivityVisibility.isVisible) "" else "${META.appName}: "
            toast(UiStrings.service_launch_failed(prefix, e.message), forced = true, loc = loc)
        }
    }
}

private fun autostartIntent(packageName: String, className: String): Intent {
    return Intent().apply {
        component = ComponentName(packageName, className)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

private fun autostartActionIntent(action: String, packageName: String? = null): Intent {
    return Intent(action).apply {
        if (packageName != null) setPackage(packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

private fun isColorOsBrand(): Boolean {
    val brand = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase()
    return brand.contains("oppo") ||
            brand.contains("oneplus") ||
            brand.contains("realme") ||
            brand.contains("oplus")
}

private fun autostartFallbackHint(): String {
    return if (isColorOsBrand()) {
        UiStrings.keep_alive_guide_autostart_fallback_oppo
    } else {
        UiStrings.keep_alive_guide_autostart_fallback
    }
}
